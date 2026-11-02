package com.example

import com.example.data.PdfSecurityStorageService
import com.example.data.ResumeMetadata
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [PdfChunkSecurityStorageUnitTest.TestOsShadow::class])
class PdfChunkSecurityStorageUnitTest {

    @Implements(android.system.Os::class)
    class TestOsShadow {
        companion object {
            @Implementation
            @JvmStatic
            fun rename(oldPath: String, newPath: String) {
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

    private lateinit var testStorageDir: File
    private lateinit var testCacheDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setUp() {
        testStorageDir = tempFolder.newFolder("secure_storage")
        testCacheDir = tempFolder.newFolder("cache")

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testSecretKey = keyGen.generateKey()
        PdfSecurityStorageService.testKeyProvider = { testSecretKey }
    }

    @After
    fun tearDown() {
        PdfSecurityStorageService.testKeyProvider = null
    }

    private fun generateDeterministicData(size: Int, prefix: ByteArray? = null): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        if (prefix != null && prefix.isNotEmpty()) {
            System.arraycopy(prefix, 0, data, 0, prefix.size)
            offset = prefix.size
        }
        for (i in offset until size) {
            data[i] = ((i * 31 + 17) and 0xFF).toByte()
        }
        return data
    }

    /**
     * 1. تشفير جزء وفك تشفيره واستعادة البايتات الأصلية بالكامل
     */
    @Test
    fun testEncryptAndDecryptChunkRestoresOriginalBytes() {
        val plainBytes = generateDeterministicData(500 * 1024) // 500 KB
        val chunkFile = File(testStorageDir, "chunk_0.enc")

        val encryptRes = PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(plainBytes),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = plainBytes.size.toLong()
        )
        assertTrue("يجب أن ينجح تشفير الجزء", encryptRes.isSuccess)
        assertTrue(chunkFile.exists())
        assertEquals(
            "حجم الملف المشفر يجب أن يطابق 29 بايت ترويسة + البيانات + 16 بايت Tag",
            PdfSecurityStorageService.CHUNK_HEADER_TOTAL_LENGTH + plainBytes.size + 16L,
            chunkFile.length()
        )

        val headerRes = PdfSecurityStorageService.readChunkHeader(chunkFile)
        assertTrue(headerRes.isSuccess)
        val header = headerRes.getOrThrow()
        assertEquals(0, header.chunkIndex)
        assertEquals(plainBytes.size.toLong(), header.plainLength)

        val decryptedOut = ByteArrayOutputStream()
        val decryptRes = PdfSecurityStorageService.decryptChunkStream(
            chunkFile = chunkFile,
            expectedChunkIndex = 0,
            outputStream = decryptedOut
        )
        assertTrue("يجب أن ينجح فك تشفير الجزء", decryptRes.isSuccess)
        assertEquals(plainBytes.size.toLong(), decryptRes.getOrThrow())
        assertArrayEquals("البايتات المفكوكة يجب أن تطابق الأصلية تماماً", plainBytes, decryptedOut.toByteArray())
    }

    /**
     * 2. رفض توقيع جزء غير صحيح (ليس MCHK)
     */
    @Test
    fun testRejectInvalidChunkMagicSignature() {
        val plainBytes = generateDeterministicData(10 * 1024)
        val chunkFile = File(testStorageDir, "chunk_bad_magic.enc")

        PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(plainBytes),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = plainBytes.size.toLong()
        ).getOrThrow()

        // تخريب أول 4 بايتات لتصبح مختلفة عن MCHK
        RandomAccessFile(chunkFile, "rw").use { raf ->
            raf.seek(0)
            raf.write("BAD!".toByteArray(Charsets.US_ASCII))
        }

        val headerRes = PdfSecurityStorageService.readChunkHeader(chunkFile)
        assertTrue("يجب أن يفشل فحص الترويسة لملف ذي توقيع خاطئ", headerRes.isFailure)
        assertTrue(headerRes.exceptionOrNull() is SecurityException)

        val out = ByteArrayOutputStream()
        val decryptRes = PdfSecurityStorageService.decryptChunkStream(chunkFile, 0, out)
        assertTrue("يجب أن يفشل فك التشفير لملف ذي توقيع غير صالح", decryptRes.isFailure)
    }

    /**
     * 3. رفض رقم جزء غير متوقع (Index Mismatch)
     */
    @Test
    fun testRejectUnexpectedChunkIndex() {
        val plainBytes = generateDeterministicData(10 * 1024)
        val chunkFile = File(testStorageDir, "chunk_index_0.enc")

        PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(plainBytes),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = plainBytes.size.toLong()
        ).getOrThrow()

        // محاولة فحص وفك التشفير مع توقع chunkIndex = 1
        val verifyRes = PdfSecurityStorageService.verifyChunkFile(chunkFile, expectedChunkIndex = 1)
        assertTrue("يجب أن يفشل التحقق عند اختلاف رقم الجزء", verifyRes.isFailure)
        assertTrue(verifyRes.exceptionOrNull() is SecurityException)

        val out = ByteArrayOutputStream()
        val decryptRes = PdfSecurityStorageService.decryptChunkStream(chunkFile, expectedChunkIndex = 1, outputStream = out)
        assertTrue("يجب أن يفشل فك التشفير عند عدم مطابقة رقم الجزء", decryptRes.isFailure)
    }

    /**
     * 4. اكتشاف تعديل البيانات المشفرة عبر مصادقة GCM Tag
     */
    @Test
    fun testDetectTamperedCiphertextViaGcmTag() {
        val plainBytes = generateDeterministicData(32 * 1024)
        val chunkFile = File(testStorageDir, "chunk_tampered.enc")

        PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(plainBytes),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = plainBytes.size.toLong()
        ).getOrThrow()

        // تعديل بايت واحد في منطقة البيانات المشفرة (بعد الترويسة 29 بايت)
        RandomAccessFile(chunkFile, "rw").use { raf ->
            raf.seek(35)
            val originalByte = raf.readByte()
            raf.seek(35)
            raf.writeByte((originalByte.toInt() xor 0xFF))
        }

        val out = ByteArrayOutputStream()
        val decryptRes = PdfSecurityStorageService.decryptChunkStream(chunkFile, 0, out)
        assertTrue("يجب أن يفشل فك التشفير ويكشف التلاعب برمز المصادقة GCM", decryptRes.isFailure)
        assertTrue(
            "الاستثناء يجب أن يكون SecurityException",
            decryptRes.exceptionOrNull() is SecurityException
        )
    }

    /**
     * 5. رفض بيانات وصفية غير صحيحة أو مسار غير آمن (Path Traversal وقيود الحجم)
     */
    @Test
    fun testRejectInvalidMetadataOrUnsafePath() {
        val validPdfId = UUID.randomUUID().toString()
        val chunksDirRes = PdfSecurityStorageService.getChunksDirectory(testStorageDir, validPdfId)
        assertTrue(chunksDirRes.isSuccess)
        val chunksDir = chunksDirRes.getOrThrow()
        chunksDir.mkdirs()

        // مسار يحتوي على Path Traversal
        val badPathMeta = ResumeMetadata(
            pdfId = validPdfId,
            remoteFilePath = "../../../etc/passwd",
            expectedTotalBytes = 1000L,
            totalChunksCount = 1
        )
        val saveBadPath = PdfSecurityStorageService.saveResumeMetadata(chunksDir, badPathMeta)
        assertTrue("يجب رفض حفظ مسار يحتوي على Path Traversal", saveBadPath.isFailure)

        // حجم ملف غير صالح (أكبر من 50MB)
        val hugeMeta = ResumeMetadata(
            pdfId = validPdfId,
            remoteFilePath = "lessons/math.pdf",
            expectedTotalBytes = 55L * 1024L * 1024L,
            totalChunksCount = 28
        )
        val saveHuge = PdfSecurityStorageService.saveResumeMetadata(chunksDir, hugeMeta)
        assertTrue("يجب رفض حفظ بيانات تتجاوز 50MB", saveHuge.isFailure)

        // عدد أجزاء غير متطابق حسابياً
        val badCountMeta = ResumeMetadata(
            pdfId = validPdfId,
            remoteFilePath = "lessons/math.pdf",
            expectedTotalBytes = 2L * 1024L * 1024L,
            totalChunksCount = 5 // المفترض 1
        )
        val saveBadCount = PdfSecurityStorageService.saveResumeMetadata(chunksDir, badCountMeta)
        assertTrue("يجب رفض حفظ عدد أجزاء غير متطابق حسابياً", saveBadCount.isFailure)

        // معرف ملف ليس UUID
        val badUuidRes = PdfSecurityStorageService.getChunksDirectory(testStorageDir, "not_a_uuid;rm -rf")
        assertTrue("يجب رفض معرف ليس UUID", badUuidRes.isFailure)
    }

    /**
     * 6. اكتشاف فقدان جزء مسجل وحذفه من الأجزاء المكتملة في الميتا داتا المنقحة
     */
    @Test
    fun testDetectMissingOrCorruptRecordedChunk() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        val chunk0 = File(chunksDir, "chunk_0.enc")
        val chunk1 = File(chunksDir, "chunk_1.enc")

        val data0 = generateDeterministicData(100 * 1024)
        val data1 = generateDeterministicData(100 * 1024)

        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(data0), chunk0, 0, data0.size.toLong()).getOrThrow()
        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(data1), chunk1, 1, data1.size.toLong()).getOrThrow()

        // حفظ ميتا داتا تشير إلى أن الأجزاء 0 و 1 و 2 مكتملة (بينما 2 غير موجود على القرص)
        val meta = ResumeMetadata(
            pdfId = pdfId,
            remoteFilePath = "lessons/test.pdf",
            expectedTotalBytes = 5000000L, // ~5MB (3 أجزاء)
            chunkSizeBytes = 2097152L,
            totalChunksCount = 3,
            completedChunks = listOf(0, 1, 2)
        )
        PdfSecurityStorageService.saveResumeMetadata(chunksDir, meta).getOrThrow()

        // قراءة الميتا داتا: يجب أن تكتشف غياب chunk_2.enc وتنقي القائمة لتصبح [0, 1] فقط
        val loadRes = PdfSecurityStorageService.loadAndValidateResumeMetadata(chunksDir, pdfId)
        assertTrue("يجب أن تنجح قراءة وتنقيح الميتا داتا", loadRes.isSuccess)
        val sanitized = loadRes.getOrThrow()
        assertEquals(listOf(0, 1), sanitized.completedChunks)

        // التحقق من أن ملف الميتا داتا على القرص أعيدت كتابته ذرياً بالقائمة المنقحة
        val reloadRes = PdfSecurityStorageService.loadAndValidateResumeMetadata(chunksDir, pdfId)
        assertEquals(listOf(0, 1), reloadRes.getOrThrow().completedChunks)
    }

    /**
     * 7. تجميع عدة أجزاء والتحقق من تطابق الملف الناتج مع الأصل بعد فك تشفيره
     */
    @Test
    fun testAssembleMultipleChunksAndVerifyWithDecryptToTempFile() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        // إنشاء محتوى PDF كامل من 3 أجزاء بحجم إجمالي 4.5MB
        val pdfSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
        val totalBytes = 4L * 1024L * 1024L + 500L * 1024L // 4.5 MB
        val fullData = generateDeterministicData(totalBytes.toInt(), prefix = pdfSignature)

        val chunkSize = 2L * 1024L * 1024L // 2 MB
        val chunk0Data = fullData.copyOfRange(0, chunkSize.toInt())
        val chunk1Data = fullData.copyOfRange(chunkSize.toInt(), (chunkSize * 2).toInt())
        val chunk2Data = fullData.copyOfRange((chunkSize * 2).toInt(), fullData.size)

        val chunk0File = File(chunksDir, "chunk_0.enc")
        val chunk1File = File(chunksDir, "chunk_1.enc")
        val chunk2File = File(chunksDir, "chunk_2.enc")

        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk0Data), chunk0File, 0, chunk0Data.size.toLong()).getOrThrow()
        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk1Data), chunk1File, 1, chunk1Data.size.toLong()).getOrThrow()
        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk2Data), chunk2File, 2, chunk2Data.size.toLong()).getOrThrow()

        val finalEncryptedFile = File(testStorageDir, "$pdfId.enc")
        var progressCalled = false
        val assembleRes = PdfSecurityStorageService.assembleChunksToMpdf(
            chunksDir = chunksDir,
            destinationEncryptedFile = finalEncryptedFile,
            expectedTotalBytes = totalBytes,
            totalChunksCount = 3,
            userSecureDir = testStorageDir,
            onProgress = { processed, total ->
                progressCalled = true
                assertTrue(processed <= total)
            }
        )
        assertTrue("يجب أن ينجح تجميع الأجزاء في ملف MPDF", assembleRes.isSuccess)
        assertTrue("يجب أن يتم استدعاء دالة التقدم", progressCalled)
        assertTrue(finalEncryptedFile.exists())
        assertFalse("يجب حذف مجلد الأجزاء المؤقتة بعد اكتمال التجميع", chunksDir.exists())

        // التحقق من توافق الملف المجمع مع آلية فك التشفير الحالية decryptToTempFile
        val decryptRes = PdfSecurityStorageService.decryptToTempFile(finalEncryptedFile, testCacheDir)
        assertTrue("يجب أن ينجح فك تشفير الملف المجمع عبر decryptToTempFile", decryptRes.isSuccess)
        val tempPdf = decryptRes.getOrThrow()
        try {
            assertTrue(tempPdf.exists())
            assertEquals(totalBytes, tempPdf.length())
            val decryptedBytes = tempPdf.readBytes()
            assertArrayEquals("الملف المفكوك بعد التجميع يجب أن يطابق الأصل تماماً", fullData, decryptedBytes)
        } finally {
            tempPdf.delete()
        }
    }

    /**
     * 8. فشل التجميع عند تلف أحد الأجزاء دون استبدال أو المساس بالملف النهائي السابق
     */
    @Test
    fun testAssembleFailurePreservesPreviousFinalFile() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        val finalEncryptedFile = File(testStorageDir, "$pdfId.enc")
        finalEncryptedFile.writeText("PREVIOUS_VALID_CONTENT")
        val previousLength = finalEncryptedFile.length()

        val pdfSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
        val chunkSize = PdfSecurityStorageService.DEFAULT_CHUNK_SIZE_BYTES // 2 MB
        val remainder = 100 * 1024L // 100 KB
        val totalExpected = chunkSize + remainder

        val chunk0Data = generateDeterministicData(chunkSize.toInt(), prefix = pdfSignature)
        val chunk1Data = generateDeterministicData(remainder.toInt())

        val chunk0File = File(chunksDir, "chunk_0.enc")
        val chunk1File = File(chunksDir, "chunk_1.enc")

        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk0Data), chunk0File, 0, chunkSize).getOrThrow()
        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk1Data), chunk1File, 1, remainder).getOrThrow()

        // تخريب chunk_1.enc
        RandomAccessFile(chunk1File, "rw").use { raf ->
            raf.seek(32)
            raf.writeByte(0xAA)
        }

        val assembleRes = PdfSecurityStorageService.assembleChunksToMpdf(
            chunksDir = chunksDir,
            destinationEncryptedFile = finalEncryptedFile,
            expectedTotalBytes = totalExpected,
            totalChunksCount = 2,
            userSecureDir = testStorageDir
        )
        assertTrue("يجب أن يفشل التجميع عند تلف الجزء", assembleRes.isFailure)

        // التحقق من بقاء الملف النهائي القديم سليماً دون تغيير
        assertTrue(finalEncryptedFile.exists())
        assertEquals(previousLength, finalEncryptedFile.length())
        assertEquals("PREVIOUS_VALID_CONTENT", finalEncryptedFile.readText())
    }

    /**
     * 9. حذف الملفات المؤقتة عند الفشل
     */
    @Test
    fun testTemporaryFilesCleanedUpOnFailure() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        val finalEncryptedFile = File(testStorageDir, "$pdfId.enc")
        val chunk0File = File(chunksDir, "chunk_0.enc")
        val badData = byteArrayOf(1, 2, 3) // ليس PDF صالحاً (لا يبدأ بـ %PDF-)

        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(badData), chunk0File, 0, badData.size.toLong()).getOrThrow()

        val assembleRes = PdfSecurityStorageService.assembleChunksToMpdf(
            chunksDir = chunksDir,
            destinationEncryptedFile = finalEncryptedFile,
            expectedTotalBytes = 3L,
            totalChunksCount = 1,
            userSecureDir = testStorageDir
        )
        assertTrue("يجب أن يفشل التجميع لملف ليس PDF", assembleRes.isFailure)

        // التحقق من عدم وجود أي ملفات مؤقتة (.tmp_*) في مجلد التخزين
        val tmpFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }
        assertTrue("يجب تنظيف وحذف جميع الملفات المؤقتة عند الفشل", tmpFiles.isNullOrEmpty())
    }

    /**
     * 10. إثبات عدم إنشاء أي ملف PDF مكشوف على القرص أثناء تشفير الأجزاء أو التجميع
     */
    @Test
    fun testProveNoUnencryptedPdfWrittenToDiskDuringChunkingAndAssembly() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        val pdfSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
        val data0 = generateDeterministicData(100 * 1024, prefix = pdfSignature)
        val chunk0File = File(chunksDir, "chunk_0.enc")

        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(data0), chunk0File, 0, data0.size.toLong()).getOrThrow()

        val finalFile = File(testStorageDir, "$pdfId.enc")
        PdfSecurityStorageService.assembleChunksToMpdf(
            chunksDir = chunksDir,
            destinationEncryptedFile = finalFile,
            expectedTotalBytes = data0.size.toLong(),
            totalChunksCount = 1,
            userSecureDir = testStorageDir
        ).getOrThrow()

        // فحص جميع الملفات على القرص للتأكد من عدم وجود أي ملف يبدأ بتوقيع %PDF-
        val allFiles = testStorageDir.walkTopDown().filter { it.isFile }.toList()
        for (f in allFiles) {
            val bytes = ByteArray(5)
            f.inputStream().use { it.read(bytes) }
            assertFalse(
                "لا يجوز حفظ أي ملف غير مشفر يبدأ بـ %PDF- على القرص: ${f.name}",
                bytes.contentEquals(pdfSignature)
            )
        }
    }

    /**
     * 11. اختبار ملف بحجم 30MB مع الحفاظ على استهلاك ذاكرة محدود (Memory-Bounded Assembly)
     */
    @Test
    fun testAssemble30MbFileWithBoundedMemory() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        // 30 ميغابايت مقسمة إلى 15 جزءاً بحجم 2 ميغابايت لكل جزء
        val totalBytes = 30L * 1024L * 1024L
        val chunkSize = 2L * 1024L * 1024L
        val chunksCount = 15

        val pdfSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)

        // دفق البيانات مولدة آنياً دون حجز مصفوفات 30MB في الذاكرة الحية
        class ZeroAllocInputStream(private val streamLength: Long, private val prefix: ByteArray? = null) : java.io.InputStream() {
            private var cursor = 0L

            override fun read(): Int {
                if (cursor >= streamLength) return -1
                val b = if (prefix != null && cursor < prefix.size) {
                    prefix[cursor.toInt()].toInt() and 0xFF
                } else {
                    ((cursor * 31 + 17) and 0xFF).toInt()
                }
                cursor++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (cursor >= streamLength) return -1
                val toRead = minOf(len.toLong(), streamLength - cursor).toInt()
                for (i in 0 until toRead) {
                    val curr = cursor + i
                    b[off + i] = if (prefix != null && curr < prefix.size) {
                        prefix[curr.toInt()]
                    } else {
                        ((curr * 31 + 17) and 0xFF).toByte()
                    }
                }
                cursor += toRead
                return toRead
            }
        }

        // تشفير الـ 15 جزءاً تدفقياً باستخدام دفق لا يستهلك ذاكرة
        for (i in 0 until chunksCount) {
            val chunkPrefix = if (i == 0) pdfSignature else null
            val chunkFile = File(chunksDir, "chunk_${i}.enc")
            PdfSecurityStorageService.encryptChunkStream(
                inputStream = ZeroAllocInputStream(chunkSize, prefix = chunkPrefix),
                destinationChunkFile = chunkFile,
                chunkIndex = i,
                plainLength = chunkSize
            ).getOrThrow()
        }

        val finalFile = File(testStorageDir, "$pdfId.enc")

        // تنظيف الذاكرة وقياس استهلاك الذاكرة قبل التجميع
        System.gc()
        Thread.sleep(50)
        val memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val assembleRes = PdfSecurityStorageService.assembleChunksToMpdf(
            chunksDir = chunksDir,
            destinationEncryptedFile = finalFile,
            expectedTotalBytes = totalBytes,
            totalChunksCount = chunksCount,
            userSecureDir = testStorageDir
        )
        assertTrue("يجب أن ينجح تجميع ملف 30MB", assembleRes.isSuccess)
        assertTrue(finalFile.exists())

        // التحقق من صحة الملف النهائي المشفر وحجمه
        assertTrue(PdfSecurityStorageService.isEncryptedFilePresent(finalFile))
        assertEquals(
            "حجم الملف المشفر MPDF النهائي = 5 ترويسة + 12 IV + 30MB بيانات + 16 Tag",
            PdfSecurityStorageService.HEADER_TOTAL_LENGTH + 12 + totalBytes + 16,
            finalFile.length()
        )

        // التحقق من أن حجم الذاكرة المستخدمة لم يتضخم بحجم الملف (30MB) لأن التجميع يستخدم مخزن 64KB فقط
        System.gc()
        Thread.sleep(50)
        val memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryDelta = memoryAfter - memoryBefore

        assertTrue(
            "يجب ألا يقفز استهلاك الذاكرة الحية بأكثر من 15MB أثناء التجميع التدفيقي لمخزن 64KB (الزيادة الفعلية: ${memoryDelta / (1024 * 1024)}MB)",
            memoryDelta < 15L * 1024L * 1024L
        )
    }

    /**
     * 12. حذف مجلد أجزاء PDF المشفرة وبيانات الاستكمال بشكل آمن مع منع Path Traversal
     */
    @Test
    fun testDeleteChunksDirectorySecurely() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        val chunk0 = File(chunksDir, "chunk_0.enc")
        val data = generateDeterministicData(1024)
        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(data), chunk0, 0, data.size.toLong()).getOrThrow()

        val meta = ResumeMetadata(
            pdfId = pdfId,
            remoteFilePath = "lessons/test.pdf",
            expectedTotalBytes = 1024L,
            totalChunksCount = 1,
            completedChunks = listOf(0)
        )
        PdfSecurityStorageService.saveResumeMetadata(chunksDir, meta).getOrThrow()

        assertTrue(chunksDir.exists())
        assertTrue(chunk0.exists())

        val deleted = PdfSecurityStorageService.deleteChunksDirectory(chunksDir, testStorageDir)
        assertTrue("يجب أن ينجح الحذف الآمن لمجلد الأجزاء", deleted)
        assertFalse("يجب ألا يبقى مجلد الأجزاء موجوداً", chunksDir.exists())

        // محاولة حذف مجلد خارج النطاق المسموح به
        val outsideDir = tempFolder.newFolder("outside")
        val deletedOutside = PdfSecurityStorageService.deleteChunksDirectory(outsideDir, testStorageDir)
        assertFalse("يجب رفض حذف مجلد يقع خارج allowedBaseDir", deletedOutside)
    }

    /**
     * 13. دفق أقصر من plainLength: يجب رفض العملية وحذف الملف المؤقت وعدم اعتماد الملف النهائي
     */
    @Test
    fun testRejectShorterStreamAndCleanupTempFile() {
        val chunkFile = File(testStorageDir, "chunk_short.enc")
        val shortData = generateDeterministicData(500) // فقط 500 بايت بينما المطلوب 1000 بايت

        val result = PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(shortData),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = 1000L
        )

        assertTrue("يجب أن يفشل تشفير الجزء عندما يكون الدفق أقصر من المطلوب", result.isFailure)
        assertFalse("يجب ألا يتم اعتماد ملف الجزء النهائي", chunkFile.exists())

        // التحقق من تنظيف وحذف أي ملفات مؤقتة
        val tmpFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }
        assertTrue("يجب ألا تبقى أي ملفات مؤقتة عند فشل الدفق القصير", tmpFiles.isNullOrEmpty())
    }

    /**
     * 14. دفق أطول من plainLength: يجب رفض العملية بسبب البايتات الزائدة وعدم اعتماد الجزء
     */
    @Test
    fun testRejectLongerStreamAndDoNotCommitChunk() {
        val chunkFile = File(testStorageDir, "chunk_long.enc")
        val longData = generateDeterministicData(1500) // 1500 بايت بينما المطلوب فقط 1000 بايت

        val result = PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(longData),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = 1000L
        )

        assertTrue("يجب أن يفشل تشفير الجزء عندما يحتوي الدفق على بايتات زائدة", result.isFailure)
        assertTrue(
            "رسالة الاستثناء يجب أن تشير إلى البايتات الزائدة",
            result.exceptionOrNull()?.message?.contains("بايتات زائدة") == true
        )
        assertFalse("يجب ألا يتم اعتماد ملف الجزء النهائي عند وجود بايتات زائدة", chunkFile.exists())

        val tmpFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }
        assertTrue("يجب تنظيف الملفات المؤقتة عند رفض الدفق ذي البايتات الزائدة", tmpFiles.isNullOrEmpty())
    }

    /**
     * 15. ملف جزء مبتور (Truncated Chunk File): يجب رفضه من خلال readChunkHeader و verifyChunkFile
     */
    @Test
    fun testRejectTruncatedChunkFile() {
        val chunkFile = File(testStorageDir, "chunk_truncated.enc")
        val data = generateDeterministicData(10 * 1024)

        PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(data),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = data.size.toLong()
        ).getOrThrow()

        val originalSize = chunkFile.length()
        // بتر 100 بايت من نهاية الملف المشفر
        RandomAccessFile(chunkFile, "rw").use { raf ->
            raf.setLength(originalSize - 100)
        }

        val verifyRes = PdfSecurityStorageService.verifyChunkFile(chunkFile, 0)
        assertTrue("يجب رفض ملف الجزء المبتور", verifyRes.isFailure)

        val out = ByteArrayOutputStream()
        val decryptRes = PdfSecurityStorageService.decryptChunkStream(chunkFile, 0, out)
        assertTrue("يجب أن يفشل فك تشفير الجزء المبتور", decryptRes.isFailure)
    }

    /**
     * 16. ملف جزء يحتوي على بايتات إضافية بعد GCM Tag: يجب رفضه
     */
    @Test
    fun testRejectChunkFileWithAppendedExtraBytes() {
        val chunkFile = File(testStorageDir, "chunk_appended.enc")
        val data = generateDeterministicData(10 * 1024)

        PdfSecurityStorageService.encryptChunkStream(
            inputStream = ByteArrayInputStream(data),
            destinationChunkFile = chunkFile,
            chunkIndex = 0,
            plainLength = data.size.toLong()
        ).getOrThrow()

        // إضافة 50 بايت زائدة إلى نهاية ملف الجزء المشفر بعد الـ Tag
        RandomAccessFile(chunkFile, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(ByteArray(50) { 0x55 })
        }

        val verifyRes = PdfSecurityStorageService.verifyChunkFile(chunkFile, 0)
        assertTrue("يجب رفض ملف الجزء الذي يحتوي على بايتات زائدة بعد الـ Tag", verifyRes.isFailure)

        val out = ByteArrayOutputStream()
        val decryptRes = PdfSecurityStorageService.decryptChunkStream(chunkFile, 0, out)
        assertTrue("يجب أن يفشل فك تشفير جزء يحوي بايتات زائدة", decryptRes.isFailure)
    }

    /**
     * 17. اختلاف مجموع أطوال الأجزاء أو أطوالها الفردية عن expectedTotalBytes: رفض التجميع والحفاظ على الملف السابق
     */
    @Test
    fun testRejectAssemblyWhenChunksLengthSumMismatchesExpectedTotalBytes() {
        val pdfId = UUID.randomUUID().toString()
        val chunksDir = PdfSecurityStorageService.getChunksDirectory(testStorageDir, pdfId).getOrThrow()
        chunksDir.mkdirs()

        val finalEncryptedFile = File(testStorageDir, "$pdfId.enc")
        finalEncryptedFile.writeText("PREVIOUS_FINAL_CONTENT_STAYS_INTACT")
        val previousLength = finalEncryptedFile.length()

        val pdfSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
        val chunkSize = PdfSecurityStorageService.DEFAULT_CHUNK_SIZE_BYTES // 2 MB
        val expectedTotal = chunkSize + 500 * 1024L // 2.5 MB (يجب أن يكون الجزء 1 بحجم 500KB)

        val chunk0Data = generateDeterministicData(chunkSize.toInt(), prefix = pdfSignature)
        // إنشاء الجزء 1 بحجم 400KB فقط بدلاً من 500KB المتوقعة (المجموع 2.4MB بدلاً من 2.5MB)
        val chunk1BadData = generateDeterministicData(400 * 1024)

        val chunk0File = File(chunksDir, "chunk_0.enc")
        val chunk1File = File(chunksDir, "chunk_1.enc")

        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk0Data), chunk0File, 0, chunkSize).getOrThrow()
        PdfSecurityStorageService.encryptChunkStream(ByteArrayInputStream(chunk1BadData), chunk1File, 1, 400 * 1024L).getOrThrow()

        val assembleRes = PdfSecurityStorageService.assembleChunksToMpdf(
            chunksDir = chunksDir,
            destinationEncryptedFile = finalEncryptedFile,
            expectedTotalBytes = expectedTotal,
            totalChunksCount = 2,
            userSecureDir = testStorageDir
        )

        assertTrue("يجب أن يفشل التجميع عند اختلاف مجموع أو أحجام الأجزاء عن الحجم المتوقع", assembleRes.isFailure)
        assertTrue(
            "يجب أن يوضح سبب الخطأ عدم مطابقة الحجم",
            assembleRes.exceptionOrNull()?.message?.contains("لا يطابق") == true
        )

        // التحقق الحاسم من بقاء الملف النهائي القديم سليماً دون مساس
        assertTrue(finalEncryptedFile.exists())
        assertEquals(previousLength, finalEncryptedFile.length())
        assertEquals("PREVIOUS_FINAL_CONTENT_STAYS_INTACT", finalEncryptedFile.readText())

        // التحقق من تنظيف الملفات المؤقتة
        val tmpFiles = testStorageDir.listFiles { _, name -> name.contains(".tmp_") }
        assertTrue("يجب تنظيف وحذف جميع الملفات المؤقتة عند فشل التجميع", tmpFiles.isNullOrEmpty())
    }
}
