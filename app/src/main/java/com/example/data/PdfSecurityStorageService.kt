package com.example.data

import android.os.Build
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
import java.io.InputStream
import java.nio.ByteBuffer
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * بيانات استكمال التنزيل المجزأ المحفوظة في resume_metadata.json (المرحلة 2-B3-B2-2C-B1)
 */
data class ResumeMetadata(
    val formatVersion: Int = 1,
    val pdfId: String,
    val remoteFilePath: String,
    val expectedTotalBytes: Long,
    val chunkSizeBytes: Long = PdfSecurityStorageService.DEFAULT_CHUNK_SIZE_BYTES,
    val totalChunksCount: Int,
    val completedChunks: List<Int> = emptyList(),
    val serverETag: String? = null,
    val serverLastModified: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * ترويسة الجزء المشفر المستقل MCHK (المرحلة 2-B3-B2-2C-B1)
 */
data class ChunkHeader(
    val version: Byte,
    val chunkIndex: Int,
    val plainLength: Long,
    val iv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChunkHeader
        if (version != other.version) return false
        if (chunkIndex != other.chunkIndex) return false
        if (plainLength != other.plainLength) return false
        if (!iv.contentEquals(other.iv)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + chunkIndex
        result = 31 * result + plainLength.hashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

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
 *    Files.move مع ATOMIC_MOVE و REPLACE_EXISTING، مع مسار احتياطي ذري عبر android.system.Os.rename
 *    دون حذف الملف القائم مسبقاً للحفاظ عليه في حال فشل العملية.
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
    const val MAX_ALLOWED_FILE_SIZE_BYTES: Long = 50L * 1024L * 1024L // 50 ميغابايت كحد أقصى للملف

    // ترويسة ومواصفات أجزاء التنزيل المشفرة المستقلة (المرحلة 2-B3-B2-2C-B1)
    val CHUNK_MAGIC_HEADER = byteArrayOf(0x4D, 0x43, 0x48, 0x4B) // "MCHK"
    const val CHUNK_CURRENT_VERSION: Byte = 0x01
    const val DEFAULT_CHUNK_SIZE_BYTES: Long = 2L * 1024L * 1024L // 2 ميغابايت لكل جزء
    const val CHUNK_HEADER_TOTAL_LENGTH = 29 // 4 magic + 1 version + 4 index + 8 length + 12 IV
    const val CHUNK_MIN_SIZE = CHUNK_HEADER_TOTAL_LENGTH + (GCM_TAG_LENGTH_BITS / 8) // 45 بايت
    const val TEMP_CHUNKS_DIR_NAME = "temp_chunks"
    const val RESUME_METADATA_FILE_NAME = "resume_metadata.json"

    // توقيع PDF الثنائي الأصلي (%PDF-)
    private val PDF_SIGNATURE = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)

    private val secureRandom = SecureRandom()

    /**
     * خطاف مخصص حصرياً لبيئة الاختبارات (Robolectric / JVM Unit Tests) حيث لا تتوفر شريحة AndroidKeyStore العتادية.
     *
     * إيضاح أمني وتقني هام:
     * - وسم @VisibleForTesting هو تنبيه تفتيشي وتوجيه للمترجم وLint، ولا يمنع الوصول البرمجي تقنياً على مستوى Runtime في Bytecode.
     * - تم جعل الحقل internal لتقييد ظهوره داخل حزمة التطبيق فقط.
     * - بيئة الإنتاج: المتغير يكون دائماً null، وتعتمد getOrCreateSecretKey على AndroidKeyStore حصراً.
     * - لا يوجد أي تحول تلقائي أو Fallback صامت لمفتاح اختباري عند فشل AndroidKeyStore في بيئة الإنتاج؛ بل يُرمى الاستثناء فوراً.
     */
    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal var testKeyProvider: (() -> SecretKey)? = null

    /**
     * خطاف اختباري لمحاكاة فشل النقل الذري على مستوى نظام الملفات والتأكد من بقاء الملف القديم
     */
    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal var simulateAtomicMoveFailure: Boolean = false

    /**
     * خطاف اختباري لمحاكاة فشل مسار Files.move على API 26+ للتحقق من الانتقال المباشر إلى Os.rename
     */
    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal var simulateFilesMoveFailure: Boolean = false

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
     * تشفير دفق InputStream مباشرة إلى destinationEncryptedFile مع استبدال ذري آمن،
     * دون كتابة أي ملف PDF غير مشفر على القرص نهائياً.
     *
     * @param inputStream دفق البيانات الأصلي للملف (يُقرأ تدريجياً)
     * @param destinationEncryptedFile الملف المشفر النهائي المستهدف (.enc)
     * @param alias اسم مفتاح التشفير في AndroidKeyStore
     * @param maxAllowedSizeBytes الحد الأقصى المسموح به للبايتات المقروءة (افتراضياً 50MB)
     * @param totalBytesExpected إجمالي البايتات المتوقعة (إن توفرت من Content-Length) لتقارير التقدم
     * @param onProgress دالة إشعار التقدم (bytesRead, totalBytes)
     * @return Result<Unit> تفيد بنجاح التشفير والاستبدال الذري أو سبب الفشل
     */
    fun encryptStream(
        inputStream: InputStream,
        destinationEncryptedFile: File,
        alias: String = DEFAULT_KEY_ALIAS,
        maxAllowedSizeBytes: Long = MAX_ALLOWED_FILE_SIZE_BYTES,
        totalBytesExpected: Long = -1L,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> {
        val destDir = destinationEncryptedFile.parentFile
            ?: return Result.failure(IOException("مجلد الوجهة للملف المشفر غير محدد"))

        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        if (totalBytesExpected > maxAllowedSizeBytes) {
            return Result.failure(
                IllegalArgumentException("حجم الملف ($totalBytesExpected بايت) يتجاوز الحد الأقصى المسموح به (50 ميغابايت)")
            )
        }

        // إنشاء ملف مؤقت في نفس المجلد لتمكين النقل الذري على مستوى نظام الملفات
        val tempFile = File(destDir, "${destinationEncryptedFile.name}.tmp_${UUID.randomUUID()}")

        try {
            // 1. التحقق من أول خمسة بايتات ومطابقتها تماماً مع %PDF-
            val header = ByteArray(PDF_SIGNATURE.size)
            var headerBytesRead = 0
            while (headerBytesRead < PDF_SIGNATURE.size) {
                val r = inputStream.read(header, headerBytesRead, PDF_SIGNATURE.size - headerBytesRead)
                if (r == -1) break
                headerBytesRead += r
            }

            if (headerBytesRead < PDF_SIGNATURE.size || !header.contentEquals(PDF_SIGNATURE)) {
                return Result.failure(
                    IllegalArgumentException("الملف المصدري ليس ملف PDF صالحاً (توقيع %PDF- غير موجود)")
                )
            }

            val secretKey = getOrCreateSecretKey(alias)

            // 2. توليد IV عشوائي فريد بطول 12 بايت
//            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
//            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
//            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            check(iv.size == GCM_IV_LENGTH_BYTES) { "طول IV غير متوقع: ${iv.size}" }

            FileOutputStream(tempFile).use { fos ->
                fos.buffered().use { output ->
                    // 3. كتابة الترويسة الثابتة والإصدار
                    output.write(MAGIC_HEADER)
                    output.write(byteArrayOf(CURRENT_VERSION))

                    // 4. كتابة متجه التهيئة IV
                    output.write(iv)

                    // 5. تشفير أول 5 بايتات (%PDF-) التي تم التحقق منها مسبقاً
                    val encHeader = cipher.update(header)
                    if (encHeader != null && encHeader.isNotEmpty()) {
                        output.write(encHeader)
                    }

                    var totalRead = headerBytesRead.toLong()
                    onProgress?.invoke(totalRead, totalBytesExpected)

                    // 6. قراءة باقي الدفق وتشفيره تدريجياً بأجزاء 64KB دون تحميل الملف كاملاً في الذاكرة
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalRead += bytesRead
                        if (totalRead > maxAllowedSizeBytes) {
                            throw IllegalArgumentException("حجم الملف يتجاوز الحد الأقصى المسموح به (50 ميغابايت)")
                        }

                        val encryptedChunk = cipher.update(buffer, 0, bytesRead)
                        if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                            output.write(encryptedChunk)
                        }

                        onProgress?.invoke(totalRead, totalBytesExpected)
                    }

                    // 7. إنهاء التشفير وإخراج وتثبيت GCM Authentication Tag
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null && finalBlock.isNotEmpty()) {
                        output.write(finalBlock)
                    }

                    output.flush()
                }

                // تفريغ المخازن المؤقتة إلى مستوى نظام التشغيل قبل النقل الذري
                try {
                    fos.fd?.sync()
                } catch (_: Exception) {
                    // بعض بيئات الاختبار أو أنظمة الملفات الافتراضية قد لا تدعم sync
                }
            }

            // 8. استبدال ذري موثق دون مساس بالنسخة القديمة في حال الفشل
            atomicMoveFile(tempFile, destinationEncryptedFile)

            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt stream or atomic move file", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            // ملاحظة هامة: المحافظة على الملف النهائي القديم destinationEncryptedFile دون مساس
            return Result.failure(e)
        }
    }

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

        if (sourceLength > MAX_ALLOWED_FILE_SIZE_BYTES) {
            return Result.failure(IllegalArgumentException("حجم الملف يتجاوز الحد الأقصى المسموح به (50 ميغابايت)"))
        }

        return try {
            FileInputStream(sourceFile).buffered().use { input ->
                encryptStream(
                    inputStream = input,
                    destinationEncryptedFile = destinationEncryptedFile,
                    alias = alias,
                    maxAllowedSizeBytes = MAX_ALLOWED_FILE_SIZE_BYTES,
                    totalBytesExpected = sourceLength
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * التحقق من وجود الملف المشفر محلياً وصلاحية حجمه
     */
    fun isEncryptedFilePresent(file: File): Boolean {
        return file.exists() && file.isFile && file.length() >= MIN_ENCRYPTED_FILE_SIZE
    }

    /**
     * استبدال ذري للملف يقتصر حصراً على المسارات الذرية الموثقة:
     * 1. على Android API 26+ (Oreo وما بعده):
     *    استخدام java.nio.file.Files.move مع ATOMIC_MOVE و REPLACE_EXISTING.
     *    وإذا فشل، ينتقل مباشرة إلى android.system.Os.rename كمسار احتياطي ذري.
     * 2. على Android API 24 و 25:
     *    استخدام android.system.Os.rename مباشرة (استدعاء النواة rename(2))
     *    دون محاولة استدعاء أو تحميل java.nio.file.Files أو StandardCopyOption.
     *
     * تم استبعاد File.renameTo و copyTo وأي مسار غير ذري بالكامل لعدم وجود ضمان ذري موثق في عقد Java.
     * إذا فشل النقل الذري، يُعلن الفشل فوراً عبر رمي IOException واضحة مع إرفاق الاستثناءات عبر addSuppressed
     * دون مساس بالملف النهائي القديم.
     */
    private fun atomicMoveFile(source: File, destination: File) {
        if (simulateAtomicMoveFailure) {
            throw IOException("فشل استبدال الملف ذرياً (محاكاة فشل النقل الذري للاختبار وتجاوز المسارين معاً)")
        }

        var moved = false
        var atomicMoveException: Exception? = null

        // 1. Android API 26+: استخدام java.nio.file.Files.move مع ATOMIC_MOVE و REPLACE_EXISTING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (simulateFilesMoveFailure) {
                    throw IOException("محاكاة فشل Files.move للاختبار للتحقق من الانتقال إلى Os.rename")
                }
                java.nio.file.Files.move(
                    source.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                moved = !source.exists() && destination.exists()
            } catch (e: Exception) {
                atomicMoveException = e
                Log.w(TAG, "Files.move(ATOMIC_MOVE, REPLACE_EXISTING) failed: ${e.message}; attempting Os.rename fallback")
            }
        }

        // 2. Android API 24 و 25 مباشرة، أو كمسار احتياطي ذري على API 26+ عند فشل Files.move عبر استدعاء النواة rename(2)
        var osRenameException: Exception? = null
        if (!moved) {
            try {
                Os.rename(source.absolutePath, destination.absolutePath)
                moved = !source.exists() && destination.exists()
            } catch (e: Exception) {
                osRenameException = e
                Log.w(TAG, "Os.rename failed: ${e.message}")
            }
        }

        // إذا فشل النقل الذري بالمسارين، نُعلن الفشل صراحة دون اللجوء إلى أي طريقة غير ذرية
        if (!moved || !destination.exists()) {
            val ioException = IOException(
                "فشل استبدال الملف المشفر ذرياً من ${source.name} إلى ${destination.name}: أخفق النقل الذري عبر المسارات المتاحة"
            )
            atomicMoveException?.let { ioException.addSuppressed(it) }
            osRenameException?.let { ioException.addSuppressed(it) }
            throw ioException
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

    // =========================================================================
    // 6. إدارة أجزاء PDF المشفرة وبيانات الاستكمال والتجميع الآمن (المرحلة 2-B3-B2-2C-B1)
    // =========================================================================

    /**
     * تشفير جزء محدد من ملف PDF تدفقياً وحفظه كملف مشفر مستقل (MCHK)
     * مع استبدال ذري آمن، دون كتابة أي بايت مكشوف على القرص نهائياً.
     *
     * @param inputStream دفق البيانات الخاص بالجزء المطلوب تشفيره
     * @param destinationChunkFile ملف الجزء المشفر النهائي (.enc)
     * @param chunkIndex رقم الجزء المتسلسل (يبدأ من 0)
     * @param plainLength طول البيانات غير المشفرة لهذا الجزء بالبايت
     * @param alias اسم مفتاح التشفير في Keystore
     * @return Result<File> ملف الجزء المشفر المكتمل أو سبب الفشل
     */
    fun encryptChunkStream(
        inputStream: InputStream,
        destinationChunkFile: File,
        chunkIndex: Int,
        plainLength: Long,
        alias: String = DEFAULT_KEY_ALIAS
    ): Result<File> {
        val destDir = destinationChunkFile.parentFile
            ?: return Result.failure(IOException("مجلد الوجهة لملف الجزء غير محدد"))

        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        if (chunkIndex < 0) {
            return Result.failure(IllegalArgumentException("رقم الجزء يجب ألا يكون سالباً ($chunkIndex)"))
        }

        if (plainLength <= 0 || plainLength > DEFAULT_CHUNK_SIZE_BYTES) {
            return Result.failure(
                IllegalArgumentException("حجم الجزء ($plainLength بايت) غير صالح؛ يجب أن يكون بين 1 بايت و 2 ميغابايت")
            )
        }

        val tempFile = File(destDir, "${destinationChunkFile.name}.tmp_${UUID.randomUUID()}")

        try {
            val secretKey = getOrCreateSecretKey(alias)
//            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
//            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
//            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            check(iv.size == GCM_IV_LENGTH_BYTES) { "طول IV غير متوقع: ${iv.size}" }

            FileOutputStream(tempFile).use { fos ->
                fos.buffered().use { output ->
                    // 1. ترويسة الجزء MCHK (4 بايت)
                    output.write(CHUNK_MAGIC_HEADER)

                    // 2. إصدار الجزء (1 بايت)
                    output.write(byteArrayOf(CHUNK_CURRENT_VERSION))

                    // 3. رقم الجزء (4 بايت Big-Endian)
                    val indexBytes = ByteBuffer.allocate(4).putInt(chunkIndex).array()
                    output.write(indexBytes)

                    // 4. طول البيانات غير المشفرة (8 بايت Big-Endian)
                    val lenBytes = ByteBuffer.allocate(8).putLong(plainLength).array()
                    output.write(lenBytes)

                    // 5. متجه التهيئة العشوائي المستقل (12 بايت)
                    output.write(iv)

                    // 6. قراءة الدفق وتشفيره تدفقياً عبر مخزن مؤقت 64KB
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesLeftToRead = plainLength
                    var totalRead = 0L

                    while (bytesLeftToRead > 0) {
                        val toRead = minOf(buffer.size.toLong(), bytesLeftToRead).toInt()
                        val r = inputStream.read(buffer, 0, toRead)
                        if (r == -1) break
                        totalRead += r
                        bytesLeftToRead -= r

                        val encryptedBlock = cipher.update(buffer, 0, r)
                        if (encryptedBlock != null && encryptedBlock.isNotEmpty()) {
                            output.write(encryptedBlock)
                        }
                    }

                    if (totalRead != plainLength) {
                        throw IOException("البايتات المقروءة فعلياً ($totalRead) لا تطابق الطول المتوقع للجزء ($plainLength)")
                    }

                    // التحقق الصارم من نهاية الدفق وعدم وجود بايتات زائدة تتجاوز الطول المحدد
                    val extraByte = inputStream.read()
                    if (extraByte != -1) {
                        throw IOException("دفق البيانات يحتوي على بايتات زائدة تتجاوز الطول المحدد للجزء ($plainLength بايت)")
                    }

                    // 7. إنهاء التشفير وكتابة GCM Authentication Tag (16 بايت)
                    val finalBlock = cipher.doFinal()
                    if (finalBlock != null && finalBlock.isNotEmpty()) {
                        output.write(finalBlock)
                    }

                    output.flush()
                }
                try {
                    fos.fd?.sync()
                } catch (_: Exception) {}
            }

            // 8. استبدال ذري آمن للملف النهائي
            atomicMoveFile(tempFile, destinationChunkFile)
            return Result.success(destinationChunkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt chunk $chunkIndex", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            return Result.failure(e)
        }
    }

    /**
     * قراءة وفحص ترويسة ملف الجزء المشفر (MCHK) دون فك تشفير البيانات بالكامل
     */
    fun readChunkHeader(chunkFile: File): Result<ChunkHeader> {
        if (!chunkFile.exists() || !chunkFile.isFile) {
            return Result.failure(IOException("ملف الجزء غير موجود: ${chunkFile.name}"))
        }

        val fileLength = chunkFile.length()
        if (fileLength < CHUNK_MIN_SIZE) {
            return Result.failure(IllegalArgumentException("حجم ملف الجزء ($fileLength بايت) أقل من الحد الأدنى للترويسة والمصادقة"))
        }

        try {
            FileInputStream(chunkFile).use { input ->
                // 1. Magic Bytes (4 بايت)
                val magic = ByteArray(CHUNK_MAGIC_HEADER.size)
                val magicRead = input.read(magic)
                if (magicRead != CHUNK_MAGIC_HEADER.size || !magic.contentEquals(CHUNK_MAGIC_HEADER)) {
                    return Result.failure(SecurityException("ترويسة الجزء غير متطابقة (ليس MCHK)"))
                }

                // 2. Version (1 بايت)
                val version = input.read()
                if (version != CHUNK_CURRENT_VERSION.toInt()) {
                    return Result.failure(SecurityException("إصدار الجزء ($version) غير مدعوم"))
                }

                // 3. Chunk Index (4 بايت)
                val indexBuf = ByteArray(4)
                if (input.read(indexBuf) != 4) {
                    return Result.failure(IOException("تعذر قراءة رقم الجزء"))
                }
                val chunkIndex = ByteBuffer.wrap(indexBuf).int
                if (chunkIndex < 0) {
                    return Result.failure(IllegalArgumentException("رقم الجزء سالب ($chunkIndex)"))
                }

                // 4. Plain Length (8 بايت)
                val lenBuf = ByteArray(8)
                if (input.read(lenBuf) != 8) {
                    return Result.failure(IOException("تعذر قراءة طول بيانات الجزء"))
                }
                val plainLength = ByteBuffer.wrap(lenBuf).long
                if (plainLength <= 0 || plainLength > DEFAULT_CHUNK_SIZE_BYTES) {
                    return Result.failure(IllegalArgumentException("طول بيانات الجزء ($plainLength) خارج النطاق المسموح به"))
                }

                // 5. Random IV (12 بايت)
                val iv = ByteArray(GCM_IV_LENGTH_BYTES)
                if (input.read(iv) != GCM_IV_LENGTH_BYTES) {
                    return Result.failure(IOException("تعذر قراءة متجه تهيئة الجزء"))
                }

                // 6. التحقق الدقيق من مطابقة الحجم الإجمالي للملف (Header + PlainLength + Tag)
                val expectedFileLength = CHUNK_HEADER_TOTAL_LENGTH + plainLength + (GCM_TAG_LENGTH_BITS / 8)
                if (fileLength != expectedFileLength) {
                    return Result.failure(
                        IllegalArgumentException("حجم ملف الجزء الفعلي ($fileLength) لا يطابق الحجم المحسوب من الترويسة ($expectedFileLength)")
                    )
                }

                return Result.success(ChunkHeader(version.toByte(), chunkIndex, plainLength, iv))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * التحقق من سلامة وصلاحية ملف الجزء المشفر ورقم الجزء
     */
    fun verifyChunkFile(chunkFile: File, expectedChunkIndex: Int): Result<Long> {
        val headerRes = readChunkHeader(chunkFile)
        if (headerRes.isFailure) {
            return Result.failure(headerRes.exceptionOrNull() ?: IOException("فشل قراءة ترويسة الجزء"))
        }
        val header = headerRes.getOrThrow()
        if (header.chunkIndex != expectedChunkIndex) {
            return Result.failure(
                SecurityException("رقم الجزء الموجود في الملف (${header.chunkIndex}) لا يطابق الرقم المتوقع ($expectedChunkIndex)")
            )
        }
        return Result.success(header.plainLength)
    }

    /**
     * فك تشفير جزء محدد تدفقياً وكتابة البايتات الأصلية مباشرة إلى OutputStream
     * مع التحقق الصارم من مصادقة سلامة الجزء عبر AES-GCM Tag.
     */
    fun decryptChunkStream(
        chunkFile: File,
        expectedChunkIndex: Int,
        outputStream: java.io.OutputStream,
        alias: String = DEFAULT_KEY_ALIAS
    ): Result<Long> {
        val headerRes = readChunkHeader(chunkFile)
        if (headerRes.isFailure) {
            return Result.failure(headerRes.exceptionOrNull() ?: IOException("فشل قراءة ترويسة الجزء"))
        }
        val header = headerRes.getOrThrow()
        if (header.chunkIndex != expectedChunkIndex) {
            return Result.failure(
                SecurityException("رقم الجزء (${header.chunkIndex}) لا يطابق الرقم المتوقع ($expectedChunkIndex)")
            )
        }

        try {
            val secretKey = getOrCreateSecretKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, header.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

            FileInputStream(chunkFile).use { input ->
                // تخطي ترويسة الجزء (29 بايت)
                var skipped = 0L
                while (skipped < CHUNK_HEADER_TOTAL_LENGTH) {
                    val s = input.skip(CHUNK_HEADER_TOTAL_LENGTH - skipped)
                    if (s <= 0) {
                        if (input.read() == -1) break
                        skipped++
                    } else {
                        skipped += s
                    }
                }
                if (skipped < CHUNK_HEADER_TOTAL_LENGTH) {
                    throw IOException("تعذر تخطي ترويسة الجزء كاملة")
                }

                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val plainChunk = cipher.update(buffer, 0, bytesRead)
                    if (plainChunk != null && plainChunk.isNotEmpty()) {
                        outputStream.write(plainChunk)
                    }
                }

                // التحقق الحرج من مصادقة سلامة الجزء عبر GCM Tag
                val finalBlock = cipher.doFinal()
                if (finalBlock != null && finalBlock.isNotEmpty()) {
                    outputStream.write(finalBlock)
                }
                outputStream.flush()
            }

            return Result.success(header.plainLength)
        } catch (e: Exception) {
            val friendly = when (e) {
                is AEADBadTagException, is BadPaddingException ->
                    SecurityException("فشل التحقق من سلامة الجزء رقم $expectedChunkIndex: تم التعديل عليه أو تلف رمز المصادقة", e)
                else -> e
            }
            return Result.failure(friendly)
        }
    }

    /**
     * تحديد مجلد الأجزاء المؤقتة لملف PDF لمستخدم معين مع التحقق الصارم من الأمان ومنع Path Traversal
     */
    fun getChunksDirectory(userSecureDir: File, pdfId: String): Result<File> {
        val cleanPdfId = pdfId.trim()
        try {
            UUID.fromString(cleanPdfId)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("معرف الملف (pdfId) غير صالح: يجب أن يكون بصيغة UUID"))
        }

        val baseChunksDir = File(userSecureDir, TEMP_CHUNKS_DIR_NAME)
        val targetDir = File(baseChunksDir, cleanPdfId)

        if (!isPathStrictlyInside(targetDir, userSecureDir, allowBaseDirItself = false)) {
            return Result.failure(SecurityException("المسار المستهدف يقع خارج مجلد التخزين المسموح به للمستخدم"))
        }

        return Result.success(targetDir)
    }

    /**
     * حفظ بيانات الاستكمال الوصفية في resume_metadata.json مع استبدال ذري آمن
     */
    fun saveResumeMetadata(chunksDir: File, metadata: ResumeMetadata): Result<File> {
        if (!chunksDir.exists()) {
            chunksDir.mkdirs()
        }

        try {
            UUID.fromString(metadata.pdfId.trim())
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("معرف الملف (pdfId) غير صالح: ليس UUID"))
        }

        if (metadata.expectedTotalBytes <= 0 || metadata.expectedTotalBytes > MAX_ALLOWED_FILE_SIZE_BYTES) {
            return Result.failure(IllegalArgumentException("حجم الملف المتوقع (${metadata.expectedTotalBytes}) غير صالح"))
        }

        if (metadata.chunkSizeBytes <= 0) {
            return Result.failure(IllegalArgumentException("حجم الجزء غير صالح"))
        }

        val expectedChunks = ((metadata.expectedTotalBytes + metadata.chunkSizeBytes - 1) / metadata.chunkSizeBytes).toInt()
        if (metadata.totalChunksCount != expectedChunks) {
            return Result.failure(
                IllegalArgumentException("عدد الأجزاء الإجمالي (${metadata.totalChunksCount}) لا يطابق الحساب ($expectedChunks)")
            )
        }

        val cleanPath = metadata.remoteFilePath.trim()
        if (cleanPath.isBlank() || cleanPath.contains("..") || cleanPath.startsWith("/")) {
            return Result.failure(IllegalArgumentException("مسار الملف البعيد غير آمن"))
        }

        val tempFile = File(chunksDir, "${RESUME_METADATA_FILE_NAME}.tmp_${UUID.randomUUID()}")
        val destinationFile = File(chunksDir, RESUME_METADATA_FILE_NAME)

        return try {
            val json = JSONObject().apply {
                put("formatVersion", metadata.formatVersion)
                put("pdfId", metadata.pdfId.trim())
                put("remoteFilePath", cleanPath)
                put("expectedTotalBytes", metadata.expectedTotalBytes)
                put("chunkSizeBytes", metadata.chunkSizeBytes)
                put("totalChunksCount", metadata.totalChunksCount)
                val jsonArr = JSONArray()
                metadata.completedChunks.distinct().sorted().forEach { jsonArr.put(it) }
                put("completedChunks", jsonArr)
                metadata.serverETag?.let { put("serverETag", it) }
                metadata.serverLastModified?.let { put("serverLastModified", it) }
                put("createdAtMs", metadata.createdAtMs)
                put("updatedAtMs", System.currentTimeMillis())
            }

            FileOutputStream(tempFile).use { fos ->
                fos.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(json.toString(2))
                    writer.flush()
                }
                try {
                    fos.fd?.sync()
                } catch (_: Exception) {}
            }

            atomicMoveFile(tempFile, destinationFile)
            Result.success(destinationFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save resume metadata", e)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        }
    }

    /**
     * قراءة وفحص بيانات الاستكمال الوصفية والتحقق الصارم من اتساقها الفيزيائي مع نظام الملفات
     * وحذف أي تسجيل لجزء مفقود أو معطوب مع إعادة كتابة البيانات المنقحة ذرياً.
     */
    fun loadAndValidateResumeMetadata(
        chunksDir: File,
        expectedPdfId: String,
        maxAllowedSizeBytes: Long = MAX_ALLOWED_FILE_SIZE_BYTES
    ): Result<ResumeMetadata> {
        val cleanPdfId = expectedPdfId.trim()
        try {
            UUID.fromString(cleanPdfId)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("معرف الملف غير صالح: ليس UUID"))
        }

        val metaFile = File(chunksDir, RESUME_METADATA_FILE_NAME)
        if (!metaFile.exists() || !metaFile.isFile) {
            return Result.failure(IOException("ملف بيانات الاستكمال غير موجود"))
        }

        return try {
            val jsonStr = metaFile.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)

            val parsedPdfId = json.optString("pdfId", "").trim()
            if (parsedPdfId != cleanPdfId) {
                return Result.failure(
                    SecurityException("معرف الملف في البيانات الوصفية ($parsedPdfId) لا يطابق المطلوب ($cleanPdfId)")
                )
            }

            val remotePath = json.optString("remoteFilePath", "").trim()
            if (remotePath.isBlank() || remotePath.contains("..") || remotePath.startsWith("/")) {
                return Result.failure(IllegalArgumentException("مسار الملف البعيد في البيانات الوصفية غير آمن"))
            }

            val expectedTotal = json.optLong("expectedTotalBytes", -1L)
            if (expectedTotal <= 0 || expectedTotal > maxAllowedSizeBytes) {
                return Result.failure(IllegalArgumentException("حجم الملف في البيانات الوصفية ($expectedTotal) غير صالح"))
            }

            val chunkSizeBytes = json.optLong("chunkSizeBytes", DEFAULT_CHUNK_SIZE_BYTES)
            if (chunkSizeBytes <= 0) {
                return Result.failure(IllegalArgumentException("حجم الجزء في البيانات الوصفية غير صالح"))
            }

            val expectedChunks = ((expectedTotal + chunkSizeBytes - 1) / chunkSizeBytes).toInt()
            val totalChunksCount = json.optInt("totalChunksCount", -1)
            if (totalChunksCount != expectedChunks) {
                return Result.failure(
                    IllegalArgumentException("عدد الأجزاء ($totalChunksCount) لا يطابق الحساب المتوقع ($expectedChunks)")
                )
            }

            val completedArr = json.optJSONArray("completedChunks")
            val rawIndices = mutableListOf<Int>()
            if (completedArr != null) {
                for (i in 0 until completedArr.length()) {
                    rawIndices.add(completedArr.getInt(i))
                }
            }

            // فحص الاتساق الفيزيائي مع نظام الملفات (Filesystem Consistency Check)
            val validatedChunks = mutableListOf<Int>()
            var hadInvalidOrMissing = false

            for (idx in rawIndices.distinct()) {
                if (idx in 0 until totalChunksCount) {
                    val chunkFile = File(chunksDir, "chunk_${idx}.enc")
                    val verifyRes = verifyChunkFile(chunkFile, idx)
                    if (verifyRes.isSuccess) {
                        validatedChunks.add(idx)
                    } else {
                        hadInvalidOrMissing = true
                        Log.w(TAG, "Chunk $idx recorded in metadata but corrupt or missing on disk; removing from valid chunks")
                        if (chunkFile.exists()) {
                            chunkFile.delete()
                        }
                    }
                } else {
                    hadInvalidOrMissing = true
                }
            }
            validatedChunks.sort()

            val sanitized = ResumeMetadata(
                formatVersion = json.optInt("formatVersion", 1),
                pdfId = parsedPdfId,
                remoteFilePath = remotePath,
                expectedTotalBytes = expectedTotal,
                chunkSizeBytes = chunkSizeBytes,
                totalChunksCount = totalChunksCount,
                completedChunks = validatedChunks,
                serverETag = if (json.has("serverETag")) json.optString("serverETag") else null,
                serverLastModified = if (json.has("serverLastModified")) json.optString("serverLastModified") else null,
                createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis()),
                updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis())
            )

            // إعادة كتابة الميتا داتا المنقحة ذرياً عند اكتشاف أي أجزاء معطوبة أو غير مسجلة بصورة صحيحة
            if (hadInvalidOrMissing || rawIndices.size != validatedChunks.size) {
                saveResumeMetadata(chunksDir, sanitized)
            }

            Result.success(sanitized)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load and validate resume metadata", e)
            Result.failure(e)
        }
    }

    /**
     * تجميع الأجزاء المشفرة المستقلة في ملف MPDF النهائي الموحد المتوافق تماماً مع مشغل العرض الحالي
     * عبر دفق فك تشفير وإعادة تشفير محدود الذاكرة دون كتابة أي بايت PDF مكشوف على القرص نهائياً.
     *
     * @param chunksDir مجلد الأجزاء المؤقتة الخاص بالملف
     * @param destinationEncryptedFile الملف المشفر النهائي المستهدف (.enc)
     * @param expectedTotalBytes إجمالي بايتات الملف غير المشفرة المتوقعة
     * @param totalChunksCount إجمالي عدد الأجزاء
     * @param userSecureDir مجلد التخزين الآمن للمستخدم (للتحقق من المسارات وحذف الأجزاء بعد النجاح)
     * @param alias اسم مفتاح التشفير
     * @param onProgress دالة إشعار تقدم التجميع (bytesProcessed, totalBytes)
     */
    fun assembleChunksToMpdf(
        chunksDir: File,
        destinationEncryptedFile: File,
        expectedTotalBytes: Long,
        totalChunksCount: Int,
        userSecureDir: File,
        alias: String = DEFAULT_KEY_ALIAS,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        val destDir = destinationEncryptedFile.parentFile
            ?: return Result.failure(IOException("مجلد الوجهة للملف النهائي غير محدد"))

        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        if (expectedTotalBytes <= 0 || expectedTotalBytes > MAX_ALLOWED_FILE_SIZE_BYTES) {
            return Result.failure(IllegalArgumentException("حجم الملف المتوقع ($expectedTotalBytes) غير صالح للتجميع"))
        }

        if (totalChunksCount <= 0) {
            return Result.failure(IllegalArgumentException("عدد الأجزاء ($totalChunksCount) غير صالح"))
        }

        val expectedChunks = ((expectedTotalBytes + DEFAULT_CHUNK_SIZE_BYTES - 1) / DEFAULT_CHUNK_SIZE_BYTES).toInt()
        if (totalChunksCount != expectedChunks) {
            return Result.failure(
                IllegalArgumentException("عدد الأجزاء ($totalChunksCount) لا يطابق العدد المحسوب ($expectedChunks) للحجم المتوقع ($expectedTotalBytes بايت)")
            )
        }

        val tempFinalFile = File(destDir, "${destinationEncryptedFile.name}.tmp_${UUID.randomUUID()}")

        try {
            val secretKey = getOrCreateSecretKey(alias)
//            val finalIv = ByteArray(GCM_IV_LENGTH_BYTES)
//            secureRandom.nextBytes(finalIv)

            val finalCipher = Cipher.getInstance(TRANSFORMATION)
//            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, finalIv)
            finalCipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val finalIv = finalCipher.iv
            check(finalIv.size == GCM_IV_LENGTH_BYTES) { "طول IV غير متوقع: ${finalIv.size}" }

            var hasVerifiedPdfSignature = false
            var totalBytesProcessed = 0L

            FileOutputStream(tempFinalFile).use { fos ->
                fos.buffered().use { finalOutput ->
                    // 1. ترويسة MPDF الثابتة والإصدار ومتجه التهيئة النهائي
                    finalOutput.write(MAGIC_HEADER)
                    finalOutput.write(byteArrayOf(CURRENT_VERSION))
                    finalOutput.write(finalIv)

                    // 2. تكرار على الأجزاء بالتسلسل الصارم من 0 إلى totalChunksCount - 1
                    for (chunkIdx in 0 until totalChunksCount) {
                        val chunkFile = File(chunksDir, "chunk_${chunkIdx}.enc")
                        if (!chunkFile.exists() || !chunkFile.isFile) {
                            throw IOException("الجزء رقم $chunkIdx غير موجود لاكتمال التجميع")
                        }

                        val headerRes = readChunkHeader(chunkFile)
                        if (headerRes.isFailure) {
                            throw headerRes.exceptionOrNull() ?: IOException("فشل قراءة ترويسة الجزء رقم $chunkIdx")
                        }
                        val header = headerRes.getOrThrow()
                        if (header.chunkIndex != chunkIdx) {
                            throw SecurityException("ترتيب الجزء غير مطابق: وُجد ${header.chunkIndex} والمتوقع $chunkIdx")
                        }

                        // التحقق الصارم من طول الجزء: الأجزاء غير الأخيرة 2MB، والأخير هو المتبقي المحسوب
                        if (chunkIdx < totalChunksCount - 1) {
                            if (header.plainLength != DEFAULT_CHUNK_SIZE_BYTES) {
                                throw IllegalArgumentException(
                                    "طول الجزء رقم $chunkIdx (${header.plainLength} بايت) لا يطابق الحجم القياسي المحدد للأجزاء ($DEFAULT_CHUNK_SIZE_BYTES بايت)"
                                )
                            }
                        } else {
                            val expectedLastChunkLength = expectedTotalBytes - (totalChunksCount - 1) * DEFAULT_CHUNK_SIZE_BYTES
                            if (header.plainLength != expectedLastChunkLength) {
                                throw IllegalArgumentException(
                                    "طول الجزء الأخير رقم $chunkIdx (${header.plainLength} بايت) لا يطابق الحجم المتبقي المحسوب ($expectedLastChunkLength بايت)"
                                )
                            }
                        }

                        // تهيئة Cipher فك تشفير هذا الجزء المستقل
                        val chunkCipher = Cipher.getInstance(TRANSFORMATION)
                        chunkCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, header.iv))

                        FileInputStream(chunkFile).use { chunkInput ->
                            // تخطي ترويسة الجزء (29 بايت)
                            var skipped = 0L
                            while (skipped < CHUNK_HEADER_TOTAL_LENGTH) {
                                val s = chunkInput.skip(CHUNK_HEADER_TOTAL_LENGTH - skipped)
                                if (s <= 0) {
                                    if (chunkInput.read() == -1) break
                                    skipped++
                                } else {
                                    skipped += s
                                }
                            }
                            if (skipped < CHUNK_HEADER_TOTAL_LENGTH) {
                                throw IOException("تعذر تخطي ترويسة الجزء كاملة أثناء التجميع")
                            }

                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (chunkInput.read(buffer).also { bytesRead = it } != -1) {
                                val plainBlock = chunkCipher.update(buffer, 0, bytesRead)
                                if (plainBlock != null && plainBlock.isNotEmpty()) {
                                    // التحقق من توقيع PDF (%PDF-) في بداية الجزء 0 حصراً
                                    if (chunkIdx == 0 && !hasVerifiedPdfSignature) {
                                        if (plainBlock.size < PDF_SIGNATURE.size) {
                                            throw IllegalArgumentException("الملف المصدري قصير جداً ولا يحتوي على توقيع PDF")
                                        }
                                        for (i in PDF_SIGNATURE.indices) {
                                            if (plainBlock[i] != PDF_SIGNATURE[i]) {
                                                throw IllegalArgumentException("الملف المصدري ليس ملف PDF صالحاً (توقيع %PDF- غير موجود)")
                                            }
                                        }
                                        hasVerifiedPdfSignature = true
                                    }

                                    val encOut = finalCipher.update(plainBlock)
                                    if (encOut != null && encOut.isNotEmpty()) {
                                        finalOutput.write(encOut)
                                    }
                                }
                            }

                            // التحقق الحرج: مصادقة GCM Tag للجزء
                            // إذا فشل هذا الاستدعاء، يُرمى استثناء AEADBadTagException
                            // فيتم القفز إلى catch وحذف tempFinalFile فوراً قبل اعتماده
                            val finalChunkPlain = chunkCipher.doFinal()
                            if (finalChunkPlain != null && finalChunkPlain.isNotEmpty()) {
                                if (chunkIdx == 0 && !hasVerifiedPdfSignature) {
                                    if (finalChunkPlain.size < PDF_SIGNATURE.size) {
                                        throw IllegalArgumentException("الملف المصدري قصير جداً")
                                    }
                                    for (i in PDF_SIGNATURE.indices) {
                                        if (finalChunkPlain[i] != PDF_SIGNATURE[i]) {
                                            throw IllegalArgumentException("الملف ليس PDF صالحاً")
                                        }
                                    }
                                    hasVerifiedPdfSignature = true
                                }
                                val encOut = finalCipher.update(finalChunkPlain)
                                if (encOut != null && encOut.isNotEmpty()) {
                                    finalOutput.write(encOut)
                                }
                            }

                            totalBytesProcessed += header.plainLength
                            onProgress?.invoke(totalBytesProcessed, expectedTotalBytes)
                        }
                    }

                    if (totalBytesProcessed != expectedTotalBytes) {
                        throw IOException("إجمالي البايتات المفكوكة والمجمعة ($totalBytesProcessed) لا يطابق الحجم المتوقع ($expectedTotalBytes)")
                    }

                    // 3. إنهاء التشفير النهائي وتثبيت GCM Authentication Tag لكامل الملف
                    val finalBlock = finalCipher.doFinal()
                    if (finalBlock != null && finalBlock.isNotEmpty()) {
                        finalOutput.write(finalBlock)
                    }

                    finalOutput.flush()
                }
                try {
                    fos.fd?.sync()
                } catch (_: Exception) {}
            }

            // 4. استبدال ذري آمن للملف النهائي
            atomicMoveFile(tempFinalFile, destinationEncryptedFile)

            // 5. حذف مجلد الأجزاء المؤقتة بالكامل بعد نجاح التجميع والاستبدال الذري فقط
            deleteChunksDirectory(chunksDir, userSecureDir)

            return Result.success(destinationEncryptedFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to assemble chunks into final MPDF", e)
            if (tempFinalFile.exists()) {
                tempFinalFile.delete()
            }
            val friendly = when (e) {
                is AEADBadTagException, is BadPaddingException ->
                    SecurityException("فشل التحقق من مصادقة سلامة أحد الأجزاء المشفرة أثناء التجميع", e)
                else -> e
            }
            return Result.failure(friendly)
        }
    }

    /**
     * حذف مجلد أجزاء ملف PDF المشفرة وبيانات الاستكمال بشكل آمن مع منع Path Traversal
     */
    fun deleteChunksDirectory(chunksDir: File, userSecureDir: File): Boolean {
        return try {
            if (!isPathStrictlyInside(chunksDir, userSecureDir, allowBaseDirItself = false)) {
                Log.w(TAG, "Attempted to delete chunks dir outside allowed base: ${chunksDir.path}")
                return false
            }
            deleteRecursivelyStrict(chunksDir, allowedBaseDir = userSecureDir, deleteTargetDirItself = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chunks directory: ${chunksDir.path}", e)
            false
        }
    }
}
