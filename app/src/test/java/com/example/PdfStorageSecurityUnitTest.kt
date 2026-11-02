package com.example

import com.example.data.PdfSecurityStorageService
import com.example.data.SupabaseStorageService
import com.example.model.SupabaseConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [PdfStorageSecurityUnitTest.TestOsShadow::class])
class PdfStorageSecurityUnitTest {

    @Implements(android.system.Os::class)
    class TestOsShadow {
        companion object {
            @Volatile
            var renameCount = 0

            fun reset() {
                renameCount = 0
            }

            @Implementation
            @JvmStatic
            fun rename(oldPath: String, newPath: String) {
                renameCount++
                val src = File(oldPath)
                val dst = File(newPath)
                java.nio.file.Files.move(
                    src.toPath(),
                    dst.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testCacheDir: File
    private lateinit var testStorageDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setUp() {
        TestOsShadow.reset()
        testCacheDir = tempFolder.newFolder("cache")
        testStorageDir = tempFolder.newFolder("secure_storage")

        // تجهيز مفتاح AES-256 لاختبار خوارزميات التشفير وفك التشفير والتحقق من السلامة
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testSecretKey = keyGen.generateKey()
        PdfSecurityStorageService.testKeyProvider = { testSecretKey }
    }

    @After
    fun tearDown() {
        PdfSecurityStorageService.testKeyProvider = null
        PdfSecurityStorageService.simulateAtomicMoveFailure = false
        PdfSecurityStorageService.simulateFilesMoveFailure = false
        SupabaseStorageService.testClient = null
        TestOsShadow.reset()
    }

    /**
     * إنشاء ملف PDF تجريبي يحتوي على التوقيع الثنائي الإلزامي (%PDF-) ومحتوى نصي اختباري
     */
    private fun createValidDummyPdf(name: String, content: String = "Test PDF Document Payload"): File {
        val file = File(testStorageDir, name)
        file.outputStream().use { out ->
            out.write(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)) // %PDF-
            out.write("1.7\n".toByteArray())
            out.write(content.toByteArray(Charsets.UTF_8))
            out.write("\n%%EOF".toByteArray())
        }
        return file
    }

    @Test
    fun testSuccessfulEncryptionAndDecryptionMatchesOriginal() {
        val originalPdf = createValidDummyPdf("sample_lesson.pdf", "Hello Midad Educational Platform Secure PDF Content")
        val encryptedFile = File(testStorageDir, "sample_lesson.pdf.enc")

        // 1. تشفير الملف
        val encResult = PdfSecurityStorageService.encryptFile(originalPdf, encryptedFile)
        assertTrue("يجب أن تنجح عملية التشفير", encResult.isSuccess)
        assertTrue("الملف المشفر يجب أن يكون موجوداً على القرص", encryptedFile.exists())
        assertTrue("الملف المشفر يجب أن يتجاوز الحد الأدنى للترويسة (33 بايت)", encryptedFile.length() >= PdfSecurityStorageService.MIN_ENCRYPTED_FILE_SIZE)

        // التحقق من ترويسة الملف المشفر
        val headerBytes = ByteArray(PdfSecurityStorageService.HEADER_TOTAL_LENGTH)
        encryptedFile.inputStream().use { it.read(headerBytes) }
        val magic = headerBytes.copyOfRange(0, 4)
        val version = headerBytes[4]
        assertArrayEquals("الترويسة يجب أن تطابق Magic Bytes 'MPDF'", PdfSecurityStorageService.MAGIC_HEADER, magic)
        assertEquals("رقم الإصدار يجب أن يكون 1", PdfSecurityStorageService.CURRENT_VERSION, version)

        // 2. فك التشفير إلى ملف مؤقت
        val decResult = PdfSecurityStorageService.decryptToTempFile(encryptedFile, testCacheDir)
        assertTrue("يجب أن تنجح عملية فك التشفير", decResult.isSuccess)

        val decryptedFile = decResult.getOrThrow()
        assertTrue("الملف المفكوك المؤقت يجب أن يكون موجوداً", decryptedFile.exists())

        // 3. مطابقة المحتوى الأصلي تماماً بايت ببايت
        val originalBytes = originalPdf.readBytes()
        val decryptedBytes = decryptedFile.readBytes()
        assertArrayEquals("محتوى الملف المفكوك يجب أن يطابق الملف الأصلي تماماً", originalBytes, decryptedBytes)

        // تنظيف الملف المؤقت
        decryptedFile.delete()
    }

    @Test
    fun testAtomicReplacementOfExistingEncryptedFile() {
        val v1Pdf = createValidDummyPdf("doc_v1.pdf", "Version 1 Content")
        val v2Pdf = createValidDummyPdf("doc_v2.pdf", "Version 2 Completely New Content")
        val targetEncFile = File(testStorageDir, "target_lesson.pdf.enc")

        // تشفير الإصدار الأول
        val enc1 = PdfSecurityStorageService.encryptFile(v1Pdf, targetEncFile)
        assertTrue("تشفير الإصدار الأول يجب أن ينجح", enc1.isSuccess)
        val dec1 = PdfSecurityStorageService.decryptToTempFile(targetEncFile, testCacheDir).getOrThrow()
        assertEquals("محتوى الإصدار الأول مطابق", "Version 1 Content", String(dec1.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
        dec1.delete()

        // استبدال ذري بالإصدار الثاني فوق نفس المسار القائم
        val enc2 = PdfSecurityStorageService.encryptFile(v2Pdf, targetEncFile)
        assertTrue("تشفير الإصدار الثاني واستبدال الملف القائم يجب أن ينجح", enc2.isSuccess)
        val dec2 = PdfSecurityStorageService.decryptToTempFile(targetEncFile, testCacheDir).getOrThrow()
        assertEquals("محتوى الإصدار المستبدل يجب أن يكون الإصدار الثاني", "Version 2 Completely New Content", String(dec2.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
        dec2.delete()
    }

    @Test
    fun testFailedEncryptionPreservesExistingEncryptedFile() {
        val originalPdf = createValidDummyPdf("original_good.pdf", "Good Existing Document Data")
        val targetEncFile = File(testStorageDir, "protected_target.pdf.enc")

        // 1. تشفير ملف أولي سليم
        val initialEnc = PdfSecurityStorageService.encryptFile(originalPdf, targetEncFile)
        assertTrue(initialEnc.isSuccess)
        val originalLength = targetEncFile.length()
        val originalBytes = targetEncFile.readBytes()

        // 2. محاولة استبداله بملف غير صالح (ملف نصي عادي بدون توقيع PDF)
        val invalidPdf = File(testStorageDir, "corrupt.pdf").apply { writeText("I am not a PDF file") }
        val failedEnc = PdfSecurityStorageService.encryptFile(invalidPdf, targetEncFile)
        assertTrue("يجب أن تفشل عملية التشفير لملف غير صالح", failedEnc.isFailure)

        // 3. التحقق الحاسم: الملف المشفر الأصلي القديم لم يُحذف ولم يتلف وما زال سليماً 100%
        assertTrue("الملف المشفر القديم يجب أن يظل موجوداً على القرص", targetEncFile.exists())
        assertEquals("حجم الملف القديم لم يتغير", originalLength, targetEncFile.length())
        assertArrayEquals("بايتات الملف القديم متطابقة تماماً مع نسخته قبل المحاولة الفاشلة", originalBytes, targetEncFile.readBytes())

        // التأكد من إمكانية فك تشفير الملف القديم بنجاح تام
        val dec = PdfSecurityStorageService.decryptToTempFile(targetEncFile, testCacheDir)
        assertTrue("الملف القديم يجب أن يُفك تشفيره بنجاح", dec.isSuccess)
        dec.getOrThrow().delete()
    }

    @Test
    fun testTamperingWithCiphertextFailsWithSecurityException() {
        val originalPdf = createValidDummyPdf("tamper_test.pdf", "Original Unmodified Content")
        val encryptedFile = File(testStorageDir, "tamper_test.pdf.enc")

        val encResult = PdfSecurityStorageService.encryptFile(originalPdf, encryptedFile)
        assertTrue(encResult.isSuccess)

        // التلاعب ببايت واحد في منتصف البيانات المشفرة
        val offsetToCorrupt = encryptedFile.length() - 8 // التعديل في منطقة Auth Tag أو آخر البيانات
        RandomAccessFile(encryptedFile, "rw").use { raf ->
            raf.seek(offsetToCorrupt)
            val currentByte = raf.readByte()
            raf.seek(offsetToCorrupt)
            raf.writeByte(currentByte.toInt() xor 0xFF) // قلب البايت لكسر الـ GCM Auth Tag
        }

        // محاولة فك التشفير بعد التلاعب
        val decResult = PdfSecurityStorageService.decryptToTempFile(encryptedFile, testCacheDir)
        assertTrue("يجب أن تفشل عملية فك التشفير عند التلاعب بمحتوى الملف المشفر", decResult.isFailure)

        val exception = decResult.exceptionOrNull()
        assertTrue(
            "نوع الاستثناء يجب أن يكون SecurityException أو ناتجاً عنه",
            exception is SecurityException || exception?.cause is SecurityException
        )

        // التأكد من عدم ترك أي ملف مؤقت مفكوك في مجلد العرض بعد فشل التحقق
        val viewerTempDir = File(testCacheDir, "midad_viewer_temp")
        val remainingFiles = viewerTempDir.listFiles() ?: emptyArray()
        assertEquals("يجب تنظيف وحذف الملف المؤقت فوراً عند فشل التحقق من Tag", 0, remainingFiles.size)
    }

    @Test
    fun testCorruptedOrInvalidHeaderRejected() {
        val originalPdf = createValidDummyPdf("corrupt_header.pdf")
        val encryptedFile = File(testStorageDir, "corrupt_header.pdf.enc")
        PdfSecurityStorageService.encryptFile(originalPdf, encryptedFile)

        // إتلاف الترويسة Magic Bytes
        RandomAccessFile(encryptedFile, "rw").use { raf ->
            raf.seek(0)
            raf.write("FAKE".toByteArray())
        }

        val decResult = PdfSecurityStorageService.decryptToTempFile(encryptedFile, testCacheDir)
        assertTrue("يجب رفض الملف الذي لا يحتوي على ترويسة MPDF", decResult.isFailure)
        assertTrue(decResult.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testUnsupportedVersionRejected() {
        val originalPdf = createValidDummyPdf("future_version.pdf")
        val encryptedFile = File(testStorageDir, "future_version.pdf.enc")
        PdfSecurityStorageService.encryptFile(originalPdf, encryptedFile)

        // تعديل بايت الإصدار إلى إصدار مستقبلي غير مدعوم (مثلاً 99)
        RandomAccessFile(encryptedFile, "rw").use { raf ->
            raf.seek(4) // موقع بايت الإصدار
            raf.writeByte(99)
        }

        val decResult = PdfSecurityStorageService.decryptToTempFile(encryptedFile, testCacheDir)
        assertTrue("يجب رفض الملف ذي الإصدار غير المدعوم", decResult.isFailure)
        assertTrue(decResult.exceptionOrNull()?.message?.contains("غير مدعوم") == true)
    }

    @Test
    fun testTruncatedEncryptedFileRejected() {
        val truncatedFile = File(testStorageDir, "truncated.enc")
        truncatedFile.writeBytes(byteArrayOf(0x4D, 0x50, 0x44, 0x46, 0x01, 0x02, 0x03)) // 7 بايتات فقط

        val decResult = PdfSecurityStorageService.decryptToTempFile(truncatedFile, testCacheDir)
        assertTrue("يجب رفض الملف المبتور فوراً قبل محاولة فك التشفير", decResult.isFailure)
        assertTrue(decResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testEmptySourceFileRejected() {
        val emptyFile = File(testStorageDir, "empty.pdf")
        emptyFile.createNewFile()
        val encFile = File(testStorageDir, "empty.pdf.enc")

        val encResult = PdfSecurityStorageService.encryptFile(emptyFile, encFile)
        assertTrue("يجب رفض تشفير الملفات الفارغة (0 بايت)", encResult.isFailure)
        assertFalse("يجب عدم إنشاء ملف مشفر لملف فارغ", encFile.exists())
    }

    @Test
    fun testNonPdfSourceFileRejected() {
        val txtFile = File(testStorageDir, "text.pdf")
        txtFile.writeText("This is plain text without PDF signature")
        val encFile = File(testStorageDir, "text.pdf.enc")

        val encResult = PdfSecurityStorageService.encryptFile(txtFile, encFile)
        assertTrue("يجب رفض تشفير أي ملف لا يبدأ بتوقيع %PDF-", encResult.isFailure)
        assertFalse(encFile.exists())
    }

    @Test
    fun testPathPrefixInjectionRejectedForSiblingDirectory() {
        // إنشاء مجلد مسموح به
        val safeBaseDir = tempFolder.newFolder("safe_dir")
        // إنشاء مجلد شقيق بنفس البادئة النصية (Prefix Matching Attack)
        val siblingEvilDir = File(safeBaseDir.parentFile, "safe_dir_evil").apply { mkdirs() }
        val victimFile = File(siblingEvilDir, "victim.txt").apply { writeText("critical data") }

        assertTrue("ملف الضحية موجود في المجلد الشقيق", victimFile.exists())

        // محاولة حذف الملف في المجلد الشقيق بادعاء أنه داخل safeBaseDir
        val deleteAttempt = PdfSecurityStorageService.deleteSecureFile(victimFile, safeBaseDir)
        assertFalse("يجب رفض حذف ملف في مجلد شقيق يحمل نفس بادئة الاسم", deleteAttempt)
        assertTrue("الملف في المجلد الشقيق يجب ألا يُحذف إطلاقاً", victimFile.exists())
    }

    @Test
    fun testPreventDeletingBaseDirectoryItself() {
        val allowedBaseDir = tempFolder.newFolder("protected_base")
        assertTrue(allowedBaseDir.exists())

        // محاولة تمرير المجلد الأساسي نفسه كملف مطلوب حذفه
        val deleteAttempt = PdfSecurityStorageService.deleteSecureFile(allowedBaseDir, allowedBaseDir)
        assertFalse("يجب منع دالة deleteSecureFile من حذف المجلد الأساسي نفسه", deleteAttempt)
        assertTrue("المجلد الأساسي يجب أن يبقى موجوداً", allowedBaseDir.exists())
    }

    @Test
    fun testRecursivePurgeWithSubdirectories() {
        val viewerDir = File(testCacheDir, "midad_viewer_temp").apply { mkdirs() }
        val subDir = File(viewerDir, "nested_folder").apply { mkdirs() }
        val file1 = File(viewerDir, "temp1.pdf").apply { writeText("payload1") }
        val file2 = File(subDir, "temp2.pdf").apply { writeText("payload2") }

        assertTrue(file1.exists())
        assertTrue(file2.exists())

        // فحص التطهير التعاودي
        val purgeResult = PdfSecurityStorageService.purgeViewerCacheDirectory(testCacheDir)
        assertTrue("يجب أن ينجح التطهير التعاودي لمجلد العرض المؤقت", purgeResult)
        assertFalse("الملف في المجلد الرئيسي يجب أن يُحذف", file1.exists())
        assertFalse("الملف في المجلد الفرعي يجب أن يُحذف", file2.exists())
        assertFalse("المجلد الفرعي نفسه يجب أن يُحذف", subDir.exists())
        assertFalse("مجلد العرض المؤقت نفسه يجب أن يُحذف", viewerDir.exists())
    }

    /**
     * اختبار التحقق من سلوك التراجع عند فشل النقل الذري:
     * - عند حدوث فشل في النقل الذري (تجاوز مساري Files.move(ATOMIC_MOVE) و Os.rename معاً عبر محاكاة الفشل):
     *   1. يتم الإعلان عن الفشل عبر Result.failure الحاوي على IOException.
     *   2. يبقى الملف النهائي القديم موجوداً دون حذف، ومطابقاً بايت ببايت للنسخة الأصلية.
     *   3. يُحذف الملف المؤقت (.tmp_*) بالكامل ويُنظف المجلد.
     *
     * ملاحظة معمارية هامة (حدود اختبار JVM):
     * هذا الاختبار يتحقق من منطق المعالجة والتراجع وسلامة الملفات في كود التطبيق في بيئة JVM / Robolectric،
     * ولا يدّعي اختبار سلوك استدعاء النواة الفعلي لـ Android (rename(2)) أو ضمانات نظام الملفات على الأجهزة الحقيقية.
     */
    @Test
    fun testAtomicMoveFailurePreservesOldDestinationAndCleansTempFile() {
        val originalPdf = createValidDummyPdf("doc_v1.pdf", "Original Preserved Content")
        val newPdf = createValidDummyPdf("doc_v2.pdf", "Attempted New Content That Fails At Move")
        val targetEncFile = File(testStorageDir, "preserve_target.pdf.enc")

        // 1. تشفير الملف الأولي وحفظه كنسخة قائمة
        val initialEnc = PdfSecurityStorageService.encryptFile(originalPdf, targetEncFile)
        assertTrue("تشفير الملف الأولي يجب أن ينجح", initialEnc.isSuccess)
        val initialBytes = targetEncFile.readBytes()
        val initialLength = targetEncFile.length()

        // 2. تفعيل محاكاة فشل النقل الذري (تتجاوز مساري Files.move الذري و Os.rename معاً)
        PdfSecurityStorageService.simulateAtomicMoveFailure = true

        try {
            // محاولة استبدال الملف القائم بنسخة جديدة تفشل أثناء النقل الذري
            val replaceResult = PdfSecurityStorageService.encryptFile(newPdf, targetEncFile)
            assertTrue("يجب أن تعلن الدالة الفشل عند إخفاق النقل الذري", replaceResult.isFailure)
            assertTrue("الاستثناء الناتج يجب أن يكون من نوع IOException", replaceResult.exceptionOrNull() is java.io.IOException)

            // 3. التحقق الحاسم: النسخة القديمة لم تُحذف ولم تتغير بايتاتها إطلاقاً
            assertTrue("الملف القديم يجب أن يبقى موجوداً", targetEncFile.exists())
            assertEquals("حجم الملف القديم لم يتغير", initialLength, targetEncFile.length())
            assertArrayEquals("بايتات الملف القديم متطابقة تماماً مع ما قبل المحاولة الفاشلة", initialBytes, targetEncFile.readBytes())

            // التأكد من أن فك تشفير النسخة القديمة يُعيد المحتوى الأصلي
            val dec = PdfSecurityStorageService.decryptToTempFile(targetEncFile, testCacheDir).getOrThrow()
            assertEquals("محتوى الملف المفكوك ما زال هو النسخة الأولى", "Original Preserved Content", String(dec.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
            dec.delete()

            // 4. التأكد من تنظيف الملف المؤقت .tmp_* من المجلد
            val leftoverTemps = testStorageDir.listFiles { _, name -> name.contains(".tmp_") } ?: emptyArray()
            assertEquals("يجب تنظيف وحذف أي ملف مؤقت عند فشل النقل الذري", 0, leftoverTemps.size)
        } finally {
            PdfSecurityStorageService.simulateAtomicMoveFailure = false
        }
    }

    /**
     * اختبار مسار Android API 24:
     * التحقق من أن النقل يتم مباشرة عبر Os.rename دون استدعاء أو تحميل java.nio.file.Files.
     */
    @Test
    @Config(sdk = [24])
    fun testApi24UsesOsRenameDirectly() {
        TestOsShadow.reset()
        val pdf = createValidDummyPdf("api24_lesson.pdf", "Payload for Android API 24 Nougat")
        val targetEnc = File(testStorageDir, "api24_lesson.pdf.enc")

        val result = PdfSecurityStorageService.encryptFile(pdf, targetEnc)
        assertTrue("يجب أن ينجح التشفير على Android API 24", result.isSuccess)
        assertTrue("الملف المشفر يجب أن يكون موجوداً", targetEnc.exists())
        assertTrue("يجب أن يتم استدعاء Os.rename مباشرة على API 24", TestOsShadow.renameCount > 0)

        val dec = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir).getOrThrow()
        assertEquals("المحتوى المفكوك يطابق الأصلي", "Payload for Android API 24 Nougat", String(dec.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
        dec.delete()
    }

    /**
     * اختبار مسار Android API 25:
     * التحقق من أن النقل يتم مباشرة عبر Os.rename على Android Nougat 7.1.
     */
    @Test
    @Config(sdk = [25])
    fun testApi25UsesOsRenameDirectly() {
        TestOsShadow.reset()
        val pdf = createValidDummyPdf("api25_lesson.pdf", "Payload for Android API 25 Nougat MR1")
        val targetEnc = File(testStorageDir, "api25_lesson.pdf.enc")

        val result = PdfSecurityStorageService.encryptFile(pdf, targetEnc)
        assertTrue("يجب أن ينجح التشفير على Android API 25", result.isSuccess)
        assertTrue("الملف المشفر يجب أن يكون موجوداً", targetEnc.exists())
        assertTrue("يجب أن يتم استدعاء Os.rename مباشرة على API 25", TestOsShadow.renameCount > 0)

        val dec = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir).getOrThrow()
        assertEquals("المحتوى المفكوك يطابق الأصلي", "Payload for Android API 25 Nougat MR1", String(dec.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
        dec.delete()
    }

    /**
     * اختبار مسار Android API 26+:
     * التحقق من استخدام Files.move(ATOMIC_MOVE) أولاً وعدم الحاجة لاستدعاء Os.rename طالما نجح.
     */
    @Test
    @Config(sdk = [26])
    fun testApi26UsesFilesMoveDirectly() {
        TestOsShadow.reset()
        val pdf = createValidDummyPdf("api26_lesson.pdf", "Payload for Android API 26 Oreo")
        val targetEnc = File(testStorageDir, "api26_lesson.pdf.enc")

        val result = PdfSecurityStorageService.encryptFile(pdf, targetEnc)
        assertTrue("يجب أن ينجح التشفير على Android API 26", result.isSuccess)
        assertTrue("الملف المشفر يجب أن يكون موجوداً", targetEnc.exists())
        assertEquals("على API 26 يُستخدم Files.move ولا يُستدعى Os.rename عند النجاح", 0, TestOsShadow.renameCount)

        val dec = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir).getOrThrow()
        assertEquals("المحتوى المفكوك يطابق الأصلي", "Payload for Android API 26 Oreo", String(dec.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
        dec.delete()
    }

    /**
     * اختبار فشل Files.move على API 26+ والانتقال التلقائي الذري إلى Os.rename:
     * - يُحاكي تعذر Files.move، ويتحقق من نجاح العملية عبر Os.rename كمسار احتياطي.
     */
    @Test
    @Config(sdk = [36])
    fun testFilesMoveFallbackToOsRenameOnFailure() {
        TestOsShadow.reset()
        val pdf = createValidDummyPdf("fallback_lesson.pdf", "Fallback payload when Files.move fails")
        val targetEnc = File(testStorageDir, "fallback_lesson.pdf.enc")

        PdfSecurityStorageService.simulateFilesMoveFailure = true

        try {
            val result = PdfSecurityStorageService.encryptFile(pdf, targetEnc)
            assertTrue("يجب أن ينجح الاستبدال عبر Os.rename كمسار احتياطي عند فشل Files.move", result.isSuccess)
            assertTrue("الملف المشفر يجب أن يكون موجوداً", targetEnc.exists())
            assertTrue("يجب أن يتم استدعاء Os.rename كمسار احتياطي", TestOsShadow.renameCount > 0)

            val dec = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir).getOrThrow()
            assertEquals("المحتوى المفكوك يطابق الأصلي", "Fallback payload when Files.move fails", String(dec.readBytes()).substringAfter("1.7\n").substringBefore("\n%%EOF"))
            dec.delete()
        } finally {
            PdfSecurityStorageService.simulateFilesMoveFailure = false
        }
    }

    // =========================================================================
    // اختبارات المرحلة 2-B3-B2-2A: التشفير المباشر من الدفق وتنزيل Supabase
    // =========================================================================

    /**
     * 1. التحقق من نجاح التشفير المباشر لدفق InputStream صالح دون كتابة نسخة مكشوفة على القرص
     * وإمكانية فك تشفير الناتج بنجاح ومطابقته التامة مع المحتوى الأصلي.
     */
    @Test
    fun testDirectStreamEncryptionAndDecryptionMatchesOriginal() {
        val payload = "Hello Midad Streaming AES-256-GCM Secure Payload Content"
        val rawBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\n$payload\n%%EOF".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(rawBytes)
        val targetEnc = File(testStorageDir, "stream_lesson_1.pdf.enc")

        var progressReported = false
        val encResult = PdfSecurityStorageService.encryptStream(
            inputStream = stream,
            destinationEncryptedFile = targetEnc,
            totalBytesExpected = rawBytes.size.toLong(),
            onProgress = { read, _ -> if (read > 0) progressReported = true }
        )

        assertTrue("يجب أن ينجح التشفير المباشر من الدفق", encResult.isSuccess)
        assertTrue("الملف المشفر يجب أن يكون موجوداً على القرص", targetEnc.exists())
        assertTrue("يجب أن يكون حجم الملف المشفر أكبر من الحد الأدنى للترويسة", targetEnc.length() >= PdfSecurityStorageService.MIN_ENCRYPTED_FILE_SIZE)
        assertTrue("isEncryptedFilePresent يجب أن تؤكد وجود وصلاحية الملف", PdfSecurityStorageService.isEncryptedFilePresent(targetEnc))
        assertTrue("يجب أن يتم إشعار دالة التقدم أثناء التشفير", progressReported)

        // التحقق من فك التشفير ومطابقة المحتوى
        val decResult = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir)
        assertTrue("يجب أن ينجح فك تشفير الملف المشفر تدفقياً", decResult.isSuccess)
        val decryptedFile = decResult.getOrThrow()
        val decryptedText = String(decryptedFile.readBytes(), Charsets.UTF_8)
        assertTrue("المحتوى المفكوك يجب أن يبدأ بـ %PDF-", decryptedText.startsWith("%PDF-"))
        assertEquals("المحتوى المفكوك يطابق النص الأصلي بدقة", payload, decryptedText.substringAfter("1.7\n").substringBefore("\n%%EOF"))

        decryptedFile.delete()
    }

    /**
     * 2. التحقق من رفض دفق لا يبدأ بتوقيع %PDF- الصالح (0x25, 0x50, 0x44, 0x46, 0x2D)
     * وعدم إنشاء الملف المشفر النهائي وعدم إبقاء أي ملف مؤقت.
     */
    @Test
    fun testDirectStreamEncryptionRejectsNonPdf() {
        val invalidBytes = "NOT_A_VALID_PDF_HEADER_DATA_STREAM".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(invalidBytes)
        val targetEnc = File(testStorageDir, "invalid_stream.pdf.enc")

        val encResult = PdfSecurityStorageService.encryptStream(stream, targetEnc)

        assertFalse("يجب أن تفشل العملية إذا لم يبدأ الدفق بتوقيع %PDF-", encResult.isSuccess)
        assertTrue("نوع الاستثناء يجب أن يكون IllegalArgumentException", encResult.exceptionOrNull() is IllegalArgumentException)
        assertFalse("يجب عدم إنشاء الملف النهائي عند فشل التحقق من التوقيع", targetEnc.exists())

        // التأكد من عدم بقاء أي ملف مؤقت (.tmp_)
        val tempFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }.orEmpty()
        assertEquals("يجب تنظيف أي ملف مؤقت عند الفشل فوراً", 0, tempFiles.size)
    }

    /**
     * 3. التحقق من فرض سقف 50MB أثناء قراءة الدفق ورفض أي دفق يتجاوز 50 ميغابايت
     * حتى وإن لم يتم الإبلاغ عن الحجم في Content-Length.
     */
    @Test
    fun testDirectStreamEncryptionRejectsOversizedStream() {
        val targetEnc = File(testStorageDir, "oversized_stream.pdf.enc")
        val limit = 50L * 1024L * 1024L
        val overflowSize = limit + 1024L // 50MB + 1KB

        // دفق مخصص يولد توقيع %PDF- أولاً ثم يستمر في توليد بايتات تتجاوز 50MB
        val oversizedStream = object : InputStream() {
            private val header = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
            private var bytesRead = 0L

            override fun read(): Int {
                if (bytesRead >= overflowSize) return -1
                val b = if (bytesRead < header.size) header[bytesRead.toInt()].toInt() and 0xFF else 0x5A
                bytesRead++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= overflowSize) return -1
                var count = 0
                for (i in 0 until len) {
                    if (bytesRead >= overflowSize) break
                    b[off + i] = if (bytesRead < header.size) header[bytesRead.toInt()] else 0x5A
                    bytesRead++
                    count++
                }
                return if (count == 0) -1 else count
            }
        }

        val encResult = PdfSecurityStorageService.encryptStream(oversizedStream, targetEnc)

        assertFalse("يجب أن تفشل عملية التشفير عند تجاوز سقف 50MB", encResult.isSuccess)
        val msg = encResult.exceptionOrNull()?.message.orEmpty()
        assertTrue("رسالة الخطأ يجب أن توضح تجاوز الحد الأقصى (50 ميغابايت)", msg.contains("50") || msg.contains("الحد الأقصى"))
        assertFalse("يجب عدم إنشاء الملف النهائي عند تجاوز الحجم الأقصى", targetEnc.exists())

        val tempFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }.orEmpty()
        assertEquals("يجب تنظيف أي ملف مؤقت عند تجاوز الحجم الأقصى", 0, tempFiles.size)
    }

    /**
     * 4. التحقق من اكتشاف أي تلف في بيانات Ciphertext أو Authentication Tag
     * ورفض الملف بالكامل عند فك التشفير.
     */
    @Test
    fun testDirectStreamTamperedCiphertextFailsAuthenticationTag() {
        val rawBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\nIntegrity Verification Stream Test\n%%EOF".toByteArray()
        val targetEnc = File(testStorageDir, "tampered_stream.pdf.enc")

        val encResult = PdfSecurityStorageService.encryptStream(ByteArrayInputStream(rawBytes), targetEnc)
        assertTrue(encResult.isSuccess)

        // إتلاف بايت واحد في منتصف البيانات المشفرة
        val offsetToCorrupt = PdfSecurityStorageService.HEADER_TOTAL_LENGTH + 12 + 4 // بعد الترويسة و IV
        RandomAccessFile(targetEnc, "rw").use { raf ->
            raf.seek(offsetToCorrupt.toLong())
            val originalByte = raf.readByte()
            raf.seek(offsetToCorrupt.toLong())
            raf.writeByte(originalByte.toInt() xor 0xFF)
        }

        val decResult = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir)
        assertFalse("يجب أن يفشل فك التشفير وتفشل مصادقة GCM Tag عند إتلاف البيانات", decResult.isSuccess)
        assertTrue("الاستثناء يجب أن يكون أمنياً", decResult.exceptionOrNull() is SecurityException)
    }

    /**
     * 5. التحقق من حذف الملف المؤقت فقط عند حدوث خطأ غير متوقع أثناء قراءة الدفق
     * وعدم ترك أي ملف مكشوف أو مؤقت على القرص.
     */
    @Test
    fun testDirectStreamTempFileCleanedUpOnStreamError() {
        val targetEnc = File(testStorageDir, "error_stream.pdf.enc")

        // دفق يرمي خطأ شبكة بعد قراءة الترويسة
        val failingStream = object : InputStream() {
            private val header = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
            private var readCount = 0

            override fun read(): Int {
                if (readCount < header.size) {
                    val b = header[readCount].toInt() and 0xFF
                    readCount++
                    return b
                }
                throw IOException("محاكاة انقطاع مفاجئ في الاتصال الشبكي أثناء التنزيل")
            }
        }

        val encResult = PdfSecurityStorageService.encryptStream(failingStream, targetEnc)

        assertFalse("يجب أن تفشل العملية عند رمي استثناء من الدفق", encResult.isSuccess)
        assertFalse("الملف النهائي يجب ألا يكون موجوداً", targetEnc.exists())

        val tempFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }.orEmpty()
        assertEquals("يجب تنظيف الملف المؤقت عند انقطاع الدفق فوراً", 0, tempFiles.size)
    }

    /**
     * 6. التحقق من المحافظة على النسخة النهائية القديمة للملف عند فشل النقل الذري
     * وتنظيف الملف المؤقت الجديد.
     */
    @Test
    fun testDirectStreamAtomicMoveFailurePreservesOldDestination() {
        val targetEnc = File(testStorageDir, "preserve_old_stream.pdf.enc")
        val oldPayload = "Old Version Payload From Stream"
        val oldBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\n$oldPayload\n%%EOF".toByteArray()

        // 1. تشفير النسخة القديمة بنجاح
        val firstResult = PdfSecurityStorageService.encryptStream(ByteArrayInputStream(oldBytes), targetEnc)
        assertTrue(firstResult.isSuccess)
        assertTrue(targetEnc.exists())

        // 2. محاكاة فشل النقل الذري وتشفير نسخة جديدة
        val newPayload = "New Version Should Not Overwrite On Failure"
        val newBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\n$newPayload\n%%EOF".toByteArray()

        PdfSecurityStorageService.simulateAtomicMoveFailure = true
        try {
            val secondResult = PdfSecurityStorageService.encryptStream(ByteArrayInputStream(newBytes), targetEnc)
            assertFalse("يجب أن تفشل العملية عند فشل النقل الذري", secondResult.isSuccess)
            assertTrue("الملف النهائي القديم يجب أن يظل موجوداً وسليماً تماماً", targetEnc.exists())

            // التحقق من أن النسخة القديمة بقيت دون تغيير
            val dec = PdfSecurityStorageService.decryptToTempFile(targetEnc, testCacheDir).getOrThrow()
            val text = String(dec.readBytes(), Charsets.UTF_8)
            assertEquals("المحتوى القديم محفوظ بالكامل دون أي مساس به", oldPayload, text.substringAfter("1.7\n").substringBefore("\n%%EOF"))
            dec.delete()

            // التأكد من حذف الملف المؤقت الجديد
            val tempFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }.orEmpty()
            assertEquals("يجب تنظيف الملف المؤقت بعد فشل النقل الذري", 0, tempFiles.size)
        } finally {
            PdfSecurityStorageService.simulateAtomicMoveFailure = false
        }
    }

    /**
     * 7. اختبار تكاملي مع SupabaseStorageService عبر عميل اختبار بدون اتصال حقيقي:
     * - تنزيل موثق لملف PDF وتشفيره مباشرة عبر AES-256-GCM أثناء تدفق البيانات.
     * - التأكد التام من عدم كتابة أي ملف PDF غير مشفر (.pdf) على القرص أثناء العملية.
     */
    @Test
    fun testSupabaseAuthenticatedDownloadAndDirectEncryptionOfflineMock() {
        runBlocking {
            val testConfig = SupabaseConfig(
                projectUrl = "https://mock-test.supabase.co",
                anonKey = "mock_anon_key_midad",
                bucketName = "subject-files"
            )
            val validToken = "valid_test_jwt_bearer_token"
            val pdfPayload = "Midad Supabase Authenticated Stream Mock Payload"
            val rawPdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\n$pdfPayload\n%%EOF".toByteArray()

            // إعداد Interceptor يحاكي نقطة نهاية Supabase الموثقة
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val authHeader = req.header("Authorization")
                    val apikeyHeader = req.header("apikey")

                    if (authHeader == "Bearer $validToken" && apikeyHeader == "mock_anon_key_midad") {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/pdf")
                            .body(rawPdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("{\"message\":\"Invalid JWT\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                            .build()
                    }
                })
                .build()

            SupabaseStorageService.testClient = mockClient
            val targetEncryptedFile = File(testStorageDir, "supabase_direct_lesson.pdf.enc")

            var progressUpdated = false
            val result = SupabaseStorageService.downloadAndEncryptAuthenticatedPdf(
                filePath = "physics/unit1/lesson1.pdf",
                destinationEncryptedFile = targetEncryptedFile,
                accessToken = validToken,
                config = testConfig,
                onProgress = { read, _ -> if (read > 0) progressUpdated = true }
            )

            assertTrue("يجب أن ينجح التنزيل والتشفير المباشر الموثق", result.isSuccess)
            assertTrue("الملف المشفر النهائي يجب أن يكون موجوداً", targetEncryptedFile.exists())
            assertTrue("تم إشعار دالة التقدم أثناء التنزيل والتشفير", progressUpdated)

            // التحقق الأمني الحاسم: لا يوجد أي ملف .pdf مكشوف في مجلد التخزين أو الكاش
            val unencryptedStorageFiles = testStorageDir.listFiles { _, name -> name.endsWith(".pdf", ignoreCase = true) }.orEmpty()
            assertEquals("يجب ألا يوجد أي ملف PDF غير مشفر في مجلد التخزين النهائي", 0, unencryptedStorageFiles.size)

            // التحقق من إمكانية فك تشفير الملف بنجاح
            val decResult = PdfSecurityStorageService.decryptToTempFile(targetEncryptedFile, testCacheDir)
            assertTrue(decResult.isSuccess)
            val dec = decResult.getOrThrow()
            val text = String(dec.readBytes(), Charsets.UTF_8)
            assertEquals("المحتوى المفكوك يطابق ما تم إرساله من خادم Supabase", pdfPayload, text.substringAfter("1.7\n").substringBefore("\n%%EOF"))
            dec.delete()
        }
    }

    /**
     * 8. التحقق من معالجة استجابة HTTP 404 من Supabase وإرجاع فشل واضح
     * دون اعتبار 404 نجاحاً، ودون إنشاء أي ملف مشفر أو مؤقت.
     */
    @Test
    fun testSupabaseDownloadFailsOnHttp404AndCleansUp() {
        runBlocking {
            val testConfig = SupabaseConfig(
                projectUrl = "https://mock-test.supabase.co",
                anonKey = "mock_anon_key_midad",
                bucketName = "subject-files"
            )
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{\"message\":\"Object not found\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                })
                .build()

            SupabaseStorageService.testClient = mockClient
            val targetEncryptedFile = File(testStorageDir, "missing_lesson.pdf.enc")

            val result = SupabaseStorageService.downloadAndEncryptAuthenticatedPdf(
                filePath = "biology/missing.pdf",
                destinationEncryptedFile = targetEncryptedFile,
                accessToken = "any_token",
                config = testConfig
            )

            assertFalse("يجب أن يفشل التنزيل عند إرجاع 404 من الخادم", result.isSuccess)
            val msg = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("رسالة الخطأ يجب أن تشير إلى عدم وجود الملف", msg.contains("غير موجود") || msg.contains("404"))
            assertFalse("يجب عدم إنشاء أي ملف نهائي عند 404", targetEncryptedFile.exists())

            val tempFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }.orEmpty()
            assertEquals("يجب عدم بقاء أي ملف مؤقت عند 404", 0, tempFiles.size)
        }
    }

    /**
     * 9. التحقق من رفض رمز الوصول (accessToken) الفارغ استباقياً
     * قبل إجراء أي استدعاء شبكي.
     */
    @Test
    fun testSupabaseDownloadRejectsBlankAccessToken() {
        runBlocking {
            val testConfig = SupabaseConfig(
                projectUrl = "https://mock-test.supabase.co",
                anonKey = "mock_anon_key_midad",
                bucketName = "subject-files"
            )
            val targetEncryptedFile = File(testStorageDir, "blank_token_lesson.pdf.enc")

            val result = SupabaseStorageService.downloadAndEncryptAuthenticatedPdf(
                filePath = "chemistry/lesson1.pdf",
                destinationEncryptedFile = targetEncryptedFile,
                accessToken = "   ",
                config = testConfig
            )

            assertFalse("يجب رفض التنزيل عند تمرير رمز وصول فارغ", result.isSuccess)
            assertTrue("الاستثناء يجب أن يكون IllegalArgumentException", result.exceptionOrNull() is IllegalArgumentException)
            assertFalse("يجب عدم إنشاء أي ملف", targetEncryptedFile.exists())
        }
    }
}
