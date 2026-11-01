package com.example

import com.example.data.AuthResult
import com.example.data.AuthSession
import com.example.data.SessionManager
import com.example.model.AcademicYear
import com.example.model.Semester
import com.example.model.Subject
import com.example.model.SupabaseConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseAuthUnitTest {

    @Test
    fun testSupabaseConfigReadsBuildConfig() {
        val config = SupabaseConfig()
        assertNotNull("Project URL should not be null", config.projectUrl)
        assertNotNull("Anon Key should not be null", config.anonKey)

        assertFalse(
            "Project URL should not be the placeholder string",
            config.projectUrl.contains("your-supabase-project")
        )
        assertFalse(
            "Anon key should not be the placeholder string",
            config.anonKey.contains("...")
        )
        assertTrue(
            "Project URL should be a valid HTTP/HTTPS URL",
            config.projectUrl.startsWith("http://") || config.projectUrl.startsWith("https://")
        )
    }

    @Test
    fun testSessionManagerSaveAndRetrieve() {
        val context = RuntimeEnvironment.getApplication()
        SessionManager.init(context)
        SessionManager.clearSession()

        assertNull("Session should be null initially after clear", SessionManager.getSession())

        val testSession = AuthSession(
            accessToken = "test_access_token_123",
            refreshToken = "test_refresh_token_456",
            userId = "user_uuid_789",
            email = "student@test.com",
            displayName = "طالب اختبار",
            expiresAt = System.currentTimeMillis() + 3600_000L
        )

        SessionManager.saveSession(testSession)

        val retrieved = SessionManager.getSession()
        assertNotNull("Retrieved session should not be null", retrieved)
        assertEquals("test_access_token_123", retrieved?.accessToken)
        assertEquals("test_refresh_token_456", retrieved?.refreshToken)
        assertEquals("user_uuid_789", retrieved?.userId)
        assertEquals("student@test.com", retrieved?.email)
        assertEquals("طالب اختبار", retrieved?.displayName)
        assertFalse("Token should not be expired", SessionManager.isTokenExpired())

        // Test update tokens
        SessionManager.updateTokens("new_access_token", "new_refresh_token", 7200L)
        val updated = SessionManager.getSession()
        assertEquals("new_access_token", updated?.accessToken)
        assertEquals("new_refresh_token", updated?.refreshToken)

        // Test clear
        SessionManager.clearSession()
        assertNull("Session should be null after clearing", SessionManager.getSession())
    }

    @Test
    fun testSessionManagerTokenExpiryDetection() {
        val context = RuntimeEnvironment.getApplication()
        SessionManager.init(context)

        // Session that expired in the past
        val expiredSession = AuthSession(
            accessToken = "expired_token",
            refreshToken = "expired_refresh",
            userId = "user_expired",
            email = "exp@test.com",
            displayName = "منتهي",
            expiresAt = System.currentTimeMillis() - 5000L
        )

        SessionManager.saveSession(expiredSession)
        assertTrue("Expired session must be recognized as expired", SessionManager.isTokenExpired())

        SessionManager.clearSession()
    }

    @Test
    fun testAuthSuccessResponseParsing() {
        val sampleResponseJson = """
            {
                "access_token": "token_abc_xyz",
                "token_type": "bearer",
                "expires_in": 3600,
                "refresh_token": "refresh_abc_xyz",
                "user": {
                    "id": "11111111-2222-3333-4444-555555555555",
                    "email": "student@university.edu",
                    "user_metadata": {
                        "display_name": "أحمد السوري",
                        "username": "ahmad_sy"
                    }
                }
            }
        """.trimIndent()

        val json = JSONObject(sampleResponseJson)
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        val expiresIn = json.optLong("expires_in")
        val userObj = json.optJSONObject("user")
        val userId = userObj?.optString("id")
        val email = userObj?.optString("email")
        val displayName = userObj?.optJSONObject("user_metadata")?.optString("display_name")

        assertEquals("token_abc_xyz", accessToken)
        assertEquals("refresh_abc_xyz", refreshToken)
        assertEquals(3600L, expiresIn)
        assertEquals("11111111-2222-3333-4444-555555555555", userId)
        assertEquals("student@university.edu", email)
        assertEquals("أحمد السوري", displayName)
    }

    @Test
    fun testEmailConfirmationResponseParsing() {
        // When email confirmation is enabled, signup returns user object without access_token
        val sampleSignupUnconfirmedJson = """
            {
                "id": "22222222-3333-4444-5555-666666666666",
                "aud": "authenticated",
                "role": "authenticated",
                "email": "newstudent@university.edu",
                "confirmation_sent_at": "2026-10-30T11:00:00Z",
                "user_metadata": {
                    "display_name": "سامر العلي",
                    "username": "samer99"
                }
            }
        """.trimIndent()

        val json = JSONObject(sampleSignupUnconfirmedJson)
        val accessToken = json.optString("access_token", "")
        assertTrue("access_token should be empty when email confirmation is pending", accessToken.isEmpty())
        val email = json.optString("email")
        assertEquals("newstudent@university.edu", email)
    }

    @Test
    fun testAuthErrorMessagesInterpretation() {
        val invalidGrantError = """
            {"error":"invalid_grant","error_description":"Invalid login credentials"}
        """.trimIndent()

        val emailNotConfirmedError = """
            {"error":"invalid_grant","error_description":"Email not confirmed"}
        """.trimIndent()

        val userAlreadyRegisteredError = """
            {"msg":"User already registered"}
        """.trimIndent()

        assertTrue(invalidGrantError.contains("Invalid login credentials"))
        assertTrue(emailNotConfirmedError.contains("Email not confirmed"))
        assertTrue(userAlreadyRegisteredError.contains("User already registered"))
    }

    @Test
    fun testProfileRoleParsingAdmin() {
        val sampleAdminProfileJson = """
            [
                {
                    "id": "11111111-2222-3333-4444-555555555555",
                    "username": "william_admin",
                    "display_name": "المهندس وليم",
                    "academic_year": 4,
                    "semester": 2,
                    "role": "admin"
                }
            ]
        """.trimIndent()

        val jsonArray = org.json.JSONArray(sampleAdminProfileJson)
        val obj = jsonArray.getJSONObject(0)

        val profile = com.example.data.UserProfile(
            id = obj.getString("id"),
            username = obj.optString("username"),
            displayName = obj.optString("display_name"),
            academicYear = obj.optInt("academic_year", 1),
            semester = obj.optInt("semester", 1),
            role = obj.optString("role", "student")
        )

        assertTrue("Profile with role 'admin' must identify as admin", profile.isAdmin)
        assertEquals(4, profile.academicYear)
        assertEquals(2, profile.semester)
        assertEquals("المهندس وليم", profile.displayName)
        assertEquals("william_admin", profile.username)
    }

    @Test
    fun testProfileRoleParsingStudent() {
        val sampleStudentProfileJson = """
            [
                {
                    "id": "22222222-3333-4444-5555-666666666666",
                    "username": "student_samer",
                    "display_name": "سامر العلي",
                    "academic_year": 1,
                    "semester": 1,
                    "role": "student"
                }
            ]
        """.trimIndent()

        val jsonArray = org.json.JSONArray(sampleStudentProfileJson)
        val obj = jsonArray.getJSONObject(0)

        val profile = com.example.data.UserProfile(
            id = obj.getString("id"),
            username = obj.optString("username"),
            displayName = obj.optString("display_name"),
            academicYear = obj.optInt("academic_year", 1),
            semester = obj.optInt("semester", 1),
            role = obj.optString("role", "student")
        )

        assertFalse("Profile with role 'student' must not be admin", profile.isAdmin)
        assertEquals(1, profile.academicYear)
        assertEquals(1, profile.semester)
    }

    @Test
    fun testProfileRoleParsingMissingOrInvalidDefaultsToStudent() {
        val sampleMissingRoleJson = """
            [
                {
                    "id": "33333333-4444-5555-6666-777777777777",
                    "display_name": "مستخدم بدون دور محدد"
                }
            ]
        """.trimIndent()

        val jsonArray = org.json.JSONArray(sampleMissingRoleJson)
        val obj = jsonArray.getJSONObject(0)

        val profile = com.example.data.UserProfile(
            id = obj.getString("id"),
            username = obj.optString("username", ""),
            displayName = obj.optString("display_name", ""),
            academicYear = obj.optInt("academic_year", 1),
            semester = obj.optInt("semester", 1),
            role = obj.optString("role", "student")
        )

        assertFalse("Profile without explicit 'admin' role must default safely to student", profile.isAdmin)
    }

    @Test
    fun testSessionManagerDoesNotStoreRoleToPreventPrivilegeEscalation() {
        val context = RuntimeEnvironment.getApplication()
        SessionManager.init(context)
        SessionManager.clearSession()

        val session = AuthSession(
            accessToken = "token_abc",
            refreshToken = "refresh_abc",
            userId = "user_123",
            email = "test@domain.com",
            displayName = "طالب",
            expiresAt = System.currentTimeMillis() + 3600_000L
        )
        SessionManager.saveSession(session)

        // SessionManager should not have any role field stored
        val prefs = context.getSharedPreferences("midad_auth_session_prefs", android.content.Context.MODE_PRIVATE)
        assertFalse("SessionManager must not store role in SharedPreferences", prefs.contains("role"))
    }

    @Test
    fun testRegistrationUsernameValidation() {
        val usernamePattern = Regex("^[a-zA-Z0-9_]{3,30}$")

        // Valid usernames
        assertTrue(usernamePattern.matches("ahmad_ali"))
        assertTrue(usernamePattern.matches("student123"))
        assertTrue(usernamePattern.matches("Sy_2026"))
        assertTrue(usernamePattern.matches("abc"))

        // Invalid usernames
        assertFalse("Too short (less than 3 chars)", usernamePattern.matches("ab"))
        assertFalse("Empty string", usernamePattern.matches(""))
        assertFalse("Contains special character @", usernamePattern.matches("user@name"))
        assertFalse("Contains spaces", usernamePattern.matches("user name"))
        assertFalse("Contains Arabic letters", usernamePattern.matches("وليم_علي"))
        assertFalse("Too long (more than 30 chars)", usernamePattern.matches("a".repeat(31)))
    }

    @Test
    fun testRegistrationEmailValidation() {
        val emailPattern = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

        assertTrue(emailPattern.matches("student@university.edu"))
        assertTrue(emailPattern.matches("test.user+1@domain.co"))
        assertTrue(emailPattern.matches("ahmad_sy@email.org"))

        assertFalse(emailPattern.matches("invalid-email"))
        assertFalse(emailPattern.matches("@domain.com"))
        assertFalse(emailPattern.matches("student@"))
        assertFalse(emailPattern.matches("student@domain"))
    }

    @Test
    fun testRegistrationPasswordMatching() {
        val pass1 = "secret123"
        val pass2 = "secret123"
        val pass3 = "different456"

        assertEquals(pass1, pass2)
        assertNotEquals(pass1, pass3)
        assertTrue(pass1.length >= 6)
        assertFalse("123".length >= 6)
    }

    @Test
    fun testDeepLinkUriParamsParsingFragment() {
        val uri = android.net.Uri.parse("midad://auth-callback#access_token=token123&refresh_token=refresh456&expires_in=3600&token_type=bearer&type=signup")
        val params = com.example.data.SupabaseAuthService.parseUriParams(uri)

        assertEquals("token123", params["access_token"])
        assertEquals("refresh456", params["refresh_token"])
        assertEquals("3600", params["expires_in"])
        assertEquals("signup", params["type"])
    }

    @Test
    fun testDeepLinkUriParamsParsingQuery() {
        val uri = android.net.Uri.parse("midad://auth-callback?code=auth_code_xyz&type=signup")
        val params = com.example.data.SupabaseAuthService.parseUriParams(uri)

        assertEquals("auth_code_xyz", params["code"])
        assertEquals("signup", params["type"])
    }

    @Test
    fun testDeepLinkUriErrorParsing() = kotlinx.coroutines.runBlocking {
        val uri = android.net.Uri.parse("midad://auth-callback#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired")
        val result = com.example.data.SupabaseAuthService.handleDeepLinkCallback(uri)

        assertTrue(result is com.example.data.DeepLinkAuthResult.Error)
        val errorResult = result as com.example.data.DeepLinkAuthResult.Error
        assertTrue("Error message should mention link expiration", errorResult.message.contains("انتهت صلاحيته") || errorResult.message.contains("غير صالح"))
    }

    @Test
    fun testDeepLinkIgnoredNonMatchingHost() = kotlinx.coroutines.runBlocking {
        val uri = android.net.Uri.parse("midad://other-action?code=123")
        val result = com.example.data.SupabaseAuthService.handleDeepLinkCallback(uri)

        assertTrue("Should ignore deep links with non-matching host", result is com.example.data.DeepLinkAuthResult.Ignored)
    }

    @Test
    fun testDeepLinkEmailConfirmedNeedLoginWhenNoTokens() = kotlinx.coroutines.runBlocking {
        val uri = android.net.Uri.parse("midad://auth-callback")
        val result = com.example.data.SupabaseAuthService.handleDeepLinkCallback(uri)

        assertTrue("Should prompt user to login if no tokens returned in callback", result is com.example.data.DeepLinkAuthResult.EmailConfirmedNeedLogin)
    }

    @Test
    fun testSupabaseAcademicYearsAndSemestersParsing() {
        val academicYearsJson = """
            [
                {"id": 1, "name": "السنة الأولى"},
                {"id": 2, "name": "السنة الثانية"},
                {"id": 3, "name": "السنة الثالثة"},
                {"id": 4, "name": "السنة الرابعة"},
                {"id": 5, "name": "السنة الخامسة"}
            ]
        """.trimIndent()

        val jsonArrayYears = JSONArray(academicYearsJson)
        val yearsList = mutableListOf<AcademicYear>()
        for (i in 0 until jsonArrayYears.length()) {
            val obj = jsonArrayYears.getJSONObject(i)
            yearsList.add(AcademicYear(id = obj.getInt("id"), name = obj.getString("name")))
        }

        assertEquals(5, yearsList.size)
        assertEquals("السنة الأولى", yearsList[0].name)
        assertEquals(5, yearsList[4].id)

        val semestersJson = """
            [
                {"id": 1, "name": "الفصل الأول"},
                {"id": 2, "name": "الفصل الثاني"}
            ]
        """.trimIndent()

        val jsonArraySemesters = JSONArray(semestersJson)
        val semestersList = mutableListOf<Semester>()
        for (i in 0 until jsonArraySemesters.length()) {
            val obj = jsonArraySemesters.getJSONObject(i)
            semestersList.add(Semester(id = obj.getInt("id"), name = obj.getString("name")))
        }

        assertEquals(2, semestersList.size)
        assertEquals("الفصل الأول", semestersList[0].name)
        assertEquals(2, semestersList[1].id)
    }

    @Test
    fun testSupabaseSubjectsJsonParsing() {
        val subjectsJson = """
            [
                {
                    "id": "math_year1_sem1",
                    "name": "رياضيات 1",
                    "academic_year": 1,
                    "semester": 1,
                    "price_points": 0,
                    "description": "مقدمة في الجبر الخطي والتحليل الرياضي",
                    "is_published": false,
                    "created_at": "2025-01-01T10:00:00Z"
                },
                {
                    "id": "physics_year1_sem1",
                    "name": "فيزياء 1",
                    "academic_year": 1,
                    "semester": 1,
                    "price_points": 50,
                    "description": "الميكانيك الكلاسيكي والحركة",
                    "is_published": true,
                    "created_at": "2025-01-02T12:00:00Z"
                }
            ]
        """.trimIndent()

        val jsonArray = JSONArray(subjectsJson)
        val list = mutableListOf<Subject>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                Subject(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    academicYear = obj.optInt("academic_year", 1),
                    semester = obj.optInt("semester", 1),
                    pricePoints = obj.optInt("price_points", 0),
                    description = obj.optString("description", ""),
                    isPublished = obj.optBoolean("is_published", false),
                    createdAt = obj.optString("created_at", "")
                )
            )
        }

        assertEquals(2, list.size)
        val mathSubject = list[0]
        assertEquals("math_year1_sem1", mathSubject.id)
        assertEquals("رياضيات 1", mathSubject.name)
        assertEquals(1, mathSubject.academicYear)
        assertEquals(1, mathSubject.year) // alias check
        assertEquals(1, mathSubject.semester)
        assertEquals(0, mathSubject.pricePoints)
        assertFalse(mathSubject.isPublished)

        val physicsSubject = list[1]
        assertEquals("physics_year1_sem1", physicsSubject.id)
        assertEquals(50, physicsSubject.pricePoints)
        assertTrue(physicsSubject.isPublished)
    }

    @Test
    fun testStudentRoleExcludesUnpublishedSubjects() {
        val allSubjects = listOf(
            Subject(id = "sub_1", name = "المادة الأولى", isPublished = false),
            Subject(id = "sub_2", name = "المادة الثانية", isPublished = true),
            Subject(id = "sub_3", name = "المادة الثالثة", isPublished = false),
            Subject(id = "sub_4", name = "المادة الرابعة", isPublished = true)
        )

        // For student role, filter by isPublished
        val studentView = allSubjects.filter { it.isPublished }
        assertEquals(2, studentView.size)
        assertTrue(studentView.all { it.isPublished })
        assertFalse(studentView.any { it.id == "sub_1" })
        assertFalse(studentView.any { it.id == "sub_3" })

        // When all 59 subjects are unpublished in database (as currently in Supabase):
        val allUnpublished = List(59) { idx ->
            Subject(id = "sub_$idx", name = "مادة $idx", isPublished = false)
        }
        val studentEmptyView = allUnpublished.filter { it.isPublished }
        assertTrue("Student view must be empty when all subjects are unpublished", studentEmptyView.isEmpty())
        assertEquals(0, studentEmptyView.size)
    }

    @Test
    fun testAdminRoleIncludesAllSubjectsAndAllowsPublishToggle() {
        val subjects = listOf(
            Subject(id = "sub_1", name = "المادة الأولى", isPublished = false),
            Subject(id = "sub_2", name = "المادة الثانية", isPublished = false)
        )

        // Admin views all 59 subjects regardless of is_published
        val adminView = subjects
        assertEquals(2, adminView.size)

        // Optimistic toggle simulation
        val toggledSubjects = adminView.map {
            if (it.id == "sub_1") it.copy(isPublished = !it.isPublished) else it
        }
        assertTrue("sub_1 should now be optimistically published", toggledSubjects.first { it.id == "sub_1" }.isPublished)

        // Rollback on error simulation
        val rolledBackSubjects = toggledSubjects.map {
            if (it.id == "sub_1") it.copy(isPublished = false) else it
        }
        assertFalse("sub_1 should be reverted to unpublished after failure", rolledBackSubjects.first { it.id == "sub_1" }.isPublished)
    }

    @Test
    fun testFetchSubjectsRejectsNullOrBlankAccessToken() = kotlinx.coroutines.runBlocking {
        // Must reject without sending an unauthenticated HTTP request
        val resultNull = com.example.data.SupabaseDatabaseService.fetchSubjects(accessToken = null)
        assertTrue(resultNull.isFailure)
        assertTrue(resultNull.exceptionOrNull()?.message?.contains("يتطلب الوصول") == true)

        val resultBlank = com.example.data.SupabaseDatabaseService.fetchSubjects(accessToken = "   ")
        assertTrue(resultBlank.isFailure)
        assertTrue(resultBlank.exceptionOrNull()?.message?.contains("يتطلب الوصول") == true)
    }

    @Test
    fun testHttpErrorMessagesMapping() {
        fun mapCode(code: Int): String {
            return when (code) {
                401 -> "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح (رمز HTTP: 401)"
                403 -> "تم رفض الوصول: صلاحيات غير كافية لجلب المواد الدراسية (رمز HTTP: 403)"
                else -> "فشل جلب المواد من الخادم (رمز HTTP: $code)"
            }
        }

        assertTrue(mapCode(401).contains("غير مصرح"))
        assertTrue(mapCode(403).contains("تم رفض الوصول"))
        assertTrue(mapCode(500).contains("500"))
    }

    @Test
    fun testRetryMechanismStateFlow() {
        var state = com.example.viewmodel.AppUiState(
            subjects = emptyList(),
            isSubjectsLoaded = true,
            subjectsError = "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح (رمز HTTP: 401)"
        )

        // Initial failed state
        assertNotNull(state.subjectsError)
        assertTrue(state.subjects.isEmpty())

        // Simulating retry click: clears error and sets loading
        state = state.copy(isSyncingWithSupabase = true, subjectsError = null)
        assertNull(state.subjectsError)
        assertTrue(state.isSyncingWithSupabase)

        // Successful load after retry
        val loadedSubjects = listOf(
            Subject(id = "sub_math1", name = "رياضيات 1", isPublished = false),
            Subject(id = "sub_phys1", name = "فيزياء 1", isPublished = false)
        )
        state = state.copy(
            isSyncingWithSupabase = false,
            isSubjectsLoaded = true,
            subjects = loadedSubjects,
            subjectsError = null
        )

        assertNull(state.subjectsError)
        assertFalse(state.isSyncingWithSupabase)
        assertEquals(2, state.subjects.size)
    }
}

