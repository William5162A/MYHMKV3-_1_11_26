package com.example

import com.example.data.PdfSecurityStorageService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfStorageSecurityUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testCacheDir: File
    private lateinit var testStorageDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setUp() {
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
}
