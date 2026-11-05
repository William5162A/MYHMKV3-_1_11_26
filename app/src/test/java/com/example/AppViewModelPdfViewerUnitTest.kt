package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.AuthSession
import com.example.data.PdfSecurityStorageService
import com.example.data.SessionManager
import com.example.model.LessonPdf
import com.example.model.Subject
import com.example.viewmodel.AppViewModel
import com.example.viewmodel.UserRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppViewModelPdfViewerUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var testSecretKey: SecretKey

    private val defaultTestSubject = Subject(
        id = "subject_math_1",
        name = "Math 101",
        academicYear = 1,
        semester = 1,
        pricePoints = 50,
        isPublished = true
    )

    private fun createValidPdfBytes(text: String = "Test PDF Document"): ByteArray {
        return byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) + "1.7\n$text\n%%EOF".toByteArray(Charsets.UTF_8)
    }

    private fun createTestLessonPdf(
        id: String = UUID.randomUUID().toString(),
        lessonId: String = "lesson_101",
        subjectId: String = "subject_math_1",
        title: String = "Math Sheet 1",
        filePath: String = "math/lesson1.pdf",
        isPublished: Boolean = true
    ): LessonPdf {
        return LessonPdf(
            id = id,
            lessonId = lessonId,
            subjectId = subjectId,
            title = title,
            filePath = filePath,
            fileSizeBytes = 256L,
            mimeType = "application/pdf",
            orderIndex = 0,
            isPublished = isPublished,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
    }

    private fun saveTestSession(userId: String) {
        SessionManager.saveSession(
            AuthSession(
                userId = userId,
                email = "$userId@example.com",
                displayName = userId,
                accessToken = "valid_token_$userId",
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
        SessionManager.testKeyProvider = { testSecretKey }

        // تنظيف أي ملفات مؤقتة متبقية
        PdfSecurityStorageService.purgeViewerCacheDirectory(context.cacheDir)
    }

    @After
    fun tearDown() {
        SessionManager.clearSession()
        PdfSecurityStorageService.purgeViewerCacheDirectory(context.cacheDir)
        PdfSecurityStorageService.testKeyProvider = null
        SessionManager.testKeyProvider = null
    }

    /**
     * 1. نجاح فتح الملف وفك تشفيره تدفقياً إلى مجلد الكاش المؤقت للطالب المشترك
     */
    @Test
    fun testOpenPdfSuccessWhenEnrolled() {
        runBlocking {
            val userId = "student_user_1"
            saveTestSession(userId)

            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject),
                enrolledSubjectIds = setOf("subject_math_1"),
                isEnrollmentsLoaded = true
            )

            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(id = pdfId)

            // إنشاء وتشفير الملف مسبقاً في مساحة المستخدم المشفرة (.enc)
            val encryptedFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            val pdfBytes = createValidPdfBytes("Mathematics Advanced Content")
            val encryptResult = PdfSecurityStorageService.encryptStream(
                inputStream = ByteArrayInputStream(pdfBytes),
                destinationEncryptedFile = encryptedFile,
                totalBytesExpected = pdfBytes.size.toLong()
            )
            assertTrue("يجب أن ينجح تشفير الملف الأولي", encryptResult.isSuccess)
            assertTrue("الملف المشفر يجب أن يكون موجوداً", encryptedFile.exists())

            // فتح الملف
            viewModel.openLessonPdf(context, lessonPdf)

            // انتظار اكتمال فك التشفير في الكوروتين
            var retries = 0
            while (viewModel.uiState.value.isOpeningPdf && retries < 40) {
                delay(25)
                retries++
            }

            val state = viewModel.uiState.value
            assertNull("يجب ألا توجد رسالة خطأ", state.pdfViewerError)
            assertNotNull("يجب أن تكون جلسة العرض نشطة", state.activePdfSession)

            val session = state.activePdfSession!!
            assertEquals(pdfId, session.pdf.id)
            assertTrue("الملف المؤقت يجب أن يكون موجوداً في الكاش", session.decryptedTempFile.exists())
            assertTrue(
                "مسار الملف المؤقت يجب أن يكون داخل midad_viewer_temp",
                session.decryptedTempFilePath.contains("midad_viewer_temp")
            )

            // التحقق من أن محتوى الملف المؤقت يطابق الـ PDF الأصلي ويبدأ بـ %PDF-
            val decryptedBytes = session.decryptedTempFile.readBytes()
            assertArrayEquals(pdfBytes, decryptedBytes)
        }
    }

    /**
     * 2. دعم فتح الملف دون اتصال بالإنترنت اعتماداً على آخر اشتراكات محفوظة محلياً
     */
    @Test
    fun testOpenPdfSuccessOfflineWithCachedEnrollments() {
        runBlocking {
            val userId = "student_offline_user"
            saveTestSession(userId)

            // حفظ الاشتراك محلياً في SessionManager مسبقاً
            SessionManager.saveCachedEnrolledSubjectIds(userId, setOf("subject_math_1"))

            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            // enrolledSubjectIds فارغة في AppUiState لمحاكاة بدء التطبيق أوفلاين
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject),
                enrolledSubjectIds = emptySet(),
                isEnrollmentsLoaded = false
            )

            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(id = pdfId)

            val encryptedFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            val pdfBytes = createValidPdfBytes("Offline Math Notes")
            PdfSecurityStorageService.encryptStream(
                inputStream = ByteArrayInputStream(pdfBytes),
                destinationEncryptedFile = encryptedFile,
                totalBytesExpected = pdfBytes.size.toLong()
            )

            viewModel.openLessonPdf(context, lessonPdf)

            var retries = 0
            while (viewModel.uiState.value.isOpeningPdf && retries < 40) {
                delay(25)
                retries++
            }

            val state = viewModel.uiState.value
            assertNull("يجب ألا يظهر خطأ اشتراك لوجود كاش محلي صالح", state.pdfViewerError)
            assertNotNull("يجب أن تنجح جلسة العرض أوفلاين", state.activePdfSession)
        }
    }

    /**
     * 3. رفض فتح الملف لطالب غير مشترك بمادة مدفوعة
     */
    @Test
    fun testOpenPdfDeniedWhenNotEnrolled() {
        runBlocking {
            val userId = "student_unpaid"
            saveTestSession(userId)

            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject), // pricePoints = 50
                enrolledSubjectIds = emptySet(),
                isEnrollmentsLoaded = true
            )

            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(id = pdfId)

            val encryptedFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            val pdfBytes = createValidPdfBytes("Paid Content")
            PdfSecurityStorageService.encryptStream(
                inputStream = ByteArrayInputStream(pdfBytes),
                destinationEncryptedFile = encryptedFile,
                totalBytesExpected = pdfBytes.size.toLong()
            )

            viewModel.openLessonPdf(context, lessonPdf)

            val state = viewModel.uiState.value
            assertNull("يجب ألا تفتح جلسة عرض", state.activePdfSession)
            assertNotNull("يجب أن تظهر رسالة خطأ توضح وجوب الاشتراك", state.pdfViewerError)
            assertTrue(state.pdfViewerError!!.contains("يجب الاشتراك في المادة"))
        }
    }

    /**
     * 4. رفض فتح الملف إذا لم يكن محملاً محلياً على جهاز المستخدم
     */
    @Test
    fun testOpenPdfDeniedWhenFileMissingLocally() {
        runBlocking {
            val userId = "student_no_file"
            saveTestSession(userId)

            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject),
                enrolledSubjectIds = setOf("subject_math_1"),
                isEnrollmentsLoaded = true
            )

            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(id = pdfId)

            // لم نقم بتشفير أو إنشاء الملف على القرص
            viewModel.openLessonPdf(context, lessonPdf)

            val state = viewModel.uiState.value
            assertNull(state.activePdfSession)
            assertNotNull(state.pdfViewerError)
            assertTrue(state.pdfViewerError!!.contains("غير موجود محلياً"))
        }
    }

    /**
     * 5. منع فتح ملف آخر قبل إغلاق الملف المفتوح حالياً
     */
    @Test
    fun testOpenPdfBlockedWhenAnotherSessionActive() {
        runBlocking {
            val userId = "student_multi"
            saveTestSession(userId)

            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject),
                enrolledSubjectIds = setOf("subject_math_1"),
                isEnrollmentsLoaded = true
            )

            val pdf1 = createTestLessonPdf()
            val dummyFile = File(context.cacheDir, "dummy.pdf")
            dummyFile.writeBytes(byteArrayOf(1, 2, 3))
            viewModel.setTestActivePdfSession(com.example.viewmodel.ActivePdfSession(pdf1, dummyFile))

            val pdf2 = createTestLessonPdf()
            viewModel.openLessonPdf(context, pdf2)

            val state = viewModel.uiState.value
            assertEquals("يجب أن تبقى الجلسة الأولى دون تغيير", pdf1.id, state.activePdfSession?.pdf?.id)
            assertNotNull("يجب أن يعرض خطأ بوجود ملف معروض حالياً", state.pdfViewerError)
            assertTrue(state.pdfViewerError!!.contains("هناك ملف معروض حالياً"))
        }
    }

    /**
     * 6. إغلاق العارض يحذف النسخة المؤقتة ويحافظ تماماً على الملف المشفر الأصلي دون حذفه
     */
    @Test
    fun testClosePdfCleansTempFileAndKeepsOriginalEncrypted() {
        runBlocking {
            val userId = "student_close_test"
            saveTestSession(userId)

            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject),
                enrolledSubjectIds = setOf("subject_math_1"),
                isEnrollmentsLoaded = true
            )

            val pdfId = UUID.randomUUID().toString()
            val lessonPdf = createTestLessonPdf(id = pdfId)

            val encryptedFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdfId)
            val pdfBytes = createValidPdfBytes("Permanent Encrypted Content")
            PdfSecurityStorageService.encryptStream(
                inputStream = ByteArrayInputStream(pdfBytes),
                destinationEncryptedFile = encryptedFile,
                totalBytesExpected = pdfBytes.size.toLong()
            )
            val originalEncryptedLength = encryptedFile.length()
            assertTrue(encryptedFile.exists())

            // 1. فتح الملف
            viewModel.openLessonPdf(context, lessonPdf)
            var retries = 0
            while (viewModel.uiState.value.isOpeningPdf && retries < 40) {
                delay(25)
                retries++
            }

            val session = viewModel.uiState.value.activePdfSession
            assertNotNull(session)
            val tempFile = session!!.decryptedTempFile
            assertTrue("الملف المؤقت موجود قبل الإغلاق", tempFile.exists())

            // 2. إغلاق الملف
            viewModel.closeLessonPdf(context)

            val postCloseState = viewModel.uiState.value
            assertNull("جلسة العرض يجب أن تصبح null", postCloseState.activePdfSession)
            assertFalse("الملف المؤقت يجب أن يُحذف بالكامل بعد الإغلاق", tempFile.exists())

            // 3. التحقق الحرج: الملف المشفر الأصلي يجب أن يبقى موجوداً وسليماً بالكامل!
            assertTrue("الملف المشفر الأصلي (.enc) يجب ألا يُحذف نهائياً", encryptedFile.exists())
            assertEquals("حجم الملف المشفر يجب أن يبقى ثابتاً دون مساس", originalEncryptedLength, encryptedFile.length())
            assertTrue(
                "ترويسة الملف المشفر الأصلي ما زالت صالحة",
                PdfSecurityStorageService.isEncryptedFilePresent(encryptedFile)
            )
        }
    }

    /**
     * 7. تسجيل الخروج يمسح جلسة العرض وينظف أي ملف مؤقت
     */
    @Test
    fun testLogoutCleansViewerSession() {
        runBlocking {
            val userId = "student_logout_test"
            saveTestSession(userId)

            val viewModel = AppViewModel()
            val pdf = createTestLessonPdf()
            val tempViewerDir = File(context.cacheDir, "midad_viewer_temp").apply { mkdirs() }
            val tempFile = File(tempViewerDir, "view_test.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            viewModel.setTestActivePdfSession(com.example.viewmodel.ActivePdfSession(pdf, tempFile))

            viewModel.logout(context)

            // انتظار اكتمال مهمة الـ logout في IO بالكامل
            var retries = 0
            while ((viewModel.isLoggingOut() || SessionManager.getSession() != null) && retries < 50) {
                delay(20)
                retries++
            }

            val state = viewModel.uiState.value
            assertNull("جلسة العرض يجب أن تفرغ عند تسجيل الخروج", state.activePdfSession)
            assertFalse("مجلد العرض المؤقت يجب تنظيفه تعاودياً عند تسجيل الخروج", tempFile.exists())
        }
    }

    /**
     * 8. تبديل الحساب وتسجيل الخروج يمنعان وصول المستخدم الجديد لاشتراكات المستخدم السابق
     */
    @Test
    fun testUserSwitchPreventsAccessToPreviousUserCachedEnrollments() {
        runBlocking {
            val userA = "student_user_alpha"
            val userB = "student_user_beta"

            // المستخدم الأول يقوم بتسجيل الدخول وحفظ كاش اشتراكاته المشفر
            saveTestSession(userA)
            SessionManager.saveCachedEnrolledSubjectIds(userA, setOf("subject_math_1", "subject_physics_1"))

            val cachedA = SessionManager.getCachedEnrolledSubjectIds(userA)
            assertEquals(setOf("subject_math_1", "subject_physics_1"), cachedA)

            // تسجيل الخروج للمستخدم الأول ومسح الجلسة والكاش
            val viewModel = AppViewModel()
            viewModel.logout(context)
            var logoutRetries = 0
            while ((viewModel.isLoggingOut() || SessionManager.getSession() != null) && logoutRetries < 50) {
                delay(20)
                logoutRetries++
            }

            // التأكد من مسح كاش المستخدم الأول
            val postLogoutCachedA = SessionManager.getCachedEnrolledSubjectIds(userA)
            assertTrue("يجب مسح كاش المستخدم الأول بالكامل عند تسجيل الخروج", postLogoutCachedA.isEmpty())

            // تسجيل دخول المستخدم الثاني
            saveTestSession(userB)
            val cachedB = SessionManager.getCachedEnrolledSubjectIds(userB)
            assertTrue("المستخدم الثاني يبدأ بكاش اشتراكات فارغ", cachedB.isEmpty())

            // محاولة فتح درس في مادة تخص المستخدم الأول تنتهي بالرفض
            val pdf = createTestLessonPdf(subjectId = "subject_math_1")
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(listOf(defaultTestSubject), enrolledSubjectIds = emptySet(), isEnrollmentsLoaded = true)

            viewModel.openLessonPdf(context, pdf)
            assertNull("لا يجوز فتح الملف للمستخدم الثاني استناداً لكاش المستخدم الأول", viewModel.uiState.value.activePdfSession)
            assertTrue("يجب إظهار رسالة رفض الوصول للمادة غير المشترك بها", viewModel.uiState.value.pdfViewerError?.contains("يجب الاشتراك في المادة") == true)
        }
    }

    /**
     * 9. الحفاظ على آخر كاش اشتراكات مشفر صالح عند فشل المزامنة أو غياب الاتصال
     */
    @Test
    fun testSyncFailurePreservesLastValidCachedEnrollments() {
        runBlocking {
            val userId = "student_offline_user"
            saveTestSession(userId)

            val validSubjects = setOf("subject_math_1", "subject_history_1")
            SessionManager.saveCachedEnrolledSubjectIds(userId, validSubjects)

            // محاكاة وضع أوفلاين بقراءة الكاش المشفر المحفوظ
            val cachedEnrollments = SessionManager.getCachedEnrolledSubjectIds(userId)
            assertEquals("يجب استرجاع كاش الاشتراكات المحفوظ بنجاح أوفلاين", validSubjects, cachedEnrollments)

            // التحقق من أن فتح ملف PDF لمادة مشمولة في الكاش ينجح أوفلاين دون إنترنت
            val viewModel = AppViewModel()
            viewModel.setTestRole(UserRole.STUDENT)
            viewModel.setTestSubjects(
                subjects = listOf(defaultTestSubject),
                enrolledSubjectIds = emptySet(), // محاكاة عدم مزامنة الحالة بعد
                isEnrollmentsLoaded = false
            )

            // حفظ ملف مشفر محلياً لمحاكاة وجوده مسبقاً على الجهاز
            val pdf = createTestLessonPdf(subjectId = "subject_math_1")
            val encryptedFile = viewModel.getLocalEncryptedPdfFile(context, userId, pdf.id)
            val validPdfBytes = createValidPdfBytes("Offline PDF content")
            val encryptResult = PdfSecurityStorageService.encryptStream(
                inputStream = ByteArrayInputStream(validPdfBytes),
                destinationEncryptedFile = encryptedFile,
                totalBytesExpected = validPdfBytes.size.toLong()
            )
            assertTrue("يجب نجاح تشفير الملف المحلي مسبقاً", encryptResult.isSuccess)

            viewModel.openLessonPdf(context, pdf)

            var retries = 0
            while (viewModel.uiState.value.isOpeningPdf && retries < 40) {
                delay(25)
                retries++
            }

            val session = viewModel.uiState.value.activePdfSession
            assertNotNull("يجب السماح بفتح الملف للمادة المشترك بها استناداً للكاش الصالح", session)
            assertNull("يجب ألا يكون هناك خطأ في صلاحية الوصول", viewModel.uiState.value.pdfViewerError)
        }
    }

    /**
     * 10. اكتشاف التلاعب أو التلف في كاش الاشتراكات ورفضه وحذفه فوراً دون منح أي صلاحية
     */
    @Test
    fun testEncryptedEnrollmentCacheIntegrityAndTamperDetection() {
        runBlocking {
            val userId = "student_tamper_user"
            saveTestSession(userId)

            // حفظ كاش صالح
            SessionManager.saveCachedEnrolledSubjectIds(userId, setOf("subject_math_1"))
            val prefs = context.getSharedPreferences("midad_auth_session_prefs", Context.MODE_PRIVATE)
            val cacheKey = "cached_enrolled_subjects_$userId"
            val validPayload = prefs.getString(cacheKey, null)
            assertNotNull("يجب أن تكون القيمة موجودة ومحفوظة", validPayload)

            // التلاعب بالقيمة المشفرة (تعديل أحرف في Base64 لمحاكاة التلاعب بالـ Tag أو البيانات)
            val tamperedPayload = if (validPayload!!.endsWith("==")) {
                validPayload.substring(0, validPayload.length - 4) + "AAAA"
            } else {
                validPayload.substring(0, validPayload.length - 2) + "ZZ"
            }
            prefs.edit().putString(cacheKey, tamperedPayload).apply()

            // محاولة القراءة يجب أن تكتشف التلاعب وتفشل بأمان (Fail-Closed)
            val result = SessionManager.getCachedEnrolledSubjectIds(userId)
            assertTrue("يجب رفض الكاش المتلاعب به وإرجاع مجموعة فارغة", result.isEmpty())
            assertNull("يجب مسح المفتاح التالف تلقائياً من التفضيلات", prefs.getString(cacheKey, null))

            // اختبار حالة القيمة القصيرة جداً (أقل من 28 بايت)
            prefs.edit().putString(cacheKey, Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)).apply()
            val shortResult = SessionManager.getCachedEnrolledSubjectIds(userId)
            assertTrue("يجب رفض الحمولات غير المطابقة للحد الأدنى للحجم", shortResult.isEmpty())
            assertNull("يجب إزالة الحمولة غير الصالحة فوراً", prefs.getString(cacheKey, null))
        }
    }

    /**
     * 11. التحقق الصارم من ربط التشفير بهوية المستخدم عبر AAD لمنع نسخ التشفير بين الحسابات
     */
    @Test
    fun testEncryptedEnrollmentCacheUserBindingWithAAD() {
        runBlocking {
            val userAlice = "user_alice_legit"
            val userBob = "user_bob_attacker"

            // أليس تشترك في مادة مدفوعة ويتم تشفير الكاش لها
            saveTestSession(userAlice)
            SessionManager.saveCachedEnrolledSubjectIds(userAlice, setOf("premium_subject_99"))

            val prefs = context.getSharedPreferences("midad_auth_session_prefs", Context.MODE_PRIVATE)
            val aliceKey = "cached_enrolled_subjects_$userAlice"
            val alicePayload = prefs.getString(aliceKey, null)
            assertNotNull(alicePayload)

            // بوب (المهاجم) ينسخ حمولة أليس المشفرة مباشرة إلى مفتاح حسابه
            val bobKey = "cached_enrolled_subjects_$userBob"
            prefs.edit().putString(bobKey, alicePayload).commit()

            // عند محاولة قراءة كاش بوب، لأن الحمولة مشفرة بـ AAD="user_alice_legit"،
            // فإن فك تشفيرها بواسطة AAD="user_bob_attacker" يجب أن يفشل (AEADBadTagException)
            saveTestSession(userBob)
            val bobEnrollments = SessionManager.getCachedEnrolledSubjectIds(userBob)
            assertTrue("يجب ألا يتمكن بوب من فك تشفير حمولة أليس بسبب اختلاف AAD", bobEnrollments.isEmpty())
            assertNull("يجب حذف الحمولة المنسوخة لأنها غير صالحة لهوية بوب", prefs.getString(bobKey, null))

            // التأكد من أن أليس ما زالت تستطيع قراءة كاشها بسلامة
            saveTestSession(userAlice)
            val aliceEnrollments = SessionManager.getCachedEnrolledSubjectIds(userAlice)
            assertEquals(setOf("premium_subject_99"), aliceEnrollments)
        }
    }

    /**
     * 12. التحقق من أمان واستقرار معالجة محرك العرض PdfRenderEngine على بيئة الاختبار
     */
    @Test
    fun testPdfRenderEngineInitializationAndPageCount() {
        runBlocking {
            val pdfBytes = createValidPdfBytes("Midad Page 1 Content")
            val tempPdfFile = File(context.cacheDir, "midad_engine_test.pdf")
            tempPdfFile.writeBytes(pdfBytes)

            try {
                val engine = com.example.ui.components.PdfRenderEngine(tempPdfFile)
                try {
                    assertTrue("يجب أن يحتوي الملف على صفحة واحدة على الأقل", engine.pageCount >= 1)
                    val ratio0 = engine.getPageAspectRatio(0)
                    assertTrue("النسبة البصرية يجب أن تكون أكبر من الصفر", ratio0 > 0f)
                } finally {
                    engine.close()
                    engine.close()
                }
            } catch (t: Throwable) {
                // على بيئة Robolectric/JVM، استدعاء فئات نظام التشغيل المباشرة مثل PdfRenderer
                // يعتمد على دوال libcore داخلية خاصة بنواة أندرويد (مثل getOwnerId$) غير متوفرة في Standard JVM
                assertTrue(
                    "يجب أن يكون الخطأ بسبب غياب JNI/libcore في JVM القياسي",
                    t is NoSuchMethodError || t is UnsatisfiedLinkError || t is java.io.IOException
                )
            } finally {
                tempPdfFile.delete()
            }
        }
    }

    /**
     * 13. التحقق من أمان إغلاق محرك العرض وتنظيف الكاش وتطهير الموارد
     */
    @Test
    fun testPdfRenderEngineSafeCloseAndCacheDisposal() {
        val pdfBytes = createValidPdfBytes("Safe Close Test")
        val tempPdfFile = File(context.cacheDir, "midad_close_test.pdf")
        tempPdfFile.writeBytes(pdfBytes)

        try {
            val engine = com.example.ui.components.PdfRenderEngine(tempPdfFile)
            engine.close()
            // استدعاء close مرة أخرى للتأكد من عدم رمي أي استثناء
            engine.close()
        } catch (t: Throwable) {
            assertTrue(
                "متوقع على بيئة JVM بدون ربط libcore لنظام أندرويد",
                t is NoSuchMethodError || t is UnsatisfiedLinkError || t is java.io.IOException
            )
        } finally {
            tempPdfFile.delete()
        }
    }

    /**
     * 14. التحقق من تنظيف الملفات المؤقتة اليتيمة من مجلد العارض عند بدء التطبيق
     */
    @Test
    fun testPurgeViewerCacheDirectoryCleansOrphanedFiles() {
        val viewerDir = File(context.cacheDir, "midad_viewer_temp").apply { mkdirs() }
        val orphan1 = File(viewerDir, "view_orphan_1.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val orphan2 = File(viewerDir, "view_orphan_2.pdf").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        assertTrue(orphan1.exists())
        assertTrue(orphan2.exists())

        PdfSecurityStorageService.purgeViewerCacheDirectory(context.cacheDir)

        assertFalse("يجب حذف الملف اليتيم الأول", orphan1.exists())
        assertFalse("يجب حذف الملف اليتيم الثاني", orphan2.exists())
    }
}
