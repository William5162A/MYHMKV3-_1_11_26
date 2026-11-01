package com.example.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * خدمة مستقلة للتخزين المشفر لملفات PDF محلياً داخل مساحة التطبيق الخاصة.
 *
 * المواصفات الأمنية والمعمارية:
 * 1. المفاتيح: تُولّد وتُدار عبر Android Keystore باستخدام خوارزمية AES-256.
 * 2. التشفير: AES/GCM/NoPadding مع مصادقة سلامة متكاملة (128-bit Auth Tag).
 * 3. متجه التهيئة (IV): عشوائي بطول 12 بايت لكل عملية تشفير فريدة عبر SecureRandom.
 * 4. بنية الترويسة:
 *    - 4 بايت: Magic Bytes ("MPDF" = 0x4D, 0x50, 0x44, 0x46)
 *    - 1 بايت: Version (0x01)
 *    - 12 بايت: Random IV
 *    - الباقي: Ciphertext + 16 بايت GCM Authentication Tag
 * 5. التدفق التدفقي: معالجة البيانات بأجزاء (Chunks) بحجم 64KB لمنع استهلاك الذاكرة العشوائية.
 * 6. الاستبدال الذري: كتابة الناتج أولاً إلى ملف مؤقت في نفس المجلد، ثم استبداله ذرياً عبر
 *    android.system.Os.rename دون حذف الملف القائم مسبقاً للحفاظ عليه في حال فشل العملية.
 * 7. الأمان الصارم للمسارات: فحص canonicalPath مع فاصل المجلدات لمنع أي هجمات Path Prefix Injection.
 */
object PdfSecurityStorageService {
    private const val TAG = "PdfSecurityStorage"

    const val DEFAULT_KEY_ALIAS = "midad_pdf_encryption_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB

    // الترويسة الثابتة والإصدار
    val MAGIC_HEADER = byteArrayOf(0x4D, 0x50, 0x44, 0x46) // "MPDF"
    const val CURRENT_VERSION: Byte = 0x01
    const val HEADER_TOTAL_LENGTH = 5 // 4 بايت magic + 1 بايت version
    const val MIN_ENCRYPTED_FILE_SIZE = HEADER_TOTAL_LENGTH + GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8) // 33 بايت

    // توقيع PDF الثنائي الأصلي (%PDF-)
    private val PDF_SIGNATURE = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)

    private val secureRandom = SecureRandom()

    /**
     * خطاف مخصص لبيئة الاختبارات (Robolectric / Unit Tests) في حال عدم توفر مزود AndroidKeyStore
     * محمي بـ @Volatile ومحصور في نطاق الاختبارات عبر @VisibleForTesting
     */
    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal var testKeyProvider: (() -> SecretKey)? = null

    // =========================================================================
    // 1. توليد وإدارة المفاتيح عبر Android Keystore
    // =========================================================================

    /**
     * جلب المفتاح المتماثل أو إنشاؤه في Android Keystore إذا لم يكن موجوداً
     */
    @Synchronized
    fun getOrCreateSecretKey(alias: String = DEFAULT_KEY_ALIAS): SecretKey {
        testKeyProvider?.invoke()?.let { return it }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        // توليد مفتاح AES بطول 256 بت
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // =========================================================================
    // 2. تشفير الملفات والاستبدال الذري
    // =========================================================================

    /**
     * تشفير ملف PDF تدفقياً وحفظه في destinationEncryptedFile مع استبدال ذري آمن
     *
     * @param sourceFile ملف الـ PDF الأصلي المقروء
     * @param destinationEncryptedFile الملف المشفر النهائي المستهدف (.enc)
     * @param alias اسم مفتاح التشفير في Keystore
     * @return Result<Unit> تفيد بنجاح العملية أو سبب الفشل
     */
    fun encryptFile(
        sourceFile: File,
        destinationEncryptedFile: File,
        alias: String = DEFAULT_KEY_ALIAS
    ): Result<Unit> {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return Result.failure(IOException("الملف المصدري غير موجود أو غير صالح"))
        }

        val sourceLength = sourceFile.length()
        if (sourceLength <= 0) {
            return Result.failure(IllegalArgumentException("لا يمكن تشفير ملف فارغ (0 بايت)"))
        }

        val maxAllowedSize = 55L * 1024L * 1024L
        if (sourceLength > maxAllowedSize) {
            return Result.failure(IllegalArgumentException("حجم الملف يتجاوز الحد الأقصى المسموح به (50 ميغابايت)"))
        }

        // التحقق من توقيع PDF الثنائي في الملف المصدري
        if (!verifyPdfSignature(sourceFile)) {
            return Result.failure(IllegalArgumentException("الملف المصدري ليس ملف PDF صالحاً (توقيع %PDF- غير موجود)"))
        }

        val destDir = destinationEncryptedFile.parentFile
            ?: return Result.failure(IOException("مجلد الوجهة للملف المشفر غير محدد"))

        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        // إنشاء ملف مؤقت في نفس المجلد لضمان وجوده على نفس نظام الملفات لتمكين النقل الذري
        val tempFile = File(destDir, "${destinationEncryptedFile.name}.tmp_${UUID.randomUUID()}")

        try {
            val secretKey = getOrCreateSecretKey(alias)

            // توليد IV عشوائي فريد بطول 12 بايت
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)

            FileInputStream(sourceFile).buffered().use { input ->
                FileOutputStream(tempFile).use { fos ->
                    fos.buffered().use { output ->
                        // 1. كتابة الترويسة الثابتة والإصدار
                        output.write(MAGIC_HEADER)
                        output.write(byteArrayOf(CURRENT_VERSION))

                        // 2. كتابة متجه التهيئة IV
                        output.write(iv)

                        // 3. قراءة الملف تدفقياً وتشفيره بأجزاء 64KB
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            val encryptedChunk = cipher.update(buffer, 0, bytesRead)
                            if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                                output.write(encryptedChunk)
                            }
                        }

                        // 4. إنهاء التشفير وإخراج Authentication Tag
                        val finalBlock = cipher.doFinal()
                        if (finalBlock != null && finalBlock.isNotEmpty()) {
                            output.write(finalBlock)
                        }

                        output.flush()
                    }
                    // تفريغ المخازن المؤقتة إلى مستوى نظام التشغيل قبل النقل
                    try {
                        fos.fd?.sync()
                    } catch (_: Exception) {
                        // بعض بيئات الاختبار أو أنظمة الملفات الافتراضية قد لا تدعم sync
                    }
                }
            }

            // 5. استبدال ذري آمن دون حذف النسخة السابقة مسبقاً
            atomicMoveFile(tempFile, destinationEncryptedFile)

            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt or replace file", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            // ملاحظة هامة: لم يتم المساس بـ destinationEncryptedFile في حال وجوده؛ تظل النسخة القديمة سليمة تماماً
            return Result.failure(e)
        }
    }

    /**
     * استبدال ذري للملف عبر java.nio.file.Files.move مع ATOMIC_MOVE و REPLACE_EXISTING
     * وFallback إلى Os.rename و renameTo مع ضمان عدم حذف الملف الهدف مسبقاً
     */
    private fun atomicMoveFile(source: File, destination: File) {
        var moved = false

        // 1. استخدام java.nio.file.Files.move مع ATOMIC_MOVE و REPLACE_EXISTING
        // ينفذ استدعاء النواة rename(2) ذرياً وفعلياً ويستبدل الملف الهدف دون حذفه مسبقاً
        try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            moved = !source.exists() && destination.exists()
        } catch (_: Throwable) {
            try {
                java.nio.file.Files.move(
                    source.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                moved = !source.exists() && destination.exists()
            } catch (_: Throwable) {
            }
        }

        // 2. إذا لم تتوفر مكتبة Files (على إصدارات أندرويد القديمة API < 26)، نلجأ إلى Os.rename
        if (!moved) {
            try {
                Os.rename(source.absolutePath, destination.absolutePath)
                moved = !source.exists() && destination.exists()
            } catch (_: Throwable) {
            }
        }

        // 3. Fallback أخير عبر renameTo
        if (!moved) {
            moved = source.renameTo(destination) && destination.exists()
        }

        if (!moved || !destination.exists()) {
            throw IOException("فشل استبدال الملف المشفر ذرياً من ${source.name} إلى ${destination.name}")
        }
    }

    // =========================================================================
    // 3. فك التشفير للعرض المؤقت
    // =========================================================================

    /**
     * فك تشفير ملف PDF مشفر إلى ملف مؤقت في مجلد الكاش الخاص بالتطبيق
     * للتمكن من تمريره إلى PdfRenderer
     *
     * @param encryptedFile الملف المشفر (.enc)
     * @param cacheDir مجلد الكاش الخاص بالتطبيق (context.cacheDir)
     * @param alias اسم مفتاح التشفير
     * @return Result<File> الملف المفكوك المؤقت، ويتم حذفه تلقائياً عند أي خطأ
     */
    fun decryptToTempFile(
        encryptedFile: File,
        cacheDir: File,
        alias: String = DEFAULT_KEY_ALIAS
    ): Result<File> {
        if (!encryptedFile.exists() || !encryptedFile.isFile) {
            return Result.failure(IOException("الملف المشفر غير موجود أو تالف"))
        }

        if (encryptedFile.length() < MIN_ENCRYPTED_FILE_SIZE) {
            return Result.failure(IllegalArgumentException("الملف المشفر مبتور أو لا يحتوي على الحد الأدنى من الترويسة"))
        }

        val tempViewerDir = File(cacheDir, "midad_viewer_temp").apply { mkdirs() }
        val tempDecryptedFile = File(tempViewerDir, "view_${UUID.randomUUID()}.pdf")

        try {
            val secretKey = getOrCreateSecretKey(alias)

            FileInputStream(encryptedFile).buffered().use { input ->
                // 1. التحقق من ترويسة Magic Bytes (4 بايت)
                val magic = ByteArray(MAGIC_HEADER.size)
                val magicRead = input.read(magic)
                if (magicRead != MAGIC_HEADER.size || !magic.contentEquals(MAGIC_HEADER)) {
                    throw SecurityException("ترويسة الملف غير متطابقة، الملف ليس ملف PDF مشفر بواسطة مِداد")
                }

                // 2. التحقق من إصدار الملف (1 بايت)
                val version = input.read()
                if (version != CURRENT_VERSION.toInt()) {
                    throw SecurityException("إصدار الملف المشفر ($version) غير مدعوم في هذا الإصدار من التطبيق")
                }

                // 3. قراءة متجه التهيئة IV (12 بايت)
                val iv = ByteArray(GCM_IV_LENGTH_BYTES)
                val ivRead = input.read(iv)
                if (ivRead != GCM_IV_LENGTH_BYTES) {
                    throw IOException("فشل قراءة متجه التهيئة IV من الملف المشفر")
                }

                // 4. تهيئة Cipher لفك التشفير
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

                FileOutputStream(tempDecryptedFile).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val plainChunk = cipher.update(buffer, 0, bytesRead)
                        if (plainChunk != null && plainChunk.isNotEmpty()) {
                            output.write(plainChunk)
                        }
                    }

                    // 5. التحقق الحرج: اكتمال فك التشفير ومطابقة GCM Authentication Tag
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null && finalBlock.isNotEmpty()) {
                        output.write(finalBlock)
                    }

                    output.flush()
                }
            }

            // 6. التحقق النهائي من أن الملف المفكوك يبدأ بتوقيع PDF الصالح (%PDF-)
            if (!verifyPdfSignature(tempDecryptedFile)) {
                if (tempDecryptedFile.exists()) tempDecryptedFile.delete()
                return Result.failure(SecurityException("فشل التحقق: الملف المفكوك لا يطابق توقيع مستندات PDF الصالحة"))
            }

            return Result.success(tempDecryptedFile)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption or tag validation failed", e)
            if (tempDecryptedFile.exists()) {
                tempDecryptedFile.delete()
            }

            val friendlyException = when (e) {
                is AEADBadTagException, is BadPaddingException ->
                    SecurityException("فشل التحقق من سلامة الملف المشفر: رمز المصادقة (Tag) غير متطابق أو تم التعديل على الملف", e)
                is SecurityException -> e
                else -> GeneralSecurityException("تعذر فك تشفير الملف: ${e.message}", e)
            }
            return Result.failure(friendlyException)
        }
    }

    // =========================================================================
    // 4. حذف الملفات وتنظيف المجلدات الآمن
    // =========================================================================

    /**
     * التحقق من أن الملف أو المجلد يقع بصورة صارمة داخل المجلد المسموح به
     * مع منع ثغرات Path Prefix Injection المعتمدة على تشابه أسماء المجلدات الشقيقة
     * ومنع حذف المجلد الأساسي نفسه
     *
     * @param targetElement الملف أو المجلد المراد فحصه
     * @param allowedBaseDir المجلد الأساسي المعتمد
     * @param allowBaseDirItself هل يُسمح بأن يكون الهدف هو المجلد الأساسي نفسه
     */
    private fun isPathStrictlyInside(
        targetElement: File,
        allowedBaseDir: File,
        allowBaseDirItself: Boolean = false
    ): Boolean {
        return try {
            val canonicalTarget = targetElement.canonicalPath
            val canonicalBase = allowedBaseDir.canonicalPath
            val baseWithSep = if (canonicalBase.endsWith(File.separator)) canonicalBase else canonicalBase + File.separator

            if (allowBaseDirItself && canonicalTarget == canonicalBase) {
                true
            } else {
                canonicalTarget.startsWith(baseWithSep) && canonicalTarget != canonicalBase
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * حذف ملف مؤقت أو مشفر مع التحقق الصارم من أنه يقع داخل مجلد مسموح به
     * يمنع حذف المجلد الأساسي نفسه ويمنع المسارات في المجلدات الشقيقة
     */
    fun deleteSecureFile(file: File, allowedBaseDir: File): Boolean {
        return try {
            if (!isPathStrictlyInside(file, allowedBaseDir, allowBaseDirItself = false)) {
                Log.w(TAG, "Attempted to delete file outside allowed base directory: ${file.path}")
                return false
            }
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting secure file: ${file.path}", e)
            false
        }
    }

    /**
     * مسح محتويات المجلد تعاودياً وبشكل آمن مع التحقق الصارم من كل عنصر محذوف
     * والتحقق من نجاح حذف كل عنصر، مع إمكانية حذف المجلد نفسه أو الإبقاء عليه فارغاً
     */
    private fun deleteRecursivelyStrict(targetDir: File, allowedBaseDir: File, deleteTargetDirItself: Boolean): Boolean {
        if (!targetDir.exists()) return true
        if (!isPathStrictlyInside(targetDir, allowedBaseDir, allowBaseDirItself = true)) {
            Log.w(TAG, "Directory to purge is outside allowed base: ${targetDir.path}")
            return false
        }

        var allSucceeded = true
        val children = targetDir.listFiles()
        if (children != null) {
            for (child in children) {
                // التحقق الصارم من أن كل عنصر فرعي يقع داخل allowedBaseDir
                if (!isPathStrictlyInside(child, allowedBaseDir, allowBaseDirItself = false)) {
                    allSucceeded = false
                    continue
                }
                if (child.isDirectory) {
                    val subSuccess = deleteRecursivelyStrict(child, allowedBaseDir, deleteTargetDirItself = true)
                    if (!subSuccess) allSucceeded = false
                } else {
                    val deleted = child.delete()
                    if (!deleted && child.exists()) {
                        allSucceeded = false
                    }
                }
            }
        }

        if (deleteTargetDirItself && allSucceeded) {
            // لا نحذف allowedBaseDir نفسه أبداً إذا كان هو المجلد الأساسي الحاوي
            if (targetDir.canonicalPath != allowedBaseDir.canonicalPath) {
                val deleted = targetDir.delete()
                if (!deleted && targetDir.exists()) {
                    allSucceeded = false
                }
            }
        }

        return allSucceeded
    }

    /**
     * مسح مجلد العرض المؤقت (midad_viewer_temp) داخل الكاش تعاودياً بالكامل
     * ويتحقق من نجاح حذف كل ملف، ولا يعيد نجاحاً كاذباً إذا بقيت ملفات
     */
    fun purgeViewerCacheDirectory(cacheDir: File): Boolean {
        return try {
            val viewerDir = File(cacheDir, "midad_viewer_temp")
            deleteRecursivelyStrict(viewerDir, allowedBaseDir = cacheDir, deleteTargetDirItself = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error purging viewer cache directory", e)
            false
        }
    }

    /**
     * مسح مجلد التخزين المشفر (secure_encrypted_pdfs) تعاودياً بالكامل عند تسجيل الخروج
     * ويتحقق من نجاح حذف كل ملف، ولا يعيد نجاحاً كاذباً إذا بقيت ملفات
     */
    fun purgeEncryptedStorageDirectory(secureStorageDir: File, allowedParentDir: File): Boolean {
        return try {
            deleteRecursivelyStrict(secureStorageDir, allowedBaseDir = allowedParentDir, deleteTargetDirItself = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error purging encrypted storage directory", e)
            false
        }
    }

    // =========================================================================
    // 5. دوال مساعدة داخلية
    // =========================================================================

    private fun verifyPdfSignature(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(PDF_SIGNATURE.size)
                val read = input.read(buffer)
                read == PDF_SIGNATURE.size && buffer.contentEquals(PDF_SIGNATURE)
            }
        } catch (_: Exception) {
            false
        }
    }
}
