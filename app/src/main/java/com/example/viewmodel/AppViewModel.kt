package com.example.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AuthResult
import com.example.data.DeepLinkAuthResult
import com.example.data.SessionManager
import com.example.data.SupabaseAuthService
import com.example.data.SupabaseDatabaseService
import com.example.data.SupabaseStorageService
import com.example.model.AcademicYear
import com.example.model.CourseChapter
import com.example.model.CourseLesson
import com.example.model.CourseUnit
import com.example.model.CurriculumData
import com.example.model.LessonPdf
import com.example.model.Semester
import com.example.model.Subject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.data.PdfSecurityStorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class UserRole {
    ADMIN,
    STUDENT
}

/**
 * حالة ملف الـ PDF المشفر على جهاز المستخدم (المرحلة 2-B3-B2-2B)
 */
sealed class LocalPdfStatus {
    object NotDownloaded : LocalPdfStatus()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercentage: Int
    ) : LocalPdfStatus()
    data class Downloaded(
        val fileSizeBytes: Long
    ) : LocalPdfStatus()
    data class Error(
        val message: String
    ) : LocalPdfStatus()
}

data class YearAddedStat(
    val year: Int,
    val count: Int
)

data class AppUiState(
    val isLoggedIn: Boolean = false,
    val isLoggingIn: Boolean = false,
    val username: String = "",
    val displayName: String = "",
    val role: UserRole = UserRole.STUDENT,
    val localPdfStatuses: Map<String, LocalPdfStatus> = emptyMap(),
    val isDarkMode: Boolean = true,
    val currentTab: Int = 0, // 0: الرئيسية, 1: موادي, 2: تصفح المواد, 3: ملفي الشخصي
    val academicYear: Int = 1,
    val semester: Int = 1,
    val enrolledSubjectIds: Set<String> = emptySet(), // Starts empty, populated when subjects are enrolled/published
    val completedTopicIds: Set<String> = emptySet(), // Starts empty, no fake mock completions
    val selectedSubjectId: String? = null, // Opened subject for detail view
    val searchQuery: String = "",
    val filterYear: Int? = null,
    val filterSemester: Int? = null,
    val loginError: String? = null,
    // Real Supabase data
    val academicYears: List<AcademicYear> = emptyList(),
    val semesters: List<Semester> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val isSubjectsLoaded: Boolean = false,
    val subjectsError: String? = null,
    // Added subjects published for students by admin - tracks is_published
    val addedSubjectIds: Set<String> = emptySet(),
    // Custom topics and uploaded files created by Admin per subject
    val customTopicsBySubject: Map<String, List<com.example.model.AdminTopic>> = emptyMap(),
    // Supabase storage & database credentials/config
    val supabaseConfig: com.example.model.SupabaseConfig = com.example.model.SupabaseConfig(),
    val isSyncingWithSupabase: Boolean = false,
    val supabaseSyncStatusMessage: String? = null,
    // Real Course Hierarchy PDFs (Phase 2-B3)
    val lessonPdfsByLesson: Map<String, List<LessonPdf>> = emptyMap(),
    val loadingPdfLessonIds: Set<String> = emptySet(),
    val isPdfOperating: Boolean = false,
    val pdfOperationMessage: String? = null,
    val pdfOperationError: String? = null,
    val orphanPdfRecordIdsPendingDbDelete: Set<String> = emptySet(),
    // Real Educational Hierarchy (Phase 2-B3-B1: الوحدات والفصول والدروس)
    val unitsBySubject: Map<String, List<CourseUnit>> = emptyMap(),
    val chaptersByUnit: Map<String, List<CourseChapter>> = emptyMap(),
    val lessonsByChapter: Map<String, List<CourseLesson>> = emptyMap(),
    val directLessonsByUnit: Map<String, List<CourseLesson>> = emptyMap(),
    val fullyLoadedChapterLessonIds: Set<String> = emptySet(),
    val fullyLoadedDirectLessonUnitIds: Set<String> = emptySet(),
    val loadingHierarchyIds: Set<String> = emptySet(),
    val isHierarchyOperating: Boolean = false,
    val hierarchyOperationMessage: String? = null,
    val hierarchyOperationError: String? = null
) {
    // Total topics count belonging ONLY to purchased/enrolled subjects (from real topics added by admin)
    val purchasedTotalTopicsCount: Int
        get() = enrolledSubjectIds.sumOf { (customTopicsBySubject[it]?.size ?: 0) }

    // Completed topics count belonging ONLY to purchased/enrolled subjects
    val purchasedCompletedTopicsCount: Int
        get() = enrolledSubjectIds.sumOf { subId ->
            val subTopics = customTopicsBySubject[subId].orEmpty()
            subTopics.count { completedTopicIds.contains(it.id) }
        }

    // Progress percentage of purchased subjects
    val purchasedProgressPercentage: Int
        get() = if (purchasedTotalTopicsCount > 0) {
            (purchasedCompletedTopicsCount * 100) / purchasedTotalTopicsCount
        } else 0

    // Admin Stats: Based strictly on real Supabase subjects data
    val totalSubjectsCount: Int
        get() = if (subjects.isNotEmpty()) subjects.size else 0

    val addedSubjectsCount: Int
        get() = subjects.count { it.isPublished }

    val addedCompletionPercentage: Int
        get() = if (totalSubjectsCount > 0) {
            (addedSubjectsCount * 100) / totalSubjectsCount
        } else 0

    // Academic Year with the lowest number of added subjects
    val yearWithLowestAddedSubjects: YearAddedStat
        get() {
            val stats = (1..5).map { yr ->
                val count = subjects.count { it.academicYear == yr && it.isPublished }
                YearAddedStat(yr, count)
            }
            return stats.minByOrNull { it.count } ?: YearAddedStat(1, 0)
        }
}

class AppViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    // قفل ذري لمنع تداخل عمليات تعديل ملفات الـ PDF (الرفع والحذف وإعادة المحاولة)
    private val isPdfOperationLock = AtomicBoolean(false)
    // قفل ذري لمنع تداخل عمليات تعديل الهيكلية التعليمية (الوحدات والفصول والدروس)
    private val isHierarchyOperationLock = AtomicBoolean(false)

    // تتبع عمليات النقل والإضافة المحلية للدروس لحمايتها من استجابات التحميل غير المكتملة أو القديمة
    private data class RecentLessonTransfer(
        val lesson: CourseLesson,
        val targetChapterId: String?,
        val targetUnitId: String,
        val timestamp: Long
    )
    private val recentTransfers = ConcurrentHashMap<String, RecentLessonTransfer>()

    init {
        // بدء استعادة الجلسة أولاً، وتأجيل المزامنة حتى اكتمال التحقق من الجلسة وتحديد الدور
        restoreOrRefreshSession()
    }

    fun restoreOrRefreshSession() {
        val session = SessionManager.getSession()
        if (session == null || session.accessToken.isBlank()) {
            _uiState.update { it.copy(isSubjectsLoaded = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingWithSupabase = true) }
            var activeToken = session.accessToken
            var activeSession = session
            if (SessionManager.isTokenExpired()) {
                val refreshResult = SupabaseAuthService.refreshToken(session.refreshToken, _uiState.value.supabaseConfig)
                if (refreshResult is AuthResult.Success) {
                    SessionManager.saveSession(refreshResult.session)
                    activeToken = refreshResult.session.accessToken
                    activeSession = refreshResult.session
                } else {
                    SessionManager.clearSession()
                    _uiState.update {
                        it.copy(
                            isLoggedIn = false,
                            role = UserRole.STUDENT,
                            isSyncingWithSupabase = false,
                            isSubjectsLoaded = true
                        )
                    }
                    return@launch
                }
            }

            // إعادة جلب الدور والملف الشخصي دائمًا من جدول profiles بدلاً من الاعتماد على بيانات محلية
            val profileRes = SupabaseAuthService.fetchUserProfile(
                userId = activeSession.userId,
                accessToken = activeToken,
                config = _uiState.value.supabaseConfig
            )

            if (profileRes.isSuccess) {
                val profile = profileRes.getOrThrow()
                val assignedRole = if (profile.isAdmin) UserRole.ADMIN else UserRole.STUDENT
                val effectiveDisplayName = profile.displayName.ifBlank { profile.username.ifBlank { activeSession.displayName.ifBlank { "طالب" } } }
                val effectiveUsername = profile.username.ifBlank { activeSession.email }

                _uiState.update {
                    it.copy(
                        isLoggedIn = true,
                        username = effectiveUsername,
                        displayName = effectiveDisplayName,
                        role = assignedRole,
                        academicYear = profile.academicYear,
                        semester = profile.semester,
                        loginError = null
                    )
                }
                // بدء المزامنة بعد اكتمال استعادة الجلسة وتحديد دور المستخدم بنجاح
                syncWithSupabase()
            } else {
                // في حال فشل جلب الملف الشخصي، لا تمنح المستخدم صلاحيات المشرف
                _uiState.update {
                    it.copy(
                        role = UserRole.STUDENT,
                        isSyncingWithSupabase = false,
                        isSubjectsLoaded = true,
                        loginError = "تعذر التحقق من بيانات الملف الشخصي من الخادم"
                    )
                }
            }
        }
    }

    fun syncWithSupabase() {
        val config = _uiState.value.supabaseConfig

        viewModelScope.launch {
            var session = SessionManager.getSession()
            // منع إرسال طلب مجهول إلى الجداول التي تتطلب دور authenticated
            if (session == null || session.accessToken.isBlank()) {
                _uiState.update {
                    it.copy(
                        isSyncingWithSupabase = false,
                        isSubjectsLoaded = true,
                        subjectsError = if (it.isLoggedIn) "جلسة المستخدم غير متوفرة، يرجى إعادة تسجيل الدخول" else null
                    )
                }
                return@launch
            }

            // إذا انتهت صلاحية التوكن، نقوم بتجديده أولاً لضمان وجود رمز وصول صالح
            if (SessionManager.isTokenExpired()) {
                val refreshResult = SupabaseAuthService.refreshToken(session.refreshToken, config)
                if (refreshResult is AuthResult.Success) {
                    SessionManager.saveSession(refreshResult.session)
                    session = refreshResult.session
                } else {
                    SessionManager.clearSession()
                    _uiState.update {
                        it.copy(
                            isLoggedIn = false,
                            isSyncingWithSupabase = false,
                            isSubjectsLoaded = true,
                            subjectsError = "انتهت صلاحية الجلسة، يرجى إعادة تسجيل الدخول"
                        )
                    }
                    return@launch
                }
            }

            val accessToken = session.accessToken
            _uiState.update { it.copy(isSyncingWithSupabase = true, subjectsError = null) }

            // 1. جلب السنوات الدراسية من جدول public.academic_years
            val yearsRes = SupabaseDatabaseService.fetchAcademicYears(accessToken, config)
            if (yearsRes.isSuccess) {
                _uiState.update { it.copy(academicYears = yearsRes.getOrDefault(emptyList())) }
            }

            // 2. جلب الفصول الدراسية من جدول public.semesters
            val semestersRes = SupabaseDatabaseService.fetchSemesters(accessToken, config)
            if (semestersRes.isSuccess) {
                _uiState.update { it.copy(semesters = semestersRes.getOrDefault(emptyList())) }
            }

            // 3. جلب المواد الدراسية من جدول public.subjects
            // وفق سياسات RLS: المشرف يصله جميع المواد، بينما الطالب تصله المنشورة فقط
            val subjectsRes = SupabaseDatabaseService.fetchSubjects(accessToken, config)
            if (subjectsRes.isSuccess) {
                val remoteSubjects = subjectsRes.getOrDefault(emptyList())
                _uiState.update { state ->
                    val publishedIds = remoteSubjects.filter { it.isPublished }.map { it.id }.toSet()
                    state.copy(
                        subjects = remoteSubjects,
                        addedSubjectIds = publishedIds,
                        isSubjectsLoaded = true,
                        subjectsError = null
                    )
                }
            } else {
                val errorMsg = subjectsRes.exceptionOrNull()?.message ?: "فشل تحميل المواد من الخادم"
                _uiState.update { state ->
                    state.copy(
                        isSubjectsLoaded = true,
                        subjectsError = errorMsg
                    )
                }
            }

            // 4. مزامنة الدروس والمحاضرات من Supabase
            val topicsRes = SupabaseDatabaseService.fetchAllTopics(config)
            if (topicsRes.isSuccess) {
                val remoteTopics = topicsRes.getOrNull().orEmpty()
                _uiState.update { state ->
                    val updatedMap = state.customTopicsBySubject.toMutableMap()
                    remoteTopics.forEach { (sId, list) ->
                        val localList = updatedMap[sId].orEmpty()
                        val combined = (localList + list).distinctBy { it.id }
                        updatedMap[sId] = combined
                    }
                    state.copy(customTopicsBySubject = updatedMap)
                }
            }

            val syncMsg = if (subjectsRes.isSuccess) {
                "تمت المزامنة بنجاح مع Supabase"
            } else {
                _uiState.value.subjectsError
            }

            _uiState.update {
                it.copy(
                    isSyncingWithSupabase = false,
                    supabaseSyncStatusMessage = syncMsg
                )
            }
        }
    }

    fun login(email: String, pass: String) {
        val cleanEmail = email.trim()
        val cleanPass = pass.trim()

        if (cleanEmail.isEmpty() || cleanPass.isEmpty()) {
            _uiState.update { it.copy(loginError = "يرجى إدخال البريد الإلكتروني وكلمة المرور") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            val result = SupabaseAuthService.signInWithEmail(
                email = cleanEmail,
                pass = cleanPass,
                config = _uiState.value.supabaseConfig
            )
            when (result) {
                is AuthResult.Success -> {
                    // جلب سجل المستخدم الحقيقي من public.profiles باستخدام JWT الخاص به
                    val profileRes = SupabaseAuthService.fetchUserProfile(
                        userId = result.session.userId,
                        accessToken = result.session.accessToken,
                        config = _uiState.value.supabaseConfig
                    )

                    if (profileRes.isSuccess) {
                        val profile = profileRes.getOrThrow()
                        val assignedRole = if (profile.isAdmin) UserRole.ADMIN else UserRole.STUDENT
                        val effectiveDisplayName = profile.displayName.ifBlank { profile.username.ifBlank { result.session.displayName.ifBlank { "طالب" } } }
                        val effectiveUsername = profile.username.ifBlank { result.session.email }

                        SessionManager.saveSession(result.session)
                        _uiState.update {
                            it.copy(
                                isLoggedIn = true,
                                username = effectiveUsername,
                                displayName = effectiveDisplayName,
                                role = assignedRole,
                                academicYear = profile.academicYear,
                                semester = profile.semester,
                                loginError = null,
                                isLoggingIn = false,
                                currentTab = 0,
                                selectedSubjectId = null
                            )
                        }
                        syncWithSupabase()
                    } else {
                        // إذا فشل جلب الملف الشخصي، لا تمنح المستخدم صلاحيات المشرف إطلاقاً، واعرض رسالة خطأ واضحة
                        val errorDetail = profileRes.exceptionOrNull()?.message ?: "فشل جلب الملف الشخصي"
                        _uiState.update {
                            it.copy(
                                isLoggingIn = false,
                                role = UserRole.STUDENT,
                                loginError = "تعذر تحميل بيانات الملف الشخصي من الخادم: $errorDetail"
                            )
                        }
                    }
                }
                is AuthResult.RequireEmailConfirmation -> {
                    _uiState.update {
                        it.copy(
                            isLoggingIn = false,
                            loginError = result.message
                        )
                    }
                }
                is AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoggingIn = false,
                            loginError = result.message
                        )
                    }
                }
            }
        }
    }

    fun signUp(
        email: String,
        pass: String,
        username: String,
        displayName: String,
        onResult: (AuthResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            val result = SupabaseAuthService.signUpWithEmail(
                email = email,
                pass = pass,
                username = username,
                displayName = displayName,
                config = _uiState.value.supabaseConfig
            )
            _uiState.update { it.copy(isLoggingIn = false) }
            onResult(result)
        }
    }

    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            val config = _uiState.value.supabaseConfig
            val result = SupabaseAuthService.handleDeepLinkCallback(uri, config)
            when (result) {
                is DeepLinkAuthResult.SessionAcquired -> {
                    val session = result.session
                    val profileRes = SupabaseAuthService.fetchUserProfile(
                        userId = session.userId,
                        accessToken = session.accessToken,
                        config = config
                    )
                    if (profileRes.isSuccess) {
                        val profile = profileRes.getOrThrow()
                        val assignedRole = if (profile.isAdmin) UserRole.ADMIN else UserRole.STUDENT
                        val effectiveDisplayName = profile.displayName.ifBlank { profile.username.ifBlank { session.displayName.ifBlank { "طالب" } } }
                        val effectiveUsername = profile.username.ifBlank { session.email }

                        SessionManager.saveSession(session)
                        _uiState.update {
                            it.copy(
                                isLoggedIn = true,
                                username = effectiveUsername,
                                displayName = effectiveDisplayName,
                                role = assignedRole,
                                academicYear = profile.academicYear,
                                semester = profile.semester,
                                loginError = null,
                                isLoggingIn = false,
                                currentTab = 0,
                                selectedSubjectId = null
                            )
                        }
                        syncWithSupabase()
                    } else {
                        val errorDetail = profileRes.exceptionOrNull()?.message ?: "فشل جلب الملف الشخصي"
                        _uiState.update {
                            it.copy(
                                isLoggingIn = false,
                                role = UserRole.STUDENT,
                                loginError = "تم تأكيد الحساب بنجاح ولكن تعذر تحميل بيانات الملف الشخصي: $errorDetail. يرجى تسجيل الدخول يدويًا."
                            )
                        }
                    }
                }
                is DeepLinkAuthResult.EmailConfirmedNeedLogin -> {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = false,
                            isLoggingIn = false,
                            loginError = result.message
                        )
                    }
                }
                is DeepLinkAuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = false,
                            isLoggingIn = false,
                            loginError = result.message
                        )
                    }
                }
                is DeepLinkAuthResult.Ignored -> {
                    _uiState.update { it.copy(isLoggingIn = false) }
                }
            }
        }
    }

    fun logout(context: Context? = null) {
        val session = SessionManager.getSession()
        val currentUserId = session?.userId?.trim().orEmpty()

        // 1. التسلسل الذري تحت القفل المشترك: ضبط راية الخروج وجمع كافة المهام النشطة وإلغاؤها
        val jobsToCancel: List<Job>
        synchronized(downloadLifecycleLock) {
            isLoggingOut.set(true)
            jobsToCancel = activeDownloadJobs.values.toList()
            jobsToCancel.forEach { job ->
                job.cancel(CancellationException("تم إلغاء التنزيل بسبب تسجيل الخروج"))
            }
            activeDownloadJobs.clear()
        }

        // 2. تشغيل عملية التنظيف على خيط IO مع حماية القسم الحرج بـ NonCancellable
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(NonCancellable) {
                    // أ. انتظار اكتمال جميع مهام التنزيل الملغاة لضمان إغلاق التدفقات وتوقف عمليات الكتابة
                    jobsToCancel.forEach { job ->
                        try {
                            job.join()
                        } catch (_: Exception) {}
                    }

                    // ب. تنظيف مجلد التخزين المشفر للمستخدم ومجلد العرض المؤقت بعد توقف العمليات
                    if (context != null && currentUserId.isNotBlank()) {
                        try {
                            val safeUserId = currentUserId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                            val baseDir = context.noBackupFilesDir ?: context.filesDir
                            val secureBase = File(baseDir, "secure_encrypted_pdfs")
                            val userDir = File(secureBase, safeUserId)
                            if (userDir.exists()) {
                                PdfSecurityStorageService.purgeEncryptedStorageDirectory(userDir, secureBase)
                            }
                            PdfSecurityStorageService.purgeViewerCacheDirectory(context.cacheDir)
                        } catch (_: Exception) {}
                    }

                    // ج. تسجيل الخروج من خادم Supabase ومسح الجلسة محلياً
                    if (session != null) {
                        try {
                            SupabaseAuthService.signOut(session.accessToken, _uiState.value.supabaseConfig)
                        } catch (_: Exception) {}
                    }
                    SessionManager.clearSession()
                }
            } finally {
                // د. تحديث حالة الواجهة لتسجيل الخروج ومسح الحالات فقط بعد اكتمال التنظيف، ثم إعادة ضبط الراية
                _uiState.update {
                    it.copy(
                        isLoggedIn = false,
                        username = "",
                        displayName = "",
                        currentTab = 0,
                        selectedSubjectId = null,
                        loginError = null,
                        localPdfStatuses = emptyMap()
                    )
                }
                isLoggingOut.set(false)
            }
        }
    }

    fun toggleDarkMode() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
    }

    fun setDarkMode(dark: Boolean) {
        _uiState.update { it.copy(isDarkMode = dark) }
    }

    fun setCurrentTab(tab: Int) {
        _uiState.update { it.copy(currentTab = tab, selectedSubjectId = null) }
    }

    fun selectSubject(subjectId: String?) {
        _uiState.update { it.copy(selectedSubjectId = subjectId) }
    }

    fun toggleTopicCompletion(topicId: String) {
        _uiState.update { state ->
            val updated = state.completedTopicIds.toMutableSet()
            if (updated.contains(topicId)) {
                updated.remove(topicId)
            } else {
                updated.add(topicId)
            }
            state.copy(completedTopicIds = updated)
        }
    }

    fun setAcademicYear(year: Int) {
        _uiState.update { it.copy(academicYear = year) }
    }

    fun setSemester(sem: Int) {
        _uiState.update { it.copy(semester = sem) }
    }

    fun toggleEnroll(subjectId: String) {
        _uiState.update { state ->
            val updated = state.enrolledSubjectIds.toMutableSet()
            if (updated.contains(subjectId)) {
                updated.remove(subjectId)
            } else {
                updated.add(subjectId)
            }
            state.copy(enrolledSubjectIds = updated)
        }
    }

    fun setSearchQuery(q: String) {
        _uiState.update { it.copy(searchQuery = q) }
    }

    fun setFilterYear(year: Int?) {
        _uiState.update { it.copy(filterYear = year) }
    }

    fun setFilterSemester(sem: Int?) {
        _uiState.update { it.copy(filterSemester = sem) }
    }

    fun togglePublishSubject(subjectId: String) {
        if (_uiState.value.role != UserRole.ADMIN) {
            _uiState.update { it.copy(loginError = "لا تملك صلاحية المشرف لتعديل حالة نشر المادة") }
            return
        }

        val subject = _uiState.value.subjects.firstOrNull { it.id == subjectId }
        val oldStatus = subject?.isPublished ?: _uiState.value.addedSubjectIds.contains(subjectId)
        val newStatus = !oldStatus

        // تحديث تفاؤلي في الواجهة (Optimistic UI Update)
        _uiState.update { state ->
            val updatedSubjects = state.subjects.map {
                if (it.id == subjectId) it.copy(isPublished = newStatus) else it
            }
            val updatedAdded = state.addedSubjectIds.toMutableSet()
            if (newStatus) updatedAdded.add(subjectId) else updatedAdded.remove(subjectId)
            state.copy(
                subjects = updatedSubjects,
                addedSubjectIds = updatedAdded,
                loginError = null
            )
        }

        // إرسال تحديث عمود is_published إلى جدول public.subjects
        viewModelScope.launch {
            val session = SessionManager.getSession()
            val accessToken = session?.accessToken.orEmpty()
            val config = _uiState.value.supabaseConfig

            val result = SupabaseDatabaseService.updateSubjectPublishStatus(
                subjectId = subjectId,
                isPublished = newStatus,
                accessToken = accessToken,
                config = config
            )

            if (result.isFailure) {
                // إذا فشل التحديث، أظهر رسالة خطأ واضحة وأعد الحالة السابقة في الواجهة
                val errorDetail = result.exceptionOrNull()?.message ?: "فشل تحديث حالة النشر في الخادم"
                _uiState.update { state ->
                    val revertedSubjects = state.subjects.map {
                        if (it.id == subjectId) it.copy(isPublished = oldStatus) else it
                    }
                    val revertedAdded = state.addedSubjectIds.toMutableSet()
                    if (oldStatus) revertedAdded.add(subjectId) else revertedAdded.remove(subjectId)
                    state.copy(
                        subjects = revertedSubjects,
                        addedSubjectIds = revertedAdded,
                        loginError = errorDetail
                    )
                }
            }
        }
    }

    fun setLoginError(msg: String?) {
        _uiState.update { it.copy(loginError = msg) }
    }

    fun clearLoginError() {
        _uiState.update { it.copy(loginError = null) }
    }

    fun addCustomTopic(
        subjectId: String,
        title: String,
        description: String = "",
        attachments: List<com.example.model.TopicAttachment>
    ) {
        val newTopic = com.example.model.AdminTopic(
            id = "${subjectId}_topic_${System.currentTimeMillis()}",
            subjectId = subjectId,
            title = title.trim(),
            description = description.trim(),
            attachments = attachments
        )

        _uiState.update { state ->
            val existing = state.customTopicsBySubject[subjectId].orEmpty()
            val updatedMap = state.customTopicsBySubject.toMutableMap()
            updatedMap[subjectId] = existing + newTopic
            state.copy(customTopicsBySubject = updatedMap)
        }

        // حفظ العنوان مع مرفقاته السحابية في جدول topics على Supabase
        viewModelScope.launch {
            val config = _uiState.value.supabaseConfig
            SupabaseDatabaseService.insertTopic(newTopic, config)
        }
    }

    fun removeCustomTopic(subjectId: String, topicId: String) {
        _uiState.update { state ->
            val existing = state.customTopicsBySubject[subjectId].orEmpty()
            val filtered = existing.filterNot { it.id == topicId }
            val updatedMap = state.customTopicsBySubject.toMutableMap()
            updatedMap[subjectId] = filtered
            state.copy(customTopicsBySubject = updatedMap)
        }

        // حذف العنوان من جدول topics على Supabase
        viewModelScope.launch {
            val config = _uiState.value.supabaseConfig
            SupabaseDatabaseService.deleteTopic(topicId, config)
        }
    }

    fun updateSupabaseConfig(config: com.example.model.SupabaseConfig) {
        _uiState.update { it.copy(supabaseConfig = config) }
        syncWithSupabase()
    }

    // =========================================================================
    // Phase 2-B3-A: Lesson PDF Hierarchy Operations (إدارة ملفات الـ PDF المنسقة)
    // =========================================================================

    /**
     * الحصول على رمز وصول صالح ومحدث للجلسة، مع التجديد الاستباقي إذا كان منتهي الصلاحية
     */
    private suspend fun getValidAccessToken(): Result<String> {
        val session = SessionManager.getSession()
        if (session == null || session.accessToken.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول برمز وصول صالح"))
        }
        val config = _uiState.value.supabaseConfig
        if (SessionManager.isTokenExpired()) {
            val refreshResult = SupabaseAuthService.refreshToken(session.refreshToken, config)
            return if (refreshResult is AuthResult.Success) {
                SessionManager.saveSession(refreshResult.session)
                Result.success(refreshResult.session.accessToken)
            } else {
                SessionManager.clearSession()
                _uiState.update {
                    it.copy(
                        isLoggedIn = false,
                        subjectsError = "انتهت صلاحية الجلسة، يرجى إعادة تسجيل الدخول"
                    )
                }
                Result.failure(Exception("انتهت صلاحية الجلسة، يرجى إعادة تسجيل الدخول"))
            }
        }
        return Result.success(session.accessToken)
    }

    /**
     * جلب قائمة ملفات الـ PDF لدرس محدد مع منع الطلبات المتزامنة المتكررة لنفس الدرس
     * (عملية قراءة مستقلة لا تتأثر بحجز عمليات التعديل isPdfOperationLock)
     */
    fun loadPdfsForLesson(lessonId: String, forceReload: Boolean = false, context: Context? = null) {
        val cleanLessonId = lessonId.trim()
        if (cleanLessonId.isBlank()) return
        val currentState = _uiState.value
        // منع الطلبات المتزامنة المتكررة لنفس الدرس أو إذا كانت محملة بالفعل
        if (currentState.loadingPdfLessonIds.contains(cleanLessonId)) return
        if (!forceReload && currentState.lessonPdfsByLesson.containsKey(cleanLessonId)) return

        _uiState.update { state ->
            state.copy(
                loadingPdfLessonIds = state.loadingPdfLessonIds + cleanLessonId,
                pdfOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull()?.message ?: "تعذر التحقق من الجلسة"
                    _uiState.update { state ->
                        state.copy(pdfOperationError = err)
                    }
                    return@launch
                }

                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig
                val result = SupabaseDatabaseService.fetchPdfsForLesson(cleanLessonId, token, config)

                _uiState.update { state ->
                    result.fold(
                        onSuccess = { pdfList ->
                            val updatedMap = state.lessonPdfsByLesson.toMutableMap()
                            updatedMap[cleanLessonId] = pdfList
                            state.copy(lessonPdfsByLesson = updatedMap)
                        },
                        onFailure = { error ->
                            state.copy(
                                pdfOperationError = error.message ?: "فشل تحميل ملفات الدرس"
                            )
                        }
                    )
                }

                if (result.isSuccess && context != null) {
                    val pdfList = result.getOrNull().orEmpty()
                    checkAndSyncLocalPdfStatuses(context, pdfList)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        pdfOperationError = e.message ?: "حدث خطأ غير متوقع أثناء تحميل ملفات الدرس"
                    )
                }
            } finally {
                _uiState.update { state ->
                    state.copy(loadingPdfLessonIds = state.loadingPdfLessonIds - cleanLessonId)
                }
            }
        }
    }

    /**
     * رفع ملف PDF إلى التخزين السحابي الخاص وتسجيل بياناته في جدول lesson_pdfs
     * مع حجز ذري يمنع تداخل العمليات، وتنفيذ حذف تعويضي فوري عند فشل إدراج السجل
     */
    fun uploadAndInsertLessonPdf(
        context: Context,
        fileUri: Uri,
        title: String,
        subjectId: String,
        lessonId: String,
        onResult: ((Result<LessonPdf>) -> Unit)? = null
    ) {
        val cleanTitle = title.trim()
        val cleanSubjectId = subjectId.trim()
        val cleanLessonId = lessonId.trim()

        // 1. فحص الحجز الذري: رفض العملية فوراً إذا كانت هناك عملية PDF أخرى جارية دون تغيير حالة العملية القائمة
        if (!isPdfOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(
                Result.failure(Exception("هناك عملية PDF أخرى جارية بالفعل، يرجى الانتظار حتى اكتمالها"))
            )
            return
        }

        // آلية لضمان استدعاء onResult مرة واحدة فقط حتى في حال حدوسط استثناءات لاحقة
        val hasReportedResult = AtomicBoolean(false)
        fun notifyResult(res: Result<LessonPdf>) {
            if (hasReportedResult.compareAndSet(false, true)) {
                onResult?.invoke(res)
            }
        }

        if (cleanTitle.isBlank()) {
            isPdfOperationLock.set(false)
            val err = Exception("عنوان ملف الـ PDF مطلوب ولا يمكن أن يكون فارغاً")
            _uiState.update { it.copy(pdfOperationError = err.message) }
            notifyResult(Result.failure(err))
            return
        }
        if (cleanSubjectId.isBlank() || cleanLessonId.isBlank()) {
            isPdfOperationLock.set(false)
            val err = Exception("معرف المادة ومعرف الدرس مطلوبان لربط الملف")
            _uiState.update { it.copy(pdfOperationError = err.message) }
            notifyResult(Result.failure(err))
            return
        }

        _uiState.update {
            it.copy(
                isPdfOperating = true,
                pdfOperationError = null,
                pdfOperationMessage = "جاري رفع الملف إلى التخزين السحابي..."
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update {
                        it.copy(
                            pdfOperationError = err.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(err))
                    return@launch
                }

                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                // 1. رفع الملف إلى مساحة التخزين الخاصة تدفقيًا
                val uploadRes = SupabaseStorageService.uploadPdf(
                    context = context,
                    fileUri = fileUri,
                    subjectId = cleanSubjectId,
                    lessonId = cleanLessonId,
                    accessToken = token,
                    config = config
                )

                if (uploadRes.isFailure) {
                    val uploadErr = uploadRes.exceptionOrNull() ?: Exception("فشل رفع الملف إلى التخزين السحابي")
                    _uiState.update {
                        it.copy(
                            pdfOperationError = uploadErr.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(uploadErr))
                    return@launch
                }

                val uploadData = uploadRes.getOrThrow()
                _uiState.update { it.copy(pdfOperationMessage = "تم الرفع السحابي، جاري تسجيل بيانات الملف...") }

                // 2. تسجيل بيانات الملف في جدول lesson_pdfs (isPublished = false افتراضيًا)
                val insertRes = SupabaseDatabaseService.insertLessonPdf(
                    lessonId = cleanLessonId,
                    subjectId = cleanSubjectId,
                    title = cleanTitle,
                    filePath = uploadData.filePath,
                    fileSizeBytes = uploadData.fileSizeBytes,
                    mimeType = uploadData.mimeType,
                    orderIndex = 0,
                    isPublished = false,
                    accessToken = token,
                    config = config
                )

                if (insertRes.isSuccess) {
                    val newPdf = insertRes.getOrThrow()
                    _uiState.update { state ->
                        val currentPdfs = state.lessonPdfsByLesson[cleanLessonId].orEmpty()
                        val updatedMap = state.lessonPdfsByLesson.toMutableMap()
                        updatedMap[cleanLessonId] = currentPdfs + newPdf
                        state.copy(
                            lessonPdfsByLesson = updatedMap,
                            pdfOperationError = null,
                            pdfOperationMessage = "تم إضافة ملف الـ PDF بنجاح وحفظه كمسودة"
                        )
                    }
                    notifyResult(Result.success(newPdf))
                } else {
                    // 3. فشل الإدراج: تنفيذ الحذف التعويضي للملف المؤقت من Storage
                    val dbErr = insertRes.exceptionOrNull() ?: Exception("فشل تسجيل بيانات الملف في قاعدة البيانات")
                    _uiState.update { it.copy(pdfOperationMessage = "تعذر تسجيل البيانات، جاري تنظيف الملف المؤقت...") }

                    val cleanupRes = SupabaseStorageService.deletePdf(
                        filePath = uploadData.filePath,
                        accessToken = token,
                        config = config
                    )

                    val finalErr = if (cleanupRes.isSuccess) {
                        Exception("فشل تسجيل بيانات الملف في قاعدة البيانات: ${dbErr.message}")
                    } else {
                        Exception(
                            "فشل تسجيل بيانات الملف في قاعدة البيانات (${dbErr.message})، " +
                            "وتعذر حذف الملف المرفوع تلقائيًا من التخزين السحابي. " +
                            "المسار الذي يحتاج تنظيف يدوي: ${uploadData.filePath}"
                        )
                    }

                    _uiState.update {
                        it.copy(
                            pdfOperationError = finalErr.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(finalErr))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        pdfOperationError = e.message ?: "حدث خطأ غير متوقع أثناء إضافة ملف الـ PDF",
                        pdfOperationMessage = null
                    )
                }
                notifyResult(Result.failure(e))
            } finally {
                isPdfOperationLock.set(false)
                _uiState.update { it.copy(isPdfOperating = false) }
            }
        }
    }

    /**
     * حذف ملف PDF من التخزين السحابي أولاً، ثم حذف سجله من قاعدة البيانات
     * وفي حال فشل حذف التخزين يُحظر حذف سجل قاعدة البيانات للحفاظ على الاتساق.
     * إذا نجح حذف التخزين وفشل حذف السجل، يُسجل المعرف في orphanPdfRecordIdsPendingDbDelete لتمكين إعادة المحاولة الآمنة.
     */
    fun deleteLessonPdfWithStorage(
        pdf: LessonPdf,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        if (!isPdfOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(
                Result.failure(Exception("هناك عملية PDF أخرى جارية بالفعل، يرجى الانتظار حتى اكتمالها"))
            )
            return
        }

        val hasReportedResult = AtomicBoolean(false)
        fun notifyResult(res: Result<Unit>) {
            if (hasReportedResult.compareAndSet(false, true)) {
                onResult?.invoke(res)
            }
        }

        _uiState.update {
            it.copy(
                isPdfOperating = true,
                pdfOperationError = null,
                pdfOperationMessage = "جاري حذف الملف من التخزين السحابي..."
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update {
                        it.copy(
                            pdfOperationError = err.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(err))
                    return@launch
                }

                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                // 1. حذف الملف من Storage أولاً
                val storageDeleteRes = SupabaseStorageService.deletePdf(
                    filePath = pdf.filePath,
                    accessToken = token,
                    config = config
                )

                if (storageDeleteRes.isFailure) {
                    val storageErr = storageDeleteRes.exceptionOrNull()
                        ?: Exception("فشل حذف الملف من التخزين السحابي")
                    // منع حذف سجل قاعدة البيانات عند فشل حذف Storage
                    // ولا يُضاف المعرف إلى orphanPdfRecordIdsPendingDbDelete لأن الملف لم يُحذف
                    _uiState.update {
                        it.copy(
                            pdfOperationError = "تعذر حذف الملف من التخزين السحابي (${storageErr.message})، وتم الإبقاء على سجل قاعدة البيانات",
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(storageErr))
                    return@launch
                }

                _uiState.update { it.copy(pdfOperationMessage = "تم حذف الملف السحابي، جاري حذف السجل من قاعدة البيانات...") }

                // 2. بعد نجاح حذف Storage، حذف سجل قاعدة البيانات
                val dbDeleteRes = SupabaseDatabaseService.deleteLessonPdf(
                    pdfId = pdf.id,
                    accessToken = token,
                    config = config
                )

                if (dbDeleteRes.isSuccess) {
                    // إزالة الملف من قائمة الدرس في الحالة، وإزالته من مجموعة السجلات المعلقة إن وُجد
                    _uiState.update { state ->
                        val currentPdfs = state.lessonPdfsByLesson[pdf.lessonId].orEmpty()
                        val updatedList = currentPdfs.filterNot { it.id == pdf.id }
                        val updatedMap = state.lessonPdfsByLesson.toMutableMap()
                        updatedMap[pdf.lessonId] = updatedList
                        state.copy(
                            lessonPdfsByLesson = updatedMap,
                            orphanPdfRecordIdsPendingDbDelete = state.orphanPdfRecordIdsPendingDbDelete - pdf.id,
                            pdfOperationError = null,
                            pdfOperationMessage = "تم حذف الملف وسجله بنجاح"
                        )
                    }
                    notifyResult(Result.success(Unit))
                } else {
                    // نجح حذف Storage ولكن فشل حذف سجل قاعدة البيانات:
                    // نضيف المعرف إلى orphanPdfRecordIdsPendingDbDelete لتمكين زر إعادة محاولة حذف السجل
                    val partialErr = Exception(
                        "تم حذف الملف الفعلي من التخزين السحابي بنجاح، ولكن تعذر حذف سجله من قاعدة البيانات " +
                        "(معرف السجل: ${pdf.id}). يرجى استخدام زر إعادة محاولة حذف السجل لتحديث القائمة."
                    )
                    _uiState.update { state ->
                        state.copy(
                            orphanPdfRecordIdsPendingDbDelete = state.orphanPdfRecordIdsPendingDbDelete + pdf.id,
                            pdfOperationError = partialErr.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(partialErr))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        pdfOperationError = e.message ?: "حدث خطأ غير متوقع أثناء حذف ملف الـ PDF",
                        pdfOperationMessage = null
                    )
                }
                notifyResult(Result.failure(e))
            } finally {
                isPdfOperationLock.set(false)
                _uiState.update { it.copy(isPdfOperating = false) }
            }
        }
    }

    /**
     * إعادة محاولة حذف سجل قاعدة البيانات فقط (في حال تم حذف الملف من Storage مسبقاً وفشل حذف السجل)
     * مقيدة بالتحقق من تسجيل المعرف مسبقاً في orphanPdfRecordIdsPendingDbDelete لضمان عدم حذف سجل لملف لا يزال بالتخزين
     */
    fun deleteLessonPdfRecordOnly(
        pdfId: String,
        lessonId: String,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanId = pdfId.trim()
        val cleanLessonId = lessonId.trim()
        if (cleanId.isBlank() || cleanLessonId.isBlank()) return

        if (!isPdfOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(
                Result.failure(Exception("هناك عملية PDF أخرى جارية بالفعل، يرجى الانتظار حتى اكتمالها"))
            )
            return
        }

        val hasReportedResult = AtomicBoolean(false)
        fun notifyResult(res: Result<Unit>) {
            if (hasReportedResult.compareAndSet(false, true)) {
                onResult?.invoke(res)
            }
        }

        // التحقق الإلزامي: هل المعرف مقيد ومسجل كملف نجح حذف كائنه التخزيني مسبقاً؟
        val currentState = _uiState.value
        if (!currentState.orphanPdfRecordIdsPendingDbDelete.contains(cleanId)) {
            isPdfOperationLock.set(false)
            val notAllowedErr = Exception(
                "لا يمكن حذف سجل الملف من قاعدة البيانات دون تأكيد نجاح حذف ملفه الفعلي من التخزين السحابي أولاً. " +
                "يرجى استخدام الحذف الكامل (deleteLessonPdfWithStorage)."
            )
            notifyResult(Result.failure(notAllowedErr))
            return
        }

        _uiState.update {
            it.copy(
                isPdfOperating = true,
                pdfOperationError = null,
                pdfOperationMessage = "جاري حذف السجل من قاعدة البيانات..."
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update {
                        it.copy(
                            pdfOperationError = err.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(err))
                    return@launch
                }

                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val dbDeleteRes = SupabaseDatabaseService.deleteLessonPdf(
                    pdfId = cleanId,
                    accessToken = token,
                    config = config
                )

                if (dbDeleteRes.isSuccess) {
                    _uiState.update { state ->
                        val currentPdfs = state.lessonPdfsByLesson[cleanLessonId].orEmpty()
                        val updatedList = currentPdfs.filterNot { it.id == cleanId }
                        val updatedMap = state.lessonPdfsByLesson.toMutableMap()
                        updatedMap[cleanLessonId] = updatedList
                        state.copy(
                            lessonPdfsByLesson = updatedMap,
                            orphanPdfRecordIdsPendingDbDelete = state.orphanPdfRecordIdsPendingDbDelete - cleanId,
                            pdfOperationError = null,
                            pdfOperationMessage = "تم حذف سجل الملف من قاعدة البيانات بنجاح"
                        )
                    }
                    notifyResult(Result.success(Unit))
                } else {
                    val dbErr = dbDeleteRes.exceptionOrNull() ?: Exception("فشل حذف السجل")
                    // تبقى cleanId في orphanPdfRecordIdsPendingDbDelete لإتاحة المحاولة لاحقاً
                    _uiState.update {
                        it.copy(
                            pdfOperationError = dbErr.message,
                            pdfOperationMessage = null
                        )
                    }
                    notifyResult(Result.failure(dbErr))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        pdfOperationError = e.message ?: "حدث خطأ غير متوقع أثناء حذف السجل",
                        pdfOperationMessage = null
                    )
                }
                notifyResult(Result.failure(e))
            } finally {
                isPdfOperationLock.set(false)
                _uiState.update { it.copy(isPdfOperating = false) }
            }
        }
    }

    /**
     * مسح رسائل وإشعارات عمليات ملفات الـ PDF
     */
    fun clearPdfOperationMessages() {
        _uiState.update {
            it.copy(
                pdfOperationMessage = null,
                pdfOperationError = null
            )
        }
    }

    // =========================================================================
    // Phase 2-B3-B1: Educational Course Hierarchy (الوحدات والفصول والدروس)
    // =========================================================================

    /**
     * جلب الوحدات التعليمية لمادة محددة
     */
    fun loadUnitsForSubject(subjectId: String, forceReload: Boolean = false) {
        val cleanId = subjectId.trim()
        if (cleanId.isBlank()) return
        val currentState = _uiState.value
        val loadKey = "units_$cleanId"
        if (currentState.loadingHierarchyIds.contains(loadKey)) return
        if (!forceReload && currentState.unitsBySubject.containsKey(cleanId)) return

        _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds + loadKey) }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    _uiState.update { it.copy(hierarchyOperationError = tokenRes.exceptionOrNull()?.message ?: "تعذر التحقق من الجلسة") }
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig
                val result = SupabaseDatabaseService.fetchUnitsForSubject(cleanId, token, config)

                _uiState.update { state ->
                    result.fold(
                        onSuccess = { list ->
                            val updated = state.unitsBySubject.toMutableMap()
                            updated[cleanId] = list.sortedBy { it.orderIndex }
                            state.copy(unitsBySubject = updated)
                        },
                        onFailure = { err ->
                            state.copy(hierarchyOperationError = err.message ?: "فشل جلب الوحدات الدراسية")
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "حدث خطأ أثناء جلب الوحدات الدراسية") }
            } finally {
                _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds - loadKey) }
            }
        }
    }

    /**
     * إنشاء وحدة تعليمية جديدة تابعة لمادة
     */
    fun createCourseUnit(
        subjectId: String,
        title: String,
        description: String? = null,
        orderIndex: Int = 1,
        isPublished: Boolean = true,
        onResult: ((Result<CourseUnit>) -> Unit)? = null
    ) {
        val cleanSubjectId = subjectId.trim()
        val cleanTitle = title.trim()
        if (cleanSubjectId.isBlank() || cleanTitle.isBlank()) {
            val err = Exception("معرف المادة وعنوان الوحدة مطلوبان")
            onResult?.invoke(Result.failure(err))
            return
        }

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<CourseUnit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري إنشاء الوحدة الدراسية...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.createCourseUnit(
                    subjectId = cleanSubjectId,
                    title = cleanTitle,
                    description = description?.trim(),
                    orderIndex = orderIndex,
                    isPublished = isPublished,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    val newUnit = result.getOrThrow()
                    _uiState.update { state ->
                        val currentList = state.unitsBySubject[cleanSubjectId].orEmpty()
                        val updated = state.unitsBySubject.toMutableMap()
                        updated[cleanSubjectId] = (currentList + newUnit).sortedBy { it.orderIndex }
                        state.copy(
                            unitsBySubject = updated,
                            hierarchyOperationMessage = "تم إنشاء الوحدة بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    notify(Result.success(newUnit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل إنشاء الوحدة الدراسية")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء إنشاء الوحدة", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * تحديث بيانات أو حالة نشر وحدة تعليمية
     */
    fun updateCourseUnit(
        unitId: String,
        subjectId: String,
        title: String? = null,
        description: String? = null,
        clearDescription: Boolean = false,
        orderIndex: Int? = null,
        isPublished: Boolean? = null,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanUnitId = unitId.trim()
        val cleanSubjectId = subjectId.trim()
        if (cleanUnitId.isBlank() || cleanSubjectId.isBlank()) return

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<Unit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري تحديث الوحدة...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.updateCourseUnit(
                    unitId = cleanUnitId,
                    title = title?.trim(),
                    description = description?.trim(),
                    clearDescription = clearDescription,
                    orderIndex = orderIndex,
                    isPublished = isPublished,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    _uiState.update { state ->
                        val currentList = state.unitsBySubject[cleanSubjectId].orEmpty()
                        val updatedList = currentList.map { unit ->
                            if (unit.id == cleanUnitId) {
                                unit.copy(
                                    title = title?.trim() ?: unit.title,
                                    description = if (clearDescription) null else (description?.trim() ?: unit.description),
                                    orderIndex = orderIndex ?: unit.orderIndex,
                                    isPublished = isPublished ?: unit.isPublished
                                )
                            } else unit
                        }.sortedBy { it.orderIndex }
                        val updatedMap = state.unitsBySubject.toMutableMap()
                        updatedMap[cleanSubjectId] = updatedList
                        state.copy(
                            unitsBySubject = updatedMap,
                            hierarchyOperationMessage = "تم تحديث الوحدة بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    notify(Result.success(Unit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل تحديث الوحدة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء تحديث الوحدة", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * حذف وحدة تعليمية بعد تأكيد الخادم
     */
    fun deleteCourseUnit(
        unitId: String,
        subjectId: String,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanUnitId = unitId.trim()
        val cleanSubjectId = subjectId.trim()
        if (cleanUnitId.isBlank() || cleanSubjectId.isBlank()) return

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<Unit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري حذف الوحدة وجميع فصولها ودروسها...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.deleteCourseUnit(
                    unitId = cleanUnitId,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    _uiState.update { state ->
                        val currentUnits = state.unitsBySubject[cleanSubjectId].orEmpty().filterNot { it.id == cleanUnitId }
                        val updatedUnits = state.unitsBySubject.toMutableMap()
                        updatedUnits[cleanSubjectId] = currentUnits

                        val updatedChapters = state.chaptersByUnit.toMutableMap()
                        updatedChapters.remove(cleanUnitId)

                        val updatedDirectLessons = state.directLessonsByUnit.toMutableMap()
                        updatedDirectLessons.remove(cleanUnitId)

                        state.copy(
                            unitsBySubject = updatedUnits,
                            chaptersByUnit = updatedChapters,
                            directLessonsByUnit = updatedDirectLessons,
                            fullyLoadedDirectLessonUnitIds = state.fullyLoadedDirectLessonUnitIds - cleanUnitId,
                            hierarchyOperationMessage = "تم حذف الوحدة بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    recentTransfers.entries.removeIf { it.value.targetUnitId == cleanUnitId }
                    notify(Result.success(Unit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل حذف الوحدة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء حذف الوحدة", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * جلب الفصول التابعة لوحدة محددة
     */
    fun loadChaptersForUnit(unitId: String, forceReload: Boolean = false) {
        val cleanId = unitId.trim()
        if (cleanId.isBlank()) return
        val currentState = _uiState.value
        val loadKey = "chapters_$cleanId"
        if (currentState.loadingHierarchyIds.contains(loadKey)) return
        if (!forceReload && currentState.chaptersByUnit.containsKey(cleanId)) return

        _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds + loadKey) }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    _uiState.update { it.copy(hierarchyOperationError = tokenRes.exceptionOrNull()?.message ?: "تعذر التحقق من الجلسة") }
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig
                val result = SupabaseDatabaseService.fetchChaptersForUnit(cleanId, token, config)

                _uiState.update { state ->
                    result.fold(
                        onSuccess = { list ->
                            val updated = state.chaptersByUnit.toMutableMap()
                            updated[cleanId] = list.sortedBy { it.orderIndex }
                            state.copy(chaptersByUnit = updated)
                        },
                        onFailure = { err ->
                            state.copy(hierarchyOperationError = err.message ?: "فشل جلب فصول الوحدة")
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء جلب فصول الوحدة") }
            } finally {
                _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds - loadKey) }
            }
        }
    }

    /**
     * إنشاء فصل جديد داخل وحدة محددة
     */
    fun createCourseChapter(
        unitId: String,
        subjectId: String,
        title: String,
        description: String? = null,
        orderIndex: Int = 1,
        isPublished: Boolean = true,
        onResult: ((Result<CourseChapter>) -> Unit)? = null
    ) {
        val cleanUnitId = unitId.trim()
        val cleanSubjectId = subjectId.trim()
        val cleanTitle = title.trim()
        if (cleanUnitId.isBlank() || cleanSubjectId.isBlank() || cleanTitle.isBlank()) {
            onResult?.invoke(Result.failure(Exception("معرف الوحدة والمادة وعنوان الفصل مطلوبة")))
            return
        }

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<CourseChapter>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري إنشاء الفصل...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.createCourseChapter(
                    unitId = cleanUnitId,
                    subjectId = cleanSubjectId,
                    title = cleanTitle,
                    description = description?.trim(),
                    orderIndex = orderIndex,
                    isPublished = isPublished,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    val newChapter = result.getOrThrow()
                    _uiState.update { state ->
                        val currentList = state.chaptersByUnit[cleanUnitId].orEmpty()
                        val updated = state.chaptersByUnit.toMutableMap()
                        updated[cleanUnitId] = (currentList + newChapter).sortedBy { it.orderIndex }
                        state.copy(
                            chaptersByUnit = updated,
                            hierarchyOperationMessage = "تم إنشاء الفصل بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    notify(Result.success(newChapter))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل إنشاء الفصل")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء إنشاء الفصل", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * تحديث بيانات أو حالة نشر فصل
     */
    fun updateCourseChapter(
        chapterId: String,
        unitId: String,
        title: String? = null,
        description: String? = null,
        clearDescription: Boolean = false,
        orderIndex: Int? = null,
        isPublished: Boolean? = null,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanChapterId = chapterId.trim()
        val cleanUnitId = unitId.trim()
        if (cleanChapterId.isBlank() || cleanUnitId.isBlank()) return

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<Unit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري تحديث الفصل...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.updateCourseChapter(
                    chapterId = cleanChapterId,
                    title = title?.trim(),
                    description = description?.trim(),
                    clearDescription = clearDescription,
                    orderIndex = orderIndex,
                    isPublished = isPublished,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    _uiState.update { state ->
                        val currentList = state.chaptersByUnit[cleanUnitId].orEmpty()
                        val updatedList = currentList.map { chap ->
                            if (chap.id == cleanChapterId) {
                                chap.copy(
                                    title = title?.trim() ?: chap.title,
                                    description = if (clearDescription) null else (description?.trim() ?: chap.description),
                                    orderIndex = orderIndex ?: chap.orderIndex,
                                    isPublished = isPublished ?: chap.isPublished
                                )
                            } else chap
                        }.sortedBy { it.orderIndex }
                        val updatedMap = state.chaptersByUnit.toMutableMap()
                        updatedMap[cleanUnitId] = updatedList
                        state.copy(
                            chaptersByUnit = updatedMap,
                            hierarchyOperationMessage = "تم تحديث الفصل بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    notify(Result.success(Unit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل تحديث الفصل")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء تحديث الفصل", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * حذف فصل تعليمي
     */
    fun deleteCourseChapter(
        chapterId: String,
        unitId: String,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanChapterId = chapterId.trim()
        val cleanUnitId = unitId.trim()
        if (cleanChapterId.isBlank() || cleanUnitId.isBlank()) return

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<Unit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري حذف الفصل ودروسه...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.deleteCourseChapter(
                    chapterId = cleanChapterId,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    _uiState.update { state ->
                        val currentList = state.chaptersByUnit[cleanUnitId].orEmpty().filterNot { it.id == cleanChapterId }
                        val updatedChapters = state.chaptersByUnit.toMutableMap()
                        updatedChapters[cleanUnitId] = currentList

                        val updatedLessons = state.lessonsByChapter.toMutableMap()
                        updatedLessons.remove(cleanChapterId)

                        state.copy(
                            chaptersByUnit = updatedChapters,
                            lessonsByChapter = updatedLessons,
                            fullyLoadedChapterLessonIds = state.fullyLoadedChapterLessonIds - cleanChapterId,
                            hierarchyOperationMessage = "تم حذف الفصل بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    recentTransfers.entries.removeIf { it.value.targetChapterId == cleanChapterId }
                    notify(Result.success(Unit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل حذف الفصل")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء حذف الفصل", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * جلب الدروس المباشرة التابعة لوحدة دون فصل
     */
    fun loadDirectLessonsForUnit(unitId: String, forceReload: Boolean = false) {
        val cleanId = unitId.trim()
        if (cleanId.isBlank()) return
        val currentState = _uiState.value
        val loadKey = "direct_lessons_$cleanId"
        if (currentState.loadingHierarchyIds.contains(loadKey)) return
        if (!forceReload && currentState.fullyLoadedDirectLessonUnitIds.contains(cleanId)) return

        val requestStartTime = System.currentTimeMillis()
        _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds + loadKey) }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    _uiState.update { it.copy(hierarchyOperationError = tokenRes.exceptionOrNull()?.message ?: "تعذر التحقق من الجلسة") }
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig
                val result = SupabaseDatabaseService.fetchDirectLessonsForUnit(cleanId, token, config)

                _uiState.update { state ->
                    result.fold(
                        onSuccess = { rawFetchedList ->
                            // 1. استبعاد أي درس تم نقله إلى فصل أو وحدة أخرى أثناء تنفيذ هذا الطلب
                            val validFetchedList = rawFetchedList.filterNot { fetched ->
                                val transfer = recentTransfers[fetched.id]
                                transfer != null && (transfer.targetChapterId != null || transfer.targetUnitId != cleanId) && transfer.timestamp >= requestStartTime
                            }

                            // 2. تأكيد وصول الدروس من الخادم وتنظيفها من سجل النقل المؤقت
                            validFetchedList.forEach { fetched ->
                                val transfer = recentTransfers[fetched.id]
                                if (transfer != null && transfer.targetChapterId == null && transfer.targetUnitId == cleanId) {
                                    recentTransfers.remove(fetched.id)
                                }
                            }

                            val serverIds = validFetchedList.map { it.id }.toSet()

                            // 3. الاحتفاظ بالدروس المنقولة حديثاً لهذا الموقع ولم تعد بعد في نتيجة الخادم
                            val localTransfersToKeep = if (forceReload) {
                                state.directLessonsByUnit[cleanId].orEmpty().filter { local ->
                                    !serverIds.contains(local.id) &&
                                    recentTransfers[local.id]?.let { it.targetChapterId == null && it.targetUnitId == cleanId && it.timestamp >= requestStartTime } == true
                                }
                            } else {
                                state.directLessonsByUnit[cleanId].orEmpty().filter { local ->
                                    !serverIds.contains(local.id) &&
                                    recentTransfers[local.id]?.let { it.targetChapterId == null && it.targetUnitId == cleanId } == true
                                }
                            }

                            // 4. اعتماد بيانات الخادم كالمرجع الأساسي وفرز القائمة النهائية
                            val merged = (validFetchedList + localTransfersToKeep).sortedBy { it.orderIndex }

                            val updated = state.directLessonsByUnit.toMutableMap()
                            updated[cleanId] = merged
                            state.copy(
                                directLessonsByUnit = updated,
                                fullyLoadedDirectLessonUnitIds = state.fullyLoadedDirectLessonUnitIds + cleanId
                            )
                        },
                        onFailure = { err ->
                            state.copy(hierarchyOperationError = err.message ?: "فشل جلب دروس الوحدة")
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء جلب دروس الوحدة") }
            } finally {
                _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds - loadKey) }
            }
        }
    }

    /**
     * جلب الدروس التابعة لفصل محدد
     */
    fun loadLessonsForChapter(chapterId: String, forceReload: Boolean = false) {
        val cleanId = chapterId.trim()
        if (cleanId.isBlank()) return
        val currentState = _uiState.value
        val loadKey = "chapter_lessons_$cleanId"
        if (currentState.loadingHierarchyIds.contains(loadKey)) return
        if (!forceReload && currentState.fullyLoadedChapterLessonIds.contains(cleanId)) return

        val requestStartTime = System.currentTimeMillis()
        _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds + loadKey) }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    _uiState.update { it.copy(hierarchyOperationError = tokenRes.exceptionOrNull()?.message ?: "تعذر التحقق من الجلسة") }
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig
                val result = SupabaseDatabaseService.fetchLessonsForChapter(cleanId, token, config)

                _uiState.update { state ->
                    result.fold(
                        onSuccess = { rawFetchedList ->
                            // 1. استبعاد أي درس تم نقله إلى فصل آخر أو دروس مباشرة أثناء تنفيذ هذا الطلب
                            val validFetchedList = rawFetchedList.filterNot { fetched ->
                                val transfer = recentTransfers[fetched.id]
                                transfer != null && transfer.targetChapterId != cleanId && transfer.timestamp >= requestStartTime
                            }

                            // 2. تأكيد وصول الدروس من الخادم وتنظيفها من سجل النقل المؤقت
                            validFetchedList.forEach { fetched ->
                                val transfer = recentTransfers[fetched.id]
                                if (transfer != null && transfer.targetChapterId == cleanId) {
                                    recentTransfers.remove(fetched.id)
                                }
                            }

                            val serverIds = validFetchedList.map { it.id }.toSet()

                            // 3. الاحتفاظ بالدروس المنقولة حديثاً لهذا الفصل ولم تعد بعد في نتيجة الخادم
                            val localTransfersToKeep = if (forceReload) {
                                state.lessonsByChapter[cleanId].orEmpty().filter { local ->
                                    !serverIds.contains(local.id) &&
                                    recentTransfers[local.id]?.let { it.targetChapterId == cleanId && it.timestamp >= requestStartTime } == true
                                }
                            } else {
                                state.lessonsByChapter[cleanId].orEmpty().filter { local ->
                                    !serverIds.contains(local.id) &&
                                    recentTransfers[local.id]?.let { it.targetChapterId == cleanId } == true
                                }
                            }

                            // 4. اعتماد بيانات الخادم كالمرجع الأساسي وفرز القائمة النهائية
                            val merged = (validFetchedList + localTransfersToKeep).sortedBy { it.orderIndex }

                            val updated = state.lessonsByChapter.toMutableMap()
                            updated[cleanId] = merged
                            state.copy(
                                lessonsByChapter = updated,
                                fullyLoadedChapterLessonIds = state.fullyLoadedChapterLessonIds + cleanId
                            )
                        },
                        onFailure = { err ->
                            state.copy(hierarchyOperationError = err.message ?: "فشل جلب دروس الفصل")
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء جلب دروس الفصل") }
            } finally {
                _uiState.update { it.copy(loadingHierarchyIds = it.loadingHierarchyIds - loadKey) }
            }
        }
    }

    /**
     * إنشاء درس جديد تابع لوحدة إما مباشرة أو لفصل تابع لها
     */
    fun createCourseLesson(
        unitId: String,
        subjectId: String,
        chapterId: String? = null,
        title: String,
        summary: String? = null,
        durationMinutes: Int? = null,
        orderIndex: Int = 1,
        isPublished: Boolean = true,
        onResult: ((Result<CourseLesson>) -> Unit)? = null
    ) {
        val cleanUnitId = unitId.trim()
        val cleanSubjectId = subjectId.trim()
        val cleanChapterId = chapterId?.trim()?.ifBlank { null }
        val cleanTitle = title.trim()

        if (cleanUnitId.isBlank() || cleanSubjectId.isBlank() || cleanTitle.isBlank()) {
            onResult?.invoke(Result.failure(Exception("معرف الوحدة والمادة وعنوان الدرس مطلوبة")))
            return
        }

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<CourseLesson>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري إنشاء الدرس...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.createCourseLesson(
                    unitId = cleanUnitId,
                    subjectId = cleanSubjectId,
                    chapterId = cleanChapterId,
                    title = cleanTitle,
                    summary = summary?.trim(),
                    durationMinutes = durationMinutes,
                    orderIndex = orderIndex,
                    isPublished = isPublished,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    val newLesson = result.getOrThrow()
                    _uiState.update { state ->
                        if (cleanChapterId != null) {
                            val currentList = state.lessonsByChapter[cleanChapterId].orEmpty()
                            val updated = state.lessonsByChapter.toMutableMap()
                            updated[cleanChapterId] = (currentList + newLesson).sortedBy { it.orderIndex }
                            state.copy(
                                lessonsByChapter = updated,
                                hierarchyOperationMessage = "تم إنشاء الدرس بنجاح",
                                hierarchyOperationError = null
                            )
                        } else {
                            val currentList = state.directLessonsByUnit[cleanUnitId].orEmpty()
                            val updated = state.directLessonsByUnit.toMutableMap()
                            updated[cleanUnitId] = (currentList + newLesson).sortedBy { it.orderIndex }
                            state.copy(
                                directLessonsByUnit = updated,
                                hierarchyOperationMessage = "تم إنشاء الدرس بنجاح",
                                hierarchyOperationError = null
                            )
                        }
                    }
                    recentTransfers[newLesson.id] = RecentLessonTransfer(
                        lesson = newLesson,
                        targetChapterId = cleanChapterId,
                        targetUnitId = cleanUnitId,
                        timestamp = System.currentTimeMillis()
                    )
                    notify(Result.success(newLesson))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل إنشاء الدرس")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء إنشاء الدرس", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * تحديث بيانات أو حالة نشر درس
     */
    fun updateCourseLesson(
        lessonId: String,
        unitId: String,
        chapterId: String? = null,
        clearChapterId: Boolean = false,
        title: String? = null,
        summary: String? = null,
        clearSummary: Boolean = false,
        durationMinutes: Int? = null,
        clearDuration: Boolean = false,
        orderIndex: Int? = null,
        isPublished: Boolean? = null,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanLessonId = lessonId.trim()
        val cleanUnitId = unitId.trim()
        val cleanChapterId = chapterId?.trim()?.ifBlank { null }
        if (cleanLessonId.isBlank() || cleanUnitId.isBlank()) return

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<Unit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري تحديث الدرس...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.updateCourseLesson(
                    lessonId = cleanLessonId,
                    title = title?.trim(),
                    summary = summary?.trim(),
                    clearSummary = clearSummary,
                    durationMinutes = durationMinutes,
                    clearDuration = clearDuration,
                    chapterId = if (clearChapterId) null else cleanChapterId,
                    clearChapterId = clearChapterId,
                    orderIndex = orderIndex,
                    isPublished = isPublished,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    var finalTargetChapterId: String? = null
                    var finalUpdatedLesson: CourseLesson? = null

                    _uiState.update { state ->
                        // 1. Locate the existing lesson from directLessonsByUnit or lessonsByChapter
                        val existingLesson = state.directLessonsByUnit[cleanUnitId]?.firstOrNull { it.id == cleanLessonId }
                            ?: state.lessonsByChapter.values.flatten().firstOrNull { it.id == cleanLessonId }

                        // 2. Determine target chapter ID
                        val targetChapterId = if (clearChapterId) {
                            null
                        } else if (cleanChapterId != null) {
                            cleanChapterId
                        } else {
                            existingLesson?.chapterId
                        }
                        finalTargetChapterId = targetChapterId

                        // 3. Build updated lesson entity
                        val updatedLesson = (existingLesson ?: CourseLesson(
                            id = cleanLessonId,
                            unitId = cleanUnitId,
                            subjectId = existingLesson?.subjectId ?: "",
                            chapterId = targetChapterId,
                            title = title?.trim() ?: "",
                            summary = if (clearSummary) null else summary?.trim(),
                            durationMinutes = if (clearDuration) null else durationMinutes,
                            orderIndex = orderIndex ?: 0,
                            isPublished = isPublished ?: true,
                            createdAt = "",
                            updatedAt = ""
                        )).copy(
                            title = title?.trim() ?: (existingLesson?.title ?: ""),
                            summary = if (clearSummary) null else (summary?.trim() ?: existingLesson?.summary),
                            durationMinutes = if (clearDuration) null else (durationMinutes ?: existingLesson?.durationMinutes),
                            chapterId = targetChapterId,
                            orderIndex = orderIndex ?: (existingLesson?.orderIndex ?: 0),
                            isPublished = isPublished ?: (existingLesson?.isPublished ?: true)
                        )
                        finalUpdatedLesson = updatedLesson

                        // 4. Remove previous instances from ALL chapters and direct list to prevent duplicates
                        val updatedChaptersMap = state.lessonsByChapter.mapValues { (_, list) ->
                            list.filterNot { it.id == cleanLessonId }
                        }.toMutableMap()

                        val updatedDirectMap = state.directLessonsByUnit.toMutableMap()
                        val currentDirect = state.directLessonsByUnit[cleanUnitId].orEmpty()
                        updatedDirectMap[cleanUnitId] = currentDirect.filterNot { it.id == cleanLessonId }

                        // 5. Insert updated lesson into the correct destination list and keep sorted by orderIndex
                        if (targetChapterId != null) {
                            val targetList = updatedChaptersMap[targetChapterId].orEmpty()
                            updatedChaptersMap[targetChapterId] = (targetList + updatedLesson).sortedBy { it.orderIndex }
                        } else {
                            val targetList = updatedDirectMap[cleanUnitId].orEmpty()
                            updatedDirectMap[cleanUnitId] = (targetList + updatedLesson).sortedBy { it.orderIndex }
                        }

                        state.copy(
                            lessonsByChapter = updatedChaptersMap,
                            directLessonsByUnit = updatedDirectMap,
                            hierarchyOperationMessage = "تم تحديث الدرس بنجاح",
                            hierarchyOperationError = null
                        )
                    }
                    finalUpdatedLesson?.let { lesson ->
                        recentTransfers[cleanLessonId] = RecentLessonTransfer(
                            lesson = lesson,
                            targetChapterId = finalTargetChapterId,
                            targetUnitId = cleanUnitId,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                    notify(Result.success(Unit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل تحديث الدرس")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء تحديث الدرس", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * حذف درس تعليمي
     */
    fun deleteCourseLesson(
        lessonId: String,
        unitId: String,
        chapterId: String? = null,
        onResult: ((Result<Unit>) -> Unit)? = null
    ) {
        val cleanLessonId = lessonId.trim()
        val cleanUnitId = unitId.trim()
        val cleanChapterId = chapterId?.trim()?.ifBlank { null }
        if (cleanLessonId.isBlank() || cleanUnitId.isBlank()) return

        if (!isHierarchyOperationLock.compareAndSet(false, true)) {
            onResult?.invoke(Result.failure(Exception("هناك عملية تعديل جارية بالفعل، يرجى الانتظار")))
            return
        }

        val hasReported = AtomicBoolean(false)
        fun notify(res: Result<Unit>) {
            if (hasReported.compareAndSet(false, true)) onResult?.invoke(res)
        }

        _uiState.update {
            it.copy(
                isHierarchyOperating = true,
                hierarchyOperationMessage = "جاري حذف الدرس...",
                hierarchyOperationError = null
            )
        }

        viewModelScope.launch {
            try {
                val tokenRes = getValidAccessToken()
                if (tokenRes.isFailure) {
                    val err = tokenRes.exceptionOrNull() ?: Exception("تعذر التحقق من الجلسة")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                    return@launch
                }
                val token = tokenRes.getOrThrow()
                val config = _uiState.value.supabaseConfig

                val result = SupabaseDatabaseService.deleteCourseLesson(
                    lessonId = cleanLessonId,
                    accessToken = token,
                    config = config
                )

                if (result.isSuccess) {
                    _uiState.update { state ->
                        if (cleanChapterId != null) {
                            val currentList = state.lessonsByChapter[cleanChapterId].orEmpty().filterNot { it.id == cleanLessonId }
                            val updated = state.lessonsByChapter.toMutableMap()
                            updated[cleanChapterId] = currentList
                            state.copy(
                                lessonsByChapter = updated,
                                hierarchyOperationMessage = "تم حذف الدرس بنجاح",
                                hierarchyOperationError = null
                            )
                        } else {
                            val currentList = state.directLessonsByUnit[cleanUnitId].orEmpty().filterNot { it.id == cleanLessonId }
                            val updated = state.directLessonsByUnit.toMutableMap()
                            updated[cleanUnitId] = currentList
                            state.copy(
                                directLessonsByUnit = updated,
                                hierarchyOperationMessage = "تم حذف الدرس بنجاح",
                                hierarchyOperationError = null
                            )
                        }
                    }
                    recentTransfers.remove(cleanLessonId)
                    notify(Result.success(Unit))
                } else {
                    val err = result.exceptionOrNull() ?: Exception("فشل حذف الدرس")
                    _uiState.update { it.copy(hierarchyOperationError = err.message, hierarchyOperationMessage = null) }
                    notify(Result.failure(err))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(hierarchyOperationError = e.message ?: "خطأ أثناء حذف الدرس", hierarchyOperationMessage = null) }
                notify(Result.failure(e))
            } finally {
                isHierarchyOperationLock.set(false)
                _uiState.update { it.copy(isHierarchyOperating = false) }
            }
        }
    }

    /**
     * مسح رسائل وإشعارات عمليات الهيكلية التعليمية
     */
    fun clearHierarchyOperationMessages() {
        _uiState.update {
            it.copy(
                hierarchyOperationMessage = null,
                hierarchyOperationError = null
            )
        }
    }

    // =========================================================================
    // المرحلة 2-B3-B2-2B: إدارة وتنزيل وحذف ملفات PDF المشفرة محلياً لكل مستخدم
    // =========================================================================

    private val downloadLifecycleLock = Any()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val isLoggingOut = AtomicBoolean(false)

    internal fun isLoggingOut(): Boolean = isLoggingOut.get()
    internal fun getActiveJobsCount(): Int = activeDownloadJobs.size

    internal fun buildDownloadJobKey(userId: String, pdfId: String): String {
        return "${userId.trim()}_${pdfId.trim()}"
    }

    internal fun getUserEncryptedStorageDir(context: Context, userId: String): File {
        val cleanUserId = userId.trim()
        require(cleanUserId.isNotBlank()) { "معرف المستخدم غير صالح" }
        val safeUserId = cleanUserId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val baseDir = context.noBackupFilesDir ?: context.filesDir
        val securePdfsDir = File(baseDir, "secure_encrypted_pdfs")
        val userDir = File(securePdfsDir, safeUserId)
        if (!userDir.exists()) {
            userDir.mkdirs()
        }
        return userDir
    }

    internal fun getLocalEncryptedPdfFile(context: Context, userId: String, pdfId: String): File {
        val cleanPdfId = pdfId.trim()
        try {
            UUID.fromString(cleanPdfId)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("معرف ملف الـ PDF غير صالح (ليس UUID صالحًا): $cleanPdfId")
        }
        val userDir = getUserEncryptedStorageDir(context, userId)
        val fileName = "$cleanPdfId.pdf.enc"
        val targetFile = File(userDir, fileName)
        val targetCanonical = targetFile.canonicalPath
        val userDirCanonical = userDir.canonicalPath
        if (!targetCanonical.startsWith(userDirCanonical + File.separator)) {
            throw SecurityException("مسار الملف غير آمن (محاولة تخطي مجلد المستخدم)")
        }
        return targetFile
    }

    /**
     * تنزيل ملف الـ PDF الموثق من مساحة التخزين الخاصة في Supabase وتشفيره تدفقياً ومباشرة
     * وحفظه في مجلد معزول للمستخدم الحالي دون كتابة أي بايت مكشوف على القرص
     */
    fun downloadLessonPdf(context: Context, pdf: LessonPdf) {
        val cleanPdfId = pdf.id.trim()
        val cleanFilePath = pdf.filePath.trim()

        // 1. التحقق من صلاحية معرف الـ PDF كـ UUID صالح
        try {
            UUID.fromString(cleanPdfId)
        } catch (_: IllegalArgumentException) {
            _uiState.update { state ->
                val updated = state.localPdfStatuses.toMutableMap()
                updated[cleanPdfId] = LocalPdfStatus.Error("معرف ملف الـ PDF غير صالح (يجب أن يكون UUID)")
                state.copy(localPdfStatuses = updated)
            }
            return
        }

        if (cleanFilePath.isBlank()) {
            _uiState.update { state ->
                val updated = state.localPdfStatuses.toMutableMap()
                updated[cleanPdfId] = LocalPdfStatus.Error("مسار الملف على الخادم غير محدد")
                state.copy(localPdfStatuses = updated)
            }
            return
        }

        // 2. التحقق من جلسة المستخدم واستخراج userId
        val currentSession = SessionManager.getSession()
        val sessionUserId = currentSession?.userId?.trim().orEmpty()
        if (sessionUserId.isBlank()) {
            _uiState.update { state ->
                val updated = state.localPdfStatuses.toMutableMap()
                updated[cleanPdfId] = LocalPdfStatus.Error("يجب تسجيل الدخول أولاً لتنزيل الملف")
                state.copy(localPdfStatuses = updated)
            }
            return
        }

        val targetFile = try {
            getLocalEncryptedPdfFile(context, sessionUserId, cleanPdfId)
        } catch (e: Exception) {
            _uiState.update { state ->
                val updated = state.localPdfStatuses.toMutableMap()
                updated[cleanPdfId] = LocalPdfStatus.Error(e.message ?: "مسار التخزين غير صالح")
                state.copy(localPdfStatuses = updated)
            }
            return
        }

        // 3. التحقق من وجود نسخة محلية مشفرة صالحة مسبقاً
        if (PdfSecurityStorageService.isEncryptedFilePresent(targetFile)) {
            _uiState.update { state ->
                val updated = state.localPdfStatuses.toMutableMap()
                updated[cleanPdfId] = LocalPdfStatus.Downloaded(targetFile.length())
                state.copy(localPdfStatuses = updated)
            }
            return
        }

        // 4. الحجز الذري المتسلسل تحت القفل المشترك لمنع التسابق مع تسجيل الخروج أو بدء مهمة أخرى
        val jobKey = buildDownloadJobKey(sessionUserId, cleanPdfId)
        lateinit var createdJob: Job
        var isReserved = false

        synchronized(downloadLifecycleLock) {
            if (isLoggingOut.get()) {
                return
            }

            createdJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    val tokenRes = getValidAccessToken()
                    if (tokenRes.isFailure) {
                        val err = tokenRes.exceptionOrNull()?.message ?: "انتهت صلاحية الجلسة"
                        val curr = SessionManager.getSession()
                        if (curr?.userId?.trim() == sessionUserId && !isLoggingOut.get()) {
                            _uiState.update { state ->
                                val updated = state.localPdfStatuses.toMutableMap()
                                updated[cleanPdfId] = LocalPdfStatus.Error(err)
                                state.copy(localPdfStatuses = updated)
                            }
                        }
                        return@launch
                    }
                    val token = tokenRes.getOrThrow()
                    val config = _uiState.value.supabaseConfig

                    var lastEmittedPercentage = -1
                    var lastEmittedTimeMs = 0L

                    val downloadResult = SupabaseStorageService.downloadAndEncryptAuthenticatedPdf(
                        filePath = cleanFilePath,
                        accessToken = token,
                        destinationEncryptedFile = targetFile,
                        config = config,
                        onProgress = { bytesRead, totalBytes ->
                            val percentage = if (totalBytes > 0) {
                                ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            val now = System.currentTimeMillis()
                            if (percentage != lastEmittedPercentage || now - lastEmittedTimeMs >= 150L) {
                                lastEmittedPercentage = percentage
                                lastEmittedTimeMs = now
                                val curr = SessionManager.getSession()
                                if (curr?.userId?.trim() == sessionUserId && !isLoggingOut.get()) {
                                    _uiState.update { state ->
                                        val updated = state.localPdfStatuses.toMutableMap()
                                        updated[cleanPdfId] = LocalPdfStatus.Downloading(
                                            bytesDownloaded = bytesRead,
                                            totalBytes = totalBytes,
                                            progressPercentage = percentage
                                        )
                                        state.copy(localPdfStatuses = updated)
                                    }
                                }
                            }
                        }
                    )

                    // التحقق بعد عودة الدالة من أن الجلسة ما زالت تخص نفس المستخدم وأن عملية تسجيل الخروج ليست جارية
                    val postSession = SessionManager.getSession()
                    if (postSession?.userId?.trim() != sessionUserId || isLoggingOut.get()) {
                        if (targetFile.exists()) {
                            try {
                                val userSecureDir = getUserEncryptedStorageDir(context, sessionUserId)
                                PdfSecurityStorageService.deleteSecureFile(targetFile, userSecureDir)
                            } catch (_: Exception) {
                                targetFile.delete()
                            }
                        }
                        _uiState.update { state ->
                            val updated = state.localPdfStatuses.toMutableMap()
                            updated[cleanPdfId] = LocalPdfStatus.NotDownloaded
                            state.copy(localPdfStatuses = updated)
                        }
                        return@launch
                    }

                    if (downloadResult.isSuccess) {
                        val file = downloadResult.getOrThrow()
                        if (PdfSecurityStorageService.isEncryptedFilePresent(file)) {
                            val curr = SessionManager.getSession()
                            if (curr?.userId?.trim() == sessionUserId && !isLoggingOut.get()) {
                                _uiState.update { state ->
                                    val updated = state.localPdfStatuses.toMutableMap()
                                    updated[cleanPdfId] = LocalPdfStatus.Downloaded(file.length())
                                    state.copy(localPdfStatuses = updated)
                                }
                            }
                        } else {
                            val curr = SessionManager.getSession()
                            if (curr?.userId?.trim() == sessionUserId && !isLoggingOut.get()) {
                                _uiState.update { state ->
                                    val updated = state.localPdfStatuses.toMutableMap()
                                    updated[cleanPdfId] = LocalPdfStatus.Error("الملف المشفر الناتج غير صالح أو تالف")
                                    state.copy(localPdfStatuses = updated)
                                }
                            }
                        }
                    } else {
                        val curr = SessionManager.getSession()
                        if (curr?.userId?.trim() == sessionUserId && !isLoggingOut.get()) {
                            val err = downloadResult.exceptionOrNull()
                            _uiState.update { state ->
                                val updated = state.localPdfStatuses.toMutableMap()
                                updated[cleanPdfId] = LocalPdfStatus.Error(err?.message ?: "فشل تنزيل وتشفير الملف")
                                state.copy(localPdfStatuses = updated)
                            }
                        } else {
                            _uiState.update { state ->
                                val updated = state.localPdfStatuses.toMutableMap()
                                updated[cleanPdfId] = LocalPdfStatus.NotDownloaded
                                state.copy(localPdfStatuses = updated)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    if (targetFile.exists()) {
                        try {
                            val userSecureDir = getUserEncryptedStorageDir(context, sessionUserId)
                            PdfSecurityStorageService.deleteSecureFile(targetFile, userSecureDir)
                        } catch (_: Exception) {
                            targetFile.delete()
                        }
                    }
                    _uiState.update { state ->
                        val updated = state.localPdfStatuses.toMutableMap()
                        updated[cleanPdfId] = LocalPdfStatus.NotDownloaded
                        state.copy(localPdfStatuses = updated)
                    }
                    throw e
                } catch (e: Exception) {
                    if (targetFile.exists()) {
                        try {
                            val userSecureDir = getUserEncryptedStorageDir(context, sessionUserId)
                            PdfSecurityStorageService.deleteSecureFile(targetFile, userSecureDir)
                        } catch (_: Exception) {
                            targetFile.delete()
                        }
                    }
                    val curr = SessionManager.getSession()
                    if (curr?.userId?.trim() == sessionUserId && !isLoggingOut.get()) {
                        _uiState.update { state ->
                            val updated = state.localPdfStatuses.toMutableMap()
                            updated[cleanPdfId] = LocalPdfStatus.Error(e.message ?: "حدث خطأ غير متوقع أثناء التنزيل")
                            state.copy(localPdfStatuses = updated)
                        }
                    } else {
                        _uiState.update { state ->
                            val updated = state.localPdfStatuses.toMutableMap()
                            updated[cleanPdfId] = LocalPdfStatus.NotDownloaded
                            state.copy(localPdfStatuses = updated)
                        }
                    }
                } finally {
                    // إزالة مشروطة بهوية المهمة الدقيقة لعدم مسح أي مهمة جديدة بديلة
                    activeDownloadJobs.remove(jobKey, createdJob)
                }
            }

            val existing = activeDownloadJobs.putIfAbsent(jobKey, createdJob)
            if (existing == null) {
                isReserved = true
            } else {
                createdJob.cancel()
            }
        }

        if (!isReserved) {
            return
        }

        // بدء المهمة وتعيين الحالة الابتدائية إلى Downloading
        _uiState.update { state ->
            val updated = state.localPdfStatuses.toMutableMap()
            updated[cleanPdfId] = LocalPdfStatus.Downloading(0L, -1L, 0)
            state.copy(localPdfStatuses = updated)
        }

        createdJob.start()
    }

    /**
     * إلغاء تنزيل ملف الـ PDF الجاري وإلغاء طلب الشبكة فوراً وتنظيف أي ملفات مؤقتة
     * مع التقيد الصارم بمعرف المستخدم الحالي لمنع إلغاء مهام مستخدمين آخرين
     * ودون إزالة المهمة يدوياً من خريطة المهام النشطة لضمان انتهاء التنظيف أولاً
     */
    fun cancelLessonPdfDownload(pdfId: String) {
        val cleanPdfId = pdfId.trim()
        val currentUserId = SessionManager.getSession()?.userId?.trim().orEmpty()
        if (currentUserId.isBlank()) return

        val specificKey = buildDownloadJobKey(currentUserId, cleanPdfId)
        val activeJob = activeDownloadJobs[specificKey]

        if (activeJob != null) {
            activeJob.cancel(CancellationException("تم إلغاء تنزيل الملف بواسطة المستخدم"))
            // لا نحذف المهمة يدوياً من activeDownloadJobs، بل تترك لتزيل نفسها داخل finally
            // بعد اكتمال تنظيف الملف وحذفه لضمان عدم تسابق أي طلب تنزيل جديد
        }

        _uiState.update { state ->
            val updated = state.localPdfStatuses.toMutableMap()
            updated[cleanPdfId] = LocalPdfStatus.NotDownloaded
            state.copy(localPdfStatuses = updated)
        }
    }

    /**
     * حذف النسخة المشفرة المحلية للملف فقط دون أي استدعاء لخادم أو قاعدة بيانات Supabase
     */
    fun deleteLocalSecurePdf(context: Context, pdf: LessonPdf) {
        val cleanPdfId = pdf.id.trim()
        // أولاً: إلغاء التنزيل الجاري للملف إن وجد
        cancelLessonPdfDownload(cleanPdfId)

        val currentUserId = SessionManager.getSession()?.userId?.trim().orEmpty()
        if (currentUserId.isNotBlank()) {
            try {
                val targetFile = getLocalEncryptedPdfFile(context, currentUserId, cleanPdfId)
                if (targetFile.exists()) {
                    val userSecureDir = getUserEncryptedStorageDir(context, currentUserId)
                    PdfSecurityStorageService.deleteSecureFile(targetFile, userSecureDir)
                }
            } catch (_: Exception) {}
        }

        _uiState.update { state ->
            val updated = state.localPdfStatuses.toMutableMap()
            updated[cleanPdfId] = LocalPdfStatus.NotDownloaded
            state.copy(localPdfStatuses = updated)
        }
    }

    /**
     * التحقق من الملفات المشفرة المخزنة محلياً لدروس المادة ومزامنة حالتها في AppUiState
     * لمجلد المستخدم الحالي فقط
     */
    fun checkAndSyncLocalPdfStatuses(context: Context, pdfs: List<LessonPdf>) {
        val currentUserId = SessionManager.getSession()?.userId?.trim().orEmpty()
        if (currentUserId.isBlank()) return

        val newStatuses = mutableMapOf<String, LocalPdfStatus>()
        for (pdf in pdfs) {
            val cleanId = pdf.id.trim()
            try {
                UUID.fromString(cleanId)
                val file = getLocalEncryptedPdfFile(context, currentUserId, cleanId)
                if (PdfSecurityStorageService.isEncryptedFilePresent(file)) {
                    newStatuses[cleanId] = LocalPdfStatus.Downloaded(file.length())
                } else {
                    val jobKey = buildDownloadJobKey(currentUserId, cleanId)
                    if (!activeDownloadJobs.containsKey(jobKey)) {
                        newStatuses[cleanId] = LocalPdfStatus.NotDownloaded
                    }
                }
            } catch (_: Exception) {
                // تجاوز المعرفات غير المطابقة لـ UUID
            }
        }

        if (newStatuses.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(localPdfStatuses = state.localPdfStatuses + newStatuses)
            }
        }
    }
}
