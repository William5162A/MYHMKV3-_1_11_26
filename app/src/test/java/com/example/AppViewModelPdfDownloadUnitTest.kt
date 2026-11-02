package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AuthSession
import com.example.data.PdfSecurityStorageService
import com.example.data.SessionManager
import com.example.data.SupabaseStorageService
import com.example.model.LessonPdf
import com.example.model.SupabaseConfig
import com.example.viewmodel.AppViewModel
import com.example.viewmodel.LocalPdfStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppViewModelPdfDownloadUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var testSecretKey: SecretKey

    private fun createValidPdfBytes(text: String = "Test PDF Content"): ByteArray {
        return byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\n$text\n%%EOF".toByteArray(Charsets.UTF_8)
    }

    private fun createTestLessonPdf(
        id: String = UUID.randomUUID().toString(),
        lessonId: String = "lesson_1",
        subjectId: String = "subject_1",
        title: String = "Test PDF",
        filePath: String = "test/path.pdf",
        fileSizeBytes: Long? = 100L
    ): LessonPdf {
        return LessonPdf(
            id = id,
            lessonId = lessonId,
            subjectId = subjectId,
            title = title,
            filePath = filePath,
            fileSizeBytes = fileSizeBytes,
            mimeType = "application/pdf",
            orderIndex = 0,
            isPublished = true,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
    }

    private fun saveTestSession(userId: String, token: String = "valid_token") {
        SessionManager.saveSession(
            AuthSession(
                userId = userId,
                email = "$userId@example.com",
                displayName = userId,
                accessToken = token,
                refreshToken = "refresh_$userId",
                expiresAt = System.currentTimeMillis() + 3600_000L
            )
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SessionManager.init(context)
        SessionManager.clearSession()

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testSecretKey = keyGen.generateKey()
        PdfSecurityStorageService.testKeyProvider = { testSecretKey }
    }

    @After
    fun tearDown() {
        SupabaseStorageService.testClient = null
        SessionManager.clearSession()
    }

    /**
     * 1. نجاح التنزيل وتحديث الحالة إلى Downloaded مع الحجم المناسب وتخزينه مشفراً
     */
    @Test
    fun testSuccessfulDownloadAndStatusUpdatesToDownloaded() {
        runBlocking {
            val userId = "user_alpha_1"
            saveTestSession(userId = userId, token = "valid_alpha_jwt")

            val pdfBytes = createValidPdfBytes("Lesson 1 Math")
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_101",
                title = "Math Lesson 1",
                filePath = "math/unit1/lesson1.pdf",
                fileSizeBytes = pdfBytes.size.toLong()
            )

            viewModel.downloadLessonPdf(context, lessonPdf)

            var state = viewModel.uiState.value.localPdfStatuses[pdfId]
            var retries = 0
            while (state !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                state = viewModel.uiState.value.localPdfStatuses[pdfId]
                retries++
            }

            assertTrue("يجب أن تصبح الحالة Downloaded", state is LocalPdfStatus.Downloaded)
            val downloadedState = state as LocalPdfStatus.Downloaded
            assertTrue("حجم الملف يجب أن يكون أكبر من الصفر", downloadedState.fileSizeBytes > 0)

            val localEncryptedFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            assertTrue("الملف المشفر يجب أن يكون موجوداً على القرص", localEncryptedFile.exists())
            assertTrue(
                "الملف يجب أن يحتوي على ترويسة مِداد المشفرة الصالحة",
                PdfSecurityStorageService.isEncryptedFilePresent(localEncryptedFile)
            )

            // التأكد التام من عدم وجود ملف .pdf مكشوف
            val plainFile = File(localEncryptedFile.parentFile, "$pdfId.pdf")
            assertFalse("يجب ألا يوجد أي ملف مكشوف غير مشفر", plainFile.exists())
        }
    }

    /**
     * 2. منع تكرار تنزيل الملف نفسه بالتزامن لنفس المستخدم
     */
    @Test
    fun testConcurrentDownloadBlockedForSameFile() {
        runBlocking {
            val userId = "user_concurrent"
            saveTestSession(userId = userId, token = "token_concurrent")

            val requestCount = AtomicInteger(0)
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    requestCount.incrementAndGet()
                    val pdfBytes = createValidPdfBytes("Delayed PDF")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_conc",
                title = "Concurrent Test",
                filePath = "math/conc.pdf",
                fileSizeBytes = 100L
            )

            // إطلاق طلبين متتاليين فوراً
            viewModel.downloadLessonPdf(context, lessonPdf)
            viewModel.downloadLessonPdf(context, lessonPdf)

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            assertEquals("يجب إرسال طلب شبكي واحد فقط ومنع التكرار التزامني", 1, requestCount.get())
        }
    }

    /**
     * 3. اختبار حاسم: إثبات أن إلغاء Coroutine يؤدي فعلياً إلى استدعاء call.cancel() على OkHttp
     * وليس مجرد تغيير حالة Job
     */
    @Test
    fun testCoroutineCancellationActuallyCancelsOkHttpCall() {
        runBlocking {
            val userId = "user_cancel_call"
            saveTestSession(userId = userId, token = "token_cancel")

            var interceptedCall: Call? = null
            val inFlightLatch = CountDownLatch(1)
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    interceptedCall = chain.call()
                    val source = object : ForwardingSource(Buffer().write(createValidPdfBytes("Slow stream data"))) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            inFlightLatch.countDown()
                            val start = System.currentTimeMillis()
                            while (!chain.call().isCanceled() && System.currentTimeMillis() - start < 1500) {
                                Thread.sleep(10)
                            }
                            if (chain.call().isCanceled()) {
                                throw IOException("Canceled")
                            }
                            return super.read(sink, byteCount)
                        }
                    }

                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(object : ResponseBody() {
                            override fun contentType() = "application/pdf".toMediaTypeOrNull()
                            override fun contentLength() = 1000L
                            override fun source(): BufferedSource = source.buffer()
                        })
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_slow",
                title = "Slow Lesson",
                filePath = "slow/lesson.pdf",
                fileSizeBytes = 1000L
            )

            viewModel.downloadLessonPdf(context, lessonPdf)
            inFlightLatch.await(3, TimeUnit.SECONDS)

            // إلغاء التنزيل من الـ ViewModel أثناء جريان القراءة
            viewModel.cancelLessonPdfDownload(pdfId)
            delay(50)

            assertNotNull("يجب أن يتم اعتراض الـ Call", interceptedCall)
            assertTrue(
                "إلغاء Coroutine يجب أن يستدعي call.cancel() على OkHttp فعلياً ويجعل call.isCanceled() مساوياً لـ true",
                interceptedCall!!.isCanceled()
            )

            // التحقق من أن حالة الملف عادت إلى NotDownloaded دون تسجيل رسالة خطأ
            val finalStatus = viewModel.uiState.value.localPdfStatuses[pdfId]
            assertTrue("الحالة يجب أن تكون NotDownloaded عند الإلغاء", finalStatus is LocalPdfStatus.NotDownloaded)
        }
    }

    /**
     * 4. إلغاء التنزيل وتنظيف الملفات المؤقتة وعدم ترك ملف مشفر جزئي
     */
    @Test
    fun testCancelCleansUpTempFilesAndLeavesNoPartialFile() {
        runBlocking {
            val userId = "user_cleanup"
            saveTestSession(userId = userId, token = "token_clean")

            val inFlightLatch = CountDownLatch(1)
            val proceedLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val source = object : ForwardingSource(Buffer().write(createValidPdfBytes("Data"))) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            inFlightLatch.countDown()
                            proceedLatch.await(3, TimeUnit.SECONDS)
                            if (chain.call().isCanceled()) {
                                throw IOException("Canceled")
                            }
                            return super.read(sink, byteCount)
                        }
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(object : ResponseBody() {
                            override fun contentType() = "application/pdf".toMediaTypeOrNull()
                            override fun contentLength() = 500L
                            override fun source(): BufferedSource = source.buffer()
                        })
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_clean",
                title = "Cleanup Test",
                filePath = "clean/file.pdf",
                fileSizeBytes = 500L
            )

            viewModel.downloadLessonPdf(context, lessonPdf)
            assertTrue("يجب وصول التنزيل إلى الشبكة", inFlightLatch.await(2, TimeUnit.SECONDS))

            // إلغاء التنزيل أثناء الجريان
            viewModel.cancelLessonPdfDownload(pdfId)
            proceedLatch.countDown()

            var retries = 0
            while (viewModel.getActiveJobsCount() > 0 && retries < 40) {
                delay(25)
                retries++
            }

            val userDir = viewModel.getUserEncryptedStorageDir(context, userId)
            val filesInDir = userDir.listFiles().orEmpty()
            assertEquals("يجب ألا يتبقى أي ملفات مؤقتة أو غير مكتملة في مجلد المستخدم بعد الإلغاء", 0, filesInDir.size)
            assertEquals(
                "الحالة يجب أن تعود إلى NotDownloaded",
                LocalPdfStatus.NotDownloaded,
                viewModel.uiState.value.localPdfStatuses[pdfId]
            )
        }
    }

    /**
     * 5. الحذف المحلي المشفر دون أي استدعاء لخادم أو قاعدة بيانات Supabase
     */
    @Test
    fun testLocalDeletionRemovesFileWithoutSupabaseRequest() {
        runBlocking {
            val userId = "user_delete"
            saveTestSession(userId = userId, token = "token_del")

            val networkRequests = AtomicInteger(0)
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    networkRequests.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(createValidPdfBytes("For Deletion").toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_del",
                title = "Delete Test",
                filePath = "del/file.pdf",
                fileSizeBytes = 200L
            )

            viewModel.downloadLessonPdf(context, lessonPdf)

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            val targetFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            assertTrue("الملف تم تحميله أولاً بنجاح", targetFile.exists())
            assertEquals("تم تنفيذ طلب شبكي واحد للتنزيل", 1, networkRequests.get())

            // الآن: تنفيذ الحذف المحلي
            viewModel.deleteLocalSecurePdf(context, lessonPdf)

            assertFalse("الملف المشفر المحلي يجب أن يُحذف من القرص", targetFile.exists())
            assertEquals(
                "الحالة يجب أن تصبح NotDownloaded",
                LocalPdfStatus.NotDownloaded,
                viewModel.uiState.value.localPdfStatuses[pdfId]
            )
            assertEquals("يجب ألا يتم تنفيذ أي طلب شبكي لـ Supabase أثناء الحذف المحلي", 1, networkRequests.get())
        }
    }

    /**
     * 6. عزل مجلدات المستخدمين المختلفة (user_A vs user_B)
     */
    @Test
    fun testMultiUserStorageIsolation() {
        runBlocking {
            val userA = "user_alice"
            val userB = "user_bob"
            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_shared",
                title = "Shared Subject",
                filePath = "shared/lesson.pdf",
                fileSizeBytes = 300L
            )

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(createValidPdfBytes("Multiuser").toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            // 1. تسجيل الدخول كمستخدم A وتنزيل الملف
            saveTestSession(userId = userA, token = "token_a")
            val viewModelA = AppViewModel()
            viewModelA.downloadLessonPdf(context, lessonPdf)

            var retries = 0
            while (viewModelA.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            val fileA = viewModelA.getLocalEncryptedPdfFile(context, userA, pdfId)
            assertTrue("ملف المستخدم A يجب أن يكون موجوداً", fileA.exists())

            // 2. تسجيل الدخول كمستخدم B والتحقق من أن مجلده منفصل تماماً
            saveTestSession(userId = userB, token = "token_b")
            val viewModelB = AppViewModel()
            val fileB = viewModelB.getLocalEncryptedPdfFile(context, userB, pdfId)

            assertFalse("ملف المستخدم B يجب ألا يكون موجوداً بمجرد تنزيل A له", fileB.exists())
            assertNotEquals("مسار تخزين المستخدم A يختلف عن مسار المستخدم B", fileA.canonicalPath, fileB.canonicalPath)

            viewModelB.checkAndSyncLocalPdfStatuses(context, listOf(lessonPdf))
            assertEquals(
                "حالة الملف للمستخدم B يجب أن تكون NotDownloaded",
                LocalPdfStatus.NotDownloaded,
                viewModelB.uiState.value.localPdfStatuses[pdfId]
            )
        }
    }

    /**
     * 7. رفض معرف PDF غير صالح (ليس UUID) ومنع Path Traversal
     */
    @Test
    fun testRejectsInvalidPdfIdAndPathTraversal() {
        runBlocking {
            saveTestSession(userId = "user_traversal", token = "token_trav")

            val viewModel = AppViewModel()
            val invalidPdf = createTestLessonPdf(
                id = "../../bad_id",
                lessonId = "lesson_bad",
                title = "Bad ID",
                filePath = "bad.pdf",
                fileSizeBytes = 10L
            )

            viewModel.downloadLessonPdf(context, invalidPdf)
            delay(25)

            val status = viewModel.uiState.value.localPdfStatuses["../../bad_id"]
            assertTrue("يجب أن تفشل العملية ويكون نوع الحالة Error", status is LocalPdfStatus.Error)
        }
    }

    /**
     * 8. فشل التنزيل عند عدم وجود جلسة مستخدم نشطة
     */
    @Test
    fun testDownloadFailsWhenNoSession() {
        runBlocking {
            SessionManager.clearSession()
            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "l1",
                title = "No Session",
                filePath = "test.pdf",
                fileSizeBytes = 10L
            )

            viewModel.downloadLessonPdf(context, pdf)
            delay(25)

            val status = viewModel.uiState.value.localPdfStatuses[pdfId]
            assertTrue("يجب تعيين الحالة إلى Error لعدم وجود جلسة", status is LocalPdfStatus.Error)
            val errorMsg = (status as LocalPdfStatus.Error).message
            assertTrue(errorMsg.contains("تسجيل الدخول"))
        }
    }

    /**
     * 9. تسجيل الخروج يلغي التنزيلات النشطة وينظف مجلد المستخدم الحالي ومجلد العرض المؤقت
     */
    @Test
    fun testLogoutCancelsActiveJobsAndPurgesUserDirectory() {
        runBlocking {
            val userId = "user_logout_test"
            saveTestSession(userId = userId, token = "token_logout")

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(createValidPdfBytes("Logout Data").toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_logout",
                title = "Logout PDF",
                filePath = "logout/test.pdf",
                fileSizeBytes = 200L
            )

            viewModel.downloadLessonPdf(context, pdf)

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            val userDir = viewModel.getUserEncryptedStorageDir(context, userId)
            assertTrue("مجلد المستخدم يجب أن يحتوي على الملف المحمل قبل تسجيل الخروج", userDir.exists())

            // تسجيل الخروج وتمرير Context
            viewModel.logout(context)

            var waitLogout = 0
            while ((viewModel.isLoggingOut() || userDir.exists()) && waitLogout < 50) {
                delay(25)
                waitLogout++
            }

            assertFalse("يجب أن يصبح المستخدم غير مسجل الدخول", viewModel.uiState.value.isLoggedIn)
            assertTrue("يجب مسح خريطة حالات الملفات المحلية", viewModel.uiState.value.localPdfStatuses.isEmpty())
            assertFalse("يجب حذف مجلد المستخدم المشفر بالكامل عند تسجيل الخروج", userDir.exists())
        }
    }

    /**
     * 10. عدم إعلان Downloaded قبل اكتمال التشفير أو عند استقبال بيانات غير صالحة
     */
    @Test
    fun testDoesNotAnnounceDownloadedOnNonPdfContent() {
        runBlocking {
            val userId = "user_corrupt"
            saveTestSession(userId = userId, token = "token_corrupt")

            val corruptBytes = "NOT_A_PDF_STREAM_CONTENT".toByteArray(Charsets.UTF_8)
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(corruptBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_corrupt",
                title = "Corrupt PDF",
                filePath = "corrupt/file.pdf",
                fileSizeBytes = corruptBytes.size.toLong()
            )

            viewModel.downloadLessonPdf(context, pdf)

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] is LocalPdfStatus.Downloading && retries < 40) {
                delay(25)
                retries++
            }

            val finalState = viewModel.uiState.value.localPdfStatuses[pdfId]
            assertTrue("يجب أن تكون الحالة النهائية Error لعدم مطابقة توقيع PDF", finalState is LocalPdfStatus.Error)
            val localFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            assertFalse("يجب ألا يتم اعتماد أو حفظ أي ملف نهائي تالف", localFile.exists())
        }
    }

    /**
     * 11. الحجز الذري للمهام يمنع تكرار تنزيل الملف نفسه بالتزامن لنفس المستخدم
     */
    @Test
    fun testAtomicJobRegistrationRejectsConcurrentDuplicates() {
        runBlocking {
            val userId = "user_atomic"
            saveTestSession(userId = userId, token = "token_atomic")

            val pdfBytes = createValidPdfBytes("Atomic Concurrency PDF")
            val networkHits = AtomicInteger(0)
            val inFlightLatch = CountDownLatch(1)
            val releaseLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    networkHits.incrementAndGet()
                    inFlightLatch.countDown()
                    releaseLatch.await(3, TimeUnit.SECONDS)

                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_atomic",
                title = "Atomic PDF",
                filePath = "atomic/file.pdf",
                fileSizeBytes = pdfBytes.size.toLong()
            )

            // إطلاق 5 محاولات تنزيل متزامنة للملف نفسه والمستخدم نفسه
            val jobs = (1..5).map {
                launch(Dispatchers.Default) {
                    viewModel.downloadLessonPdf(context, pdf)
                }
            }

            // انتظار وصول الطلب الأول إلى الشبكة
            assertTrue("يجب أن يصل الطلب الأول إلى معالج الشبكة", inFlightLatch.await(2, TimeUnit.SECONDS))
            jobs.joinAll()

            // التأكد من أن خريطة المهام تحتوي على مهمة واحدة فقط
            assertEquals("يجب حجز مهمة واحدة فقط في خريطة المهام النشطة", 1, viewModel.getActiveJobsCount())

            // تحرير دفق الشبكة لاكتمال التنزيل
            releaseLatch.countDown()

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            assertTrue(
                "يجب اكتمال التنزيل بنجاح",
                viewModel.uiState.value.localPdfStatuses[pdfId] is LocalPdfStatus.Downloaded
            )
            assertEquals("يجب ألا يتم استدعاء الشبكة إلا مرة واحدة فقط", 1, networkHits.get())
            assertEquals("يجب أن تفرغ خريطة المهام النشطة بعد انتهاء التنزيل", 0, viewModel.getActiveJobsCount())
        }
    }

    /**
     * 12. إلغاء التنزيل يتقيد صارماً بمعرف المستخدم النشط ولا يؤثر في تنزيلات مستخدم آخر
     */
    @Test
    fun testCancelLessonPdfDownloadDoesNotAffectOtherUsers() {
        runBlocking {
            val userOne = "user_one"
            val userTwo = "user_two"
            saveTestSession(userId = userOne, token = "token_one")

            val pdfBytes = createValidPdfBytes("User Isolation PDF")
            val inFlightLatch = CountDownLatch(1)
            val releaseLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    inFlightLatch.countDown()
                    releaseLatch.await(3, TimeUnit.SECONDS)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(
                id = pdfId,
                lessonId = "lesson_iso",
                title = "Iso PDF",
                filePath = "iso/file.pdf",
                fileSizeBytes = pdfBytes.size.toLong()
            )

            // المستخدم الأول يبدأ التنزيل
            viewModel.downloadLessonPdf(context, pdf)
            assertTrue("يجب وصول تنزيل المستخدم الأول إلى الشبكة", inFlightLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, viewModel.getActiveJobsCount())

            // محاكاة تسجيل دخول المستخدم الثاني
            saveTestSession(userId = userTwo, token = "token_two")

            // المستخدم الثاني يحاول إلغاء الملف نفسه
            viewModel.cancelLessonPdfDownload(pdfId)

            // مهمة المستخدم الأول لا يجوز أن تُلغى لأن المفتاح مقيد بـ userId
            assertEquals(
                "مهمة تنزيل المستخدم الأول يجب أن تظل نشطة ولا تُلغى بواسطة مستخدم آخر",
                1,
                viewModel.getActiveJobsCount()
            )

            // استعادة جلسة المستخدم الأول وتحرير الدفق
            saveTestSession(userId = userOne, token = "token_one")
            releaseLatch.countDown()

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            assertTrue(
                "يجب أن يكتمل تنزيل المستخدم الأول بنجاح",
                viewModel.uiState.value.localPdfStatuses[pdfId] is LocalPdfStatus.Downloaded
            )
            assertEquals(0, viewModel.getActiveJobsCount())
        }
    }

    /**
     * 13. رفض أي طلب تنزيل جديد أثناء عملية تسجيل الخروج
     */
    @Test
    fun testLogoutBlocksNewDownloadsWhileLoggingOut() {
        runBlocking {
            val userId = "user_block_logout"
            saveTestSession(userId = userId, token = "token_block")

            val pdfBytes = createValidPdfBytes("Block Test")
            val inFlightLatch = CountDownLatch(1)
            val logoutHoldLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    inFlightLatch.countDown()
                    logoutHoldLatch.await(3, TimeUnit.SECONDS)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId1 = UUID.randomUUID().toString()
            val pdf1 = createTestLessonPdf(id = pdfId1, title = "PDF 1")

            viewModel.downloadLessonPdf(context, pdf1)
            assertTrue(inFlightLatch.await(2, TimeUnit.SECONDS))

            // بدء تسجيل الخروج
            viewModel.logout(context)
            assertTrue("راية isLoggingOut يجب أن تصبح true أثناء الخروج", viewModel.isLoggingOut())

            // محاولة تنزيل ملف ثانٍ أثناء تسجيل الخروج
            val pdfId2 = UUID.randomUUID().toString()
            val pdf2 = createTestLessonPdf(id = pdfId2, title = "PDF 2")
            viewModel.downloadLessonPdf(context, pdf2)

            // يجب رفض التنزيل الثاني تماماً وعدم إضافة أي مهمة له
            assertNull(
                "يجب عدم تسجيل أي حالة تنزيل لملف أُرسل أثناء الخروج",
                viewModel.uiState.value.localPdfStatuses[pdfId2]
            )

            // تحرير التعليق لإنهاء تسجيل الخروج
            logoutHoldLatch.countDown()
            var retries = 0
            while (viewModel.isLoggingOut() && retries < 40) {
                delay(25)
                retries++
            }
            assertFalse("يجب إعادة ضبط راية isLoggingOut إلى false بعد انتهاء الخروج", viewModel.isLoggingOut())
        }
    }

    /**
     * 14. تغيير المستخدم أثناء التنزيل يحذف الملف الناتج ولا يعتمده لأي مستخدم
     */
    @Test
    fun testSessionSwitchDuringDownloadDiscardsFile() {
        runBlocking {
            val originalUser = "user_orig"
            val newUser = "user_intruder"
            saveTestSession(userId = originalUser, token = "token_orig")

            val pdfBytes = createValidPdfBytes("Session Switch PDF")
            val inFlightLatch = CountDownLatch(1)
            val proceedLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    inFlightLatch.countDown()
                    proceedLatch.await(3, TimeUnit.SECONDS)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(id = pdfId, title = "Switch PDF", fileSizeBytes = pdfBytes.size.toLong())

            viewModel.downloadLessonPdf(context, pdf)
            assertTrue("يجب وصول الطلب إلى المعالج الشبكي", inFlightLatch.await(2, TimeUnit.SECONDS))

            // تغيير الجلسة أثناء التنزيل الشبكي إلى مستخدم آخر
            saveTestSession(userId = newUser, token = "token_intruder")

            // تحرير استجابة الشبكة
            proceedLatch.countDown()

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] is LocalPdfStatus.Downloading && retries < 40) {
                delay(25)
                retries++
            }

            val targetFileOrig = viewModel.getLocalEncryptedPdfFile(context, originalUser, pdfId)
            val targetFileNew = viewModel.getLocalEncryptedPdfFile(context, newUser, pdfId)

            assertFalse("يجب حذف الملف المكتوب للمستخدم الأصلي بسبب تغير الجلسة", targetFileOrig.exists())
            assertFalse("يجب عدم اعتماد الملف للمستخدم الجديد أيضاً", targetFileNew.exists())
            assertEquals(
                "يجب إعادة تعيين الحالة إلى NotDownloaded",
                LocalPdfStatus.NotDownloaded,
                viewModel.uiState.value.localPdfStatuses[pdfId]
            )
        }
    }

    /**
     * 15. تسجيل الخروج يلغي المهام وينتظر اكتمالها قبل حذف مجلد التخزين
     */
    @Test
    fun testLogoutWaitsForActiveJobsBeforeDirectoryPurge() {
        runBlocking {
            val userId = "user_wait_purge"
            saveTestSession(userId = userId, token = "token_wait")

            val pdfBytes = createValidPdfBytes("Wait Purge PDF")
            val streamStartedLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    streamStartedLatch.countDown()
                    // محاكاة تدفق بطيء حتى يتم استلام الإلغاء
                    val buffer = Buffer().write(pdfBytes)
                    val delayedSource = object : ForwardingSource(buffer) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (chain.call().isCanceled()) {
                                throw IOException("Canceled by logout")
                            }
                            Thread.sleep(10)
                            return super.read(sink, byteCount.coerceAtMost(2))
                        }
                    }

                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(object : ResponseBody() {
                            override fun contentType() = "application/pdf".toMediaTypeOrNull()
                            override fun contentLength() = pdfBytes.size.toLong()
                            override fun source(): BufferedSource = delayedSource.buffer()
                        })
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(id = pdfId, title = "Purge PDF", fileSizeBytes = pdfBytes.size.toLong())

            viewModel.downloadLessonPdf(context, pdf)
            assertTrue("يجب بدء التدفق", streamStartedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, viewModel.getActiveJobsCount())

            val userDir = viewModel.getUserEncryptedStorageDir(context, userId)

            // تنفيذ تسجيل الخروج مع تمرير Context
            viewModel.logout(context)

            var retries = 0
            while ((viewModel.isLoggingOut() || userDir.exists()) && retries < 50) {
                delay(25)
                retries++
            }

            assertEquals("يجب تفريغ خريطة المهام النشطة", 0, viewModel.getActiveJobsCount())
            assertFalse("يجب حذف مجلد المستخدم المشفر تماماً", userDir.exists())
            assertFalse("يجب ألا تظل راية isLoggingOut مفعلة", viewModel.isLoggingOut())
        }
    }

    /**
     * 16. إلغاء تنزيل ثم طلب تنزيل الملف نفسه: عدم تداخل المهمتين وعدم حذف ملف التنزيل الجديد
     */
    @Test
    fun testCancelAndImmediateRedownloadDoNotInterfere() {
        runBlocking {
            val userId = "user_cancel_redownload"
            saveTestSession(userId = userId, token = "token_c_r")

            val pdfBytes = createValidPdfBytes("Redownload Content")
            val firstInFlightLatch = CountDownLatch(1)
            val firstProceedLatch = CountDownLatch(1)
            val networkAttempts = AtomicInteger(0)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val attempt = networkAttempts.incrementAndGet()
                    if (attempt == 1) {
                        firstInFlightLatch.countDown()
                        firstProceedLatch.await(3, TimeUnit.SECONDS)
                        if (chain.call().isCanceled()) {
                            throw IOException("Cancelled First")
                        }
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(id = pdfId, title = "Redownload PDF", fileSizeBytes = pdfBytes.size.toLong())

            // 1. بدء التنزيل الأول
            viewModel.downloadLessonPdf(context, pdf)
            assertTrue("يجب وصول التنزيل الأول للشبكة", firstInFlightLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, viewModel.getActiveJobsCount())

            // 2. إلغاء التنزيل الأول
            viewModel.cancelLessonPdfDownload(pdfId)

            // 3. محاولة فورية للتنزيل قبل تحرير المهمة الأولى: يجب رفضها لأن المفتاح ما زال محجوزاً للتنظيف
            viewModel.downloadLessonPdf(context, pdf)
            assertEquals("يجب ألا يتم استدعاء محاولة ثانية بالشبكة أثناء تنظيف الأولى", 1, networkAttempts.get())

            // 4. تحرير المهمة الأولى لتكمل معالجة الإلغاء وتنظيف ملفاتها ومسح نفسها من الخريطة
            firstProceedLatch.countDown()
            var retries = 0
            while (viewModel.getActiveJobsCount() > 0 && retries < 40) {
                delay(25)
                retries++
            }
            assertEquals("يجب تفريغ الخريطة بعد انتهاء تنظيف المهمة الملغاة", 0, viewModel.getActiveJobsCount())

            // 5. الآن يمكن بدء تنزيل جديد لنفس الملف بنجاح
            viewModel.downloadLessonPdf(context, pdf)
            retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            assertTrue(
                "يجب اكتمال التنزيل الجديد بنجاح",
                viewModel.uiState.value.localPdfStatuses[pdfId] is LocalPdfStatus.Downloaded
            )
            val finalFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            assertTrue("يجب بقاء الملف الجديد سليماً دون حذفه بواسطة المهمة القديمة", finalFile.exists())
            assertEquals(0, viewModel.getActiveJobsCount())
        }
    }

    /**
     * 17. بدء تسجيل الخروج بالتزامن مع حجز تنزيل جديد: حصر كافة المهام دون أي تسرب
     */
    @Test
    fun testConcurrentLogoutAndDownloadReservation() {
        runBlocking {
            val userId = "user_conc_logout"
            saveTestSession(userId = userId, token = "token_c_l")

            val pdfBytes = createValidPdfBytes("Concurrent Logout PDF")
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Thread.sleep(30)
                    if (chain.call().isCanceled()) {
                        throw IOException("Cancelled by logout")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfs = (1..6).map {
                createTestLessonPdf(id = UUID.randomUUID().toString(), title = "PDF $it")
            }

            // إطلاق محاولات التنزيل وتسجيل الخروج بالتزامن
            val jobs = pdfs.map { pdf ->
                launch(Dispatchers.Default) {
                    viewModel.downloadLessonPdf(context, pdf)
                }
            }
            val logoutJob = launch(Dispatchers.Default) {
                delay(5)
                viewModel.logout(context)
            }

            (jobs + logoutJob).joinAll()

            var retries = 0
            while ((viewModel.isLoggingOut() || viewModel.getActiveJobsCount() > 0) && retries < 50) {
                delay(25)
                retries++
            }

            assertEquals("يجب ألا تبقى أي مهمة نشطة خارج نطاق الإلغاء والانتظار", 0, viewModel.getActiveJobsCount())
            assertFalse("يجب ضبط حالة تسجيل الدخول إلى false", viewModel.uiState.value.isLoggedIn)
            assertFalse("يجب انتهاء راية الخروج", viewModel.isLoggingOut())
        }
    }

    /**
     * 18. تبديل الحساب أثناء وصول تحديثات التقدم: عدم تلويث حالة المستخدم الجديد
     */
    @Test
    fun testSessionSwitchDuringProgressUpdatesDoesNotPolluteNewUser() {
        runBlocking {
            val userOld = "user_old_prog"
            val userNew = "user_new_prog"
            saveTestSession(userId = userOld, token = "token_old")

            val pdfBytes = createValidPdfBytes("Progress Pollute PDF")
            val inFlightLatch = CountDownLatch(1)
            val proceedLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    inFlightLatch.countDown()
                    proceedLatch.await(3, TimeUnit.SECONDS)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(id = pdfId, title = "Progress PDF")

            viewModel.downloadLessonPdf(context, pdf)
            assertTrue(inFlightLatch.await(2, TimeUnit.SECONDS))

            // تبديل الجلسة إلى مستخدم جديد
            saveTestSession(userId = userNew, token = "token_new")

            // تحرير استجابة الشبكة
            proceedLatch.countDown()

            var retries = 0
            while (viewModel.getActiveJobsCount() > 0 && retries < 40) {
                delay(25)
                retries++
            }

            // التأكد من أن حالة المستخدم الجديد لم يتم تلويثها بـ Downloaded أو Downloading للملف السابق
            val statusForNewUser = viewModel.uiState.value.localPdfStatuses[pdfId]
            assertTrue(
                "يجب عدم تحديث حالة المستخدم الجديد بملفات المستخدم القديم (يجب أن تكون null أو NotDownloaded)",
                statusForNewUser == null || statusForNewUser is LocalPdfStatus.NotDownloaded
            )
        }
    }

    /**
     * 19. حدوث خطأ من مهمة قديمة بعد تبديل الحساب: عدم ظهور رسالة الخطأ للمستخدم الجديد
     */
    @Test
    fun testStaleJobErrorDoesNotPolluteNewUserState() {
        runBlocking {
            val userOld = "user_old_err"
            val userNew = "user_new_err"
            saveTestSession(userId = userOld, token = "token_old")

            val inFlightLatch = CountDownLatch(1)
            val proceedLatch = CountDownLatch(1)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    inFlightLatch.countDown()
                    proceedLatch.await(3, TimeUnit.SECONDS)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(id = pdfId, title = "Error PDF")

            viewModel.downloadLessonPdf(context, pdf)
            assertTrue(inFlightLatch.await(2, TimeUnit.SECONDS))

            // تبديل الجلسة قبل وصول الخطأ الشبكي
            saveTestSession(userId = userNew, token = "token_new")
            proceedLatch.countDown()

            var retries = 0
            while (viewModel.getActiveJobsCount() > 0 && retries < 40) {
                delay(25)
                retries++
            }

            val statusForNewUser = viewModel.uiState.value.localPdfStatuses[pdfId]
            assertFalse(
                "يجب ألا تظهر رسالة الخطأ الخاصة بالمستخدم القديم في حالة المستخدم الجديد",
                statusForNewUser is LocalPdfStatus.Error
            )
        }
    }

    /**
     * 20. عدم بقاء أي مهمة في خريطة المهام النشطة بعد الاكتمال
     */
    @Test
    fun testNoJobsRemainInMapAfterCompletion() {
        runBlocking {
            val userId = "user_clean_map"
            saveTestSession(userId = userId, token = "token_c_m")

            val pdfBytes = createValidPdfBytes("Clean Map PDF")
            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/pdf")
                        .body(pdfBytes.toResponseBody("application/pdf".toMediaTypeOrNull()))
                        .build()
                })
                .build()
            SupabaseStorageService.testClient = mockClient

            val viewModel = AppViewModel()
            val pdfId = UUID.randomUUID().toString()
            val pdf = createTestLessonPdf(id = pdfId, title = "Clean Map PDF", fileSizeBytes = pdfBytes.size.toLong())

            viewModel.downloadLessonPdf(context, pdf)

            var retries = 0
            while (viewModel.uiState.value.localPdfStatuses[pdfId] !is LocalPdfStatus.Downloaded && retries < 40) {
                delay(25)
                retries++
            }

            assertTrue(viewModel.uiState.value.localPdfStatuses[pdfId] is LocalPdfStatus.Downloaded)
            assertEquals("يجب أن تكون خريطة المهام فارغة تماماً بعد الاكتمال", 0, viewModel.getActiveJobsCount())
        }
    }
}
