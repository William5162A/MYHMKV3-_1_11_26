package com.example.data

import android.util.Log
import com.example.model.AcademicYear
import com.example.model.AdminTopic
import com.example.model.CourseChapter
import com.example.model.CourseLesson
import com.example.model.CourseUnit
import com.example.model.LessonPdf
import com.example.model.Semester
import com.example.model.Subject
import com.example.model.SupabaseConfig
import com.example.model.TopicAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SupabaseDatabaseService {
    private const val TAG = "SupabaseDbService"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(config: SupabaseConfig): Boolean {
        val cleanUrl = config.projectUrl.trim().trimEnd('/')
        return cleanUrl.startsWith("http") &&
                config.anonKey.isNotBlank()
    }

    // =========================================================================
    // 1. Academic Years, Semesters & Subjects (السنوات والفصول والمواد)
    // =========================================================================

    /**
     * جلب السنوات الدراسية من جدول public.academic_years
     */
    suspend fun fetchAcademicYears(
        accessToken: String? = null,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<AcademicYear>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }

        if (accessToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("يتطلب الوصول إلى السنوات الدراسية جلسة مسجلة صالحة"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/academic_years?select=*&order=id.asc"

            val token = accessToken.trim()
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<AcademicYear>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optInt("id")
                    val name = obj.optString("name")
                    list.add(AcademicYear(id = id, name = name))
                }
                Result.success(list)
            } else {
                val errorMsg = when (response.code) {
                    401 -> "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح (رمز HTTP: 401)"
                    403 -> "تم رفض الوصول: صلاحيات غير كافية لجلب السنوات الدراسية (رمز HTTP: 403)"
                    else -> "فشل جلب السنوات الدراسية من الخادم (رمز HTTP: ${response.code})"
                }
                Log.w(TAG, "$errorMsg - $responseBody")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchAcademicYears", e)
            val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                "تعذر الاتصال بالخادم، يرجى التحقق من اتصال الإنترنت"
            } else {
                e.message ?: "حدث خطأ غير متوقع أثناء جلب السنوات الدراسية"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    /**
     * جلب الفصول الدراسية من جدول public.semesters
     */
    suspend fun fetchSemesters(
        accessToken: String? = null,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<Semester>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }

        if (accessToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("يتطلب الوصول إلى الفصول الدراسية جلسة مسجلة صالحة"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/semesters?select=*&order=id.asc"

            val token = accessToken.trim()
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<Semester>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optInt("id")
                    val name = obj.optString("name")
                    list.add(Semester(id = id, name = name))
                }
                Result.success(list)
            } else {
                val errorMsg = when (response.code) {
                    401 -> "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح (رمز HTTP: 401)"
                    403 -> "تم رفض الوصول: صلاحيات غير كافية لجلب الفصول الدراسية (رمز HTTP: 403)"
                    else -> "فشل جلب الفصول الدراسية من الخادم (رمز HTTP: ${response.code})"
                }
                Log.w(TAG, "$errorMsg - $responseBody")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchSemesters", e)
            val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                "تعذر الاتصال بالخادم، يرجى التحقق من اتصال الإنترنت"
            } else {
                e.message ?: "حدث خطأ غير متوقع أثناء جلب الفصول الدراسية"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    /**
     * جلب المواد الدراسية من جدول public.subjects
     * وفق سياسات RLS:
     * - المشرف (Admin Token) يستقبل جميع المواد الـ 59 متضمنة غير المنشورة
     * - الطالب (Student Token) يستقبل المواد المنشورة فقط (is_published = true)
     */
    suspend fun fetchSubjects(
        accessToken: String? = null,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<Subject>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }

        if (accessToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("يتطلب الوصول إلى جدول المواد جلسة مسجلة صالحة"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/subjects?select=*&order=academic_year.asc,semester.asc,name.asc"

            val token = accessToken.trim()
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<Subject>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id") // Treat subject ID as TEXT
                    val name = obj.optString("name")
                    val academicYear = obj.optInt("academic_year", 1)
                    val semester = obj.optInt("semester", 1)
                    val pricePoints = obj.optInt("price_points", 0)
                    val description = obj.optString("description", "")
                    val isPublished = obj.optBoolean("is_published", false)
                    val createdAt = obj.optString("created_at", "")

                    list.add(
                        Subject(
                            id = id,
                            name = name,
                            academicYear = academicYear,
                            semester = semester,
                            pricePoints = pricePoints,
                            description = description,
                            isPublished = isPublished,
                            createdAt = createdAt
                        )
                    )
                }
                Result.success(list)
            } else {
                val errorMsg = when (response.code) {
                    401 -> "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح (رمز HTTP: 401)"
                    403 -> "تم رفض الوصول: صلاحيات غير كافية لجلب المواد الدراسية (رمز HTTP: 403)"
                    else -> "فشل جلب المواد من الخادم (رمز HTTP: ${response.code})"
                }
                Log.w(TAG, "$errorMsg - $responseBody")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchSubjects", e)
            val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                "تعذر الاتصال بالخادم، يرجى التحقق من اتصال الإنترنت"
            } else {
                e.message ?: "حدث خطأ غير متوقع أثناء تحميل المواد"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    /**
     * تحديث عمود is_published فقط لمادة معينة في جدول public.subjects
     * متاح للمشرف فقط عبر Bearer Token الخاص به
     */
    suspend fun updateSubjectPublishStatus(
        subjectId: String,
        isPublished: Boolean,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز وصول المشرف غير متوفر"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/subjects?id=eq.$subjectId"

            val jsonBody = JSONObject().apply {
                put("is_published", isPublished)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .patch(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = if (response.code == 403 || response.code == 401) {
                    "ليس لديك صلاحية المشرف لتعديل حالة نشر المادة (رمز HTTP: ${response.code})"
                } else {
                    "فشل تحديث حالة نشر المادة في الخادم (رمز HTTP: ${response.code})"
                }
                Log.w(TAG, "$errorMsg - $responseBody")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in updateSubjectPublishStatus", e)
            Result.failure(e)
        }
    }

    private fun String?.isNullExOrBlank(): Boolean = this == null || this.trim().isEmpty()

    // Backward-compatible wrappers for legacy callers
    suspend fun fetchPublishedSubjects(config: SupabaseConfig): Result<Set<String>> {
        return fetchSubjects(config = config).map { list ->
            list.filter { it.isPublished }.map { it.id }.toSet()
        }
    }

    suspend fun syncPublishStatus(
        subjectId: String,
        isPublished: Boolean,
        config: SupabaseConfig
    ): Result<Unit> {
        val session = SessionManager.getSession()
        return updateSubjectPublishStatus(
            subjectId = subjectId,
            isPublished = isPublished,
            accessToken = session?.accessToken.orEmpty(),
            config = config
        )
    }

    // =========================================================================
    // 2. Custom Topics / Lectures Synchronization (تزامن عناوين ومحتويات المواد)
    // =========================================================================

    suspend fun fetchAllTopics(config: SupabaseConfig): Result<Map<String, List<AdminTopic>>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/topics?select=*&order=created_at.asc"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${config.anonKey.trim()}")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string().orEmpty()
                val jsonArray = JSONArray(bodyStr)
                val map = mutableMapOf<String, MutableList<AdminTopic>>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id")
                    val subjectId = obj.optString("subject_id")
                    val title = obj.optString("title")
                    val desc = obj.optString("description")
                    val createdAt = obj.optLong("created_at", System.currentTimeMillis())
                    val attachmentsStr = obj.optString("attachments_json", "[]")

                    val attachments = parseAttachmentsJson(attachmentsStr)

                    val topic = AdminTopic(
                        id = id,
                        subjectId = subjectId,
                        title = title,
                        description = desc,
                        attachments = attachments,
                        createdAt = createdAt
                    )

                    val list = map.getOrPut(subjectId) { mutableListOf() }
                    list.add(topic)
                }

                Result.success(map)
            } else {
                val errorMsg = response.body?.string() ?: "خطأ HTTP ${response.code}"
                Result.failure(Exception("فشل جلب العناوين: $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchAllTopics", e)
            Result.failure(e)
        }
    }

    suspend fun insertTopic(
        topic: AdminTopic,
        config: SupabaseConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.success(Unit)
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/topics"

            val jsonBody = JSONObject().apply {
                put("id", topic.id)
                put("subject_id", topic.subjectId)
                put("title", topic.title)
                put("description", topic.description)
                put("attachments_json", serializeAttachmentsToJson(topic.attachments))
                put("created_at", topic.createdAt)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${config.anonKey.trim()}")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = response.body?.string() ?: "خطأ ${response.code}"
                Log.w(TAG, "Failed to insert topic on Supabase: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in insertTopic", e)
            Result.failure(e)
        }
    }

    suspend fun deleteTopic(
        topicId: String,
        config: SupabaseConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.success(Unit)
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/topics?id=eq.$topicId"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${config.anonKey.trim()}")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = response.body?.string() ?: "خطأ ${response.code}"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteTopic", e)
            Result.failure(e)
        }
    }

    // =========================================================================
    // Helpers: JSON Serialization of Attachments
    // =========================================================================

    private fun serializeAttachmentsToJson(attachments: List<TopicAttachment>): String {
        val array = JSONArray()
        for (att in attachments) {
            val obj = JSONObject().apply {
                put("id", att.id)
                put("name", att.name)
                put("sizeFormatted", att.sizeFormatted)
                put("supabaseUrl", att.supabaseUrl)
                put("mimeType", att.mimeType)
                put("uploadDate", att.uploadDate)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseAttachmentsJson(jsonStr: String): List<TopicAttachment> {
        val list = mutableListOf<TopicAttachment>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TopicAttachment(
                        id = obj.optString("id"),
                        name = obj.optString("name", "ملف.pdf"),
                        sizeFormatted = obj.optString("sizeFormatted", "PDF"),
                        supabaseUrl = obj.optString("supabaseUrl"),
                        mimeType = obj.optString("mimeType", "application/pdf"),
                        uploadDate = obj.optString("uploadDate", "")
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    // =========================================================================
    // 3. Course Hierarchy: Units, Chapters, Lessons & PDF Metadata
    // =========================================================================

    // -------------------------------------------------------------------------
    // 3.1 Course Units (الوحدات التعليمية)
    // -------------------------------------------------------------------------

    suspend fun fetchUnitsForSubject(
        subjectId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<CourseUnit>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول (accessToken) مطلوب لجلب الوحدات الدراسية"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_units?subject_id=eq.$subjectId&select=*&order=order_index.asc"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<CourseUnit>()
                for (i in 0 until jsonArray.length()) {
                    list.add(parseCourseUnit(jsonArray.getJSONObject(i)))
                }
                Result.success(list)
            } else {
                val errorMsg = mapHttpError(response.code, "جلب الوحدات الدراسية", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchUnitsForSubject", e)
            Result.failure(mapNetworkException(e, "فشل جلب الوحدات الدراسية"))
        }
    }

    suspend fun createCourseUnit(
        subjectId: String,
        title: String,
        description: String? = null,
        orderIndex: Int = 1,
        isPublished: Boolean = true,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<CourseUnit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لإنشاء وحدة دراسية"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_units"

            val jsonBody = JSONObject().apply {
                put("subject_id", subjectId.trim())
                put("title", title.trim())
                if (!description.isNullOrBlank()) {
                    put("description", description.trim())
                } else {
                    put("description", JSONObject.NULL)
                }
                put("order_index", orderIndex)
                put("is_published", isPublished)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                val unit = parseSingleRepresentation(responseBody) { parseCourseUnit(it) }
                if (unit != null) {
                    Result.success(unit)
                } else {
                    Result.failure(Exception("لم يُرجع الخادم بيانات الوحدة المُنشأة"))
                }
            } else {
                val errorMsg = mapHttpError(response.code, "إنشاء الوحدة الدراسية", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in createCourseUnit", e)
            Result.failure(mapNetworkException(e, "فشل إنشاء الوحدة الدراسية"))
        }
    }

    suspend fun updateCourseUnit(
        unitId: String,
        title: String? = null,
        description: String? = null,
        clearDescription: Boolean = false,
        orderIndex: Int? = null,
        isPublished: Boolean? = null,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لتحديث الوحدة الدراسية"))
        }

        try {
            val jsonBody = JSONObject().apply {
                if (title != null) put("title", title.trim())
                if (clearDescription) {
                    put("description", JSONObject.NULL)
                } else if (description != null) {
                    put("description", description.trim())
                }
                if (orderIndex != null) put("order_index", orderIndex)
                if (isPublished != null) put("is_published", isPublished)
            }

            if (jsonBody.length() == 0) {
                return@withContext Result.success(Unit)
            }

            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_units?id=eq.$unitId"

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .patch(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapHttpError(response.code, "تحديث الوحدة الدراسية", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in updateCourseUnit", e)
            Result.failure(mapNetworkException(e, "فشل تحديث الوحدة الدراسية"))
        }
    }

    suspend fun deleteCourseUnit(
        unitId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لحذف الوحدة الدراسية"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_units?id=eq.$unitId"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapHttpError(response.code, "حذف الوحدة الدراسية", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteCourseUnit", e)
            Result.failure(mapNetworkException(e, "فشل حذف الوحدة الدراسية"))
        }
    }

    // -------------------------------------------------------------------------
    // 3.2 Course Chapters (الفصول التعليمية)
    // -------------------------------------------------------------------------

    suspend fun fetchChaptersForUnit(
        unitId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<CourseChapter>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لجلب فصول الوحدة"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_chapters?unit_id=eq.$unitId&select=*&order=order_index.asc"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<CourseChapter>()
                for (i in 0 until jsonArray.length()) {
                    list.add(parseCourseChapter(jsonArray.getJSONObject(i)))
                }
                Result.success(list)
            } else {
                val errorMsg = mapHttpError(response.code, "جلب فصول الوحدة", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchChaptersForUnit", e)
            Result.failure(mapNetworkException(e, "فشل جلب فصول الوحدة"))
        }
    }

    suspend fun createCourseChapter(
        unitId: String,
        subjectId: String,
        title: String,
        description: String? = null,
        orderIndex: Int = 1,
        isPublished: Boolean = true,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<CourseChapter> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لإنشاء فصل دراسي"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_chapters"

            val jsonBody = JSONObject().apply {
                put("unit_id", unitId.trim())
                put("subject_id", subjectId.trim())
                put("title", title.trim())
                if (!description.isNullOrBlank()) {
                    put("description", description.trim())
                } else {
                    put("description", JSONObject.NULL)
                }
                put("order_index", orderIndex)
                put("is_published", isPublished)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                val chapter = parseSingleRepresentation(responseBody) { parseCourseChapter(it) }
                if (chapter != null) {
                    Result.success(chapter)
                } else {
                    Result.failure(Exception("لم يُرجع الخادم بيانات الفصل المُنشأ"))
                }
            } else {
                val errorMsg = mapHttpError(response.code, "إنشاء الفصل الدراسي", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in createCourseChapter", e)
            Result.failure(mapNetworkException(e, "فشل إنشاء الفصل الدراسي"))
        }
    }

    suspend fun updateCourseChapter(
        chapterId: String,
        title: String? = null,
        description: String? = null,
        clearDescription: Boolean = false,
        orderIndex: Int? = null,
        isPublished: Boolean? = null,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لتحديث الفصل الدراسي"))
        }

        try {
            val jsonBody = JSONObject().apply {
                if (title != null) put("title", title.trim())
                if (clearDescription) {
                    put("description", JSONObject.NULL)
                } else if (description != null) {
                    put("description", description.trim())
                }
                if (orderIndex != null) put("order_index", orderIndex)
                if (isPublished != null) put("is_published", isPublished)
            }

            if (jsonBody.length() == 0) {
                return@withContext Result.success(Unit)
            }

            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_chapters?id=eq.$chapterId"

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .patch(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapHttpError(response.code, "تحديث الفصل الدراسي", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in updateCourseChapter", e)
            Result.failure(mapNetworkException(e, "فشل تحديث الفصل الدراسي"))
        }
    }

    suspend fun deleteCourseChapter(
        chapterId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لحذف الفصل الدراسي"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_chapters?id=eq.$chapterId"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapHttpError(response.code, "حذف الفصل الدراسي", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteCourseChapter", e)
            Result.failure(mapNetworkException(e, "فشل حذف الفصل الدراسي"))
        }
    }

    // -------------------------------------------------------------------------
    // 3.3 Course Lessons (الدروس التعليمية)
    // -------------------------------------------------------------------------

    suspend fun fetchLessonsForChapter(
        chapterId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<CourseLesson>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لجلب دروس الفصل"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_lessons?chapter_id=eq.$chapterId&select=*&order=order_index.asc"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<CourseLesson>()
                for (i in 0 until jsonArray.length()) {
                    list.add(parseCourseLesson(jsonArray.getJSONObject(i)))
                }
                Result.success(list)
            } else {
                val errorMsg = mapHttpError(response.code, "جلب دروس الفصل", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchLessonsForChapter", e)
            Result.failure(mapNetworkException(e, "فشل جلب دروس الفصل"))
        }
    }

    suspend fun fetchDirectLessonsForUnit(
        unitId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<CourseLesson>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لجلب دروس الوحدة المباشرة"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_lessons?unit_id=eq.$unitId&chapter_id=is.null&select=*&order=order_index.asc"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<CourseLesson>()
                for (i in 0 until jsonArray.length()) {
                    list.add(parseCourseLesson(jsonArray.getJSONObject(i)))
                }
                Result.success(list)
            } else {
                val errorMsg = mapHttpError(response.code, "جلب دروس الوحدة المباشرة", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchDirectLessonsForUnit", e)
            Result.failure(mapNetworkException(e, "فشل جلب دروس الوحدة المباشرة"))
        }
    }

    suspend fun createCourseLesson(
        unitId: String,
        subjectId: String,
        chapterId: String? = null,
        title: String,
        summary: String? = null,
        durationMinutes: Int? = null,
        orderIndex: Int = 1,
        isPublished: Boolean = true,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<CourseLesson> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لإنشاء درس"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_lessons"

            val jsonBody = JSONObject().apply {
                put("unit_id", unitId.trim())
                put("subject_id", subjectId.trim())
                if (!chapterId.isNullOrBlank()) {
                    put("chapter_id", chapterId.trim())
                } else {
                    put("chapter_id", JSONObject.NULL)
                }
                put("title", title.trim())
                if (!summary.isNullOrBlank()) {
                    put("summary", summary.trim())
                } else {
                    put("summary", JSONObject.NULL)
                }
                if (durationMinutes != null) {
                    put("duration_minutes", durationMinutes)
                } else {
                    put("duration_minutes", JSONObject.NULL)
                }
                put("order_index", orderIndex)
                put("is_published", isPublished)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                val lesson = parseSingleRepresentation(responseBody) { parseCourseLesson(it) }
                if (lesson != null) {
                    Result.success(lesson)
                } else {
                    Result.failure(Exception("لم يُرجع الخادم بيانات الدرس المُنشأ"))
                }
            } else {
                val errorMsg = mapHttpError(response.code, "إنشاء الدرس التعليمي", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in createCourseLesson", e)
            Result.failure(mapNetworkException(e, "فشل إنشاء الدرس التعليمي"))
        }
    }

    suspend fun updateCourseLesson(
        lessonId: String,
        title: String? = null,
        summary: String? = null,
        clearSummary: Boolean = false,
        durationMinutes: Int? = null,
        clearDuration: Boolean = false,
        chapterId: String? = null,
        clearChapterId: Boolean = false,
        orderIndex: Int? = null,
        isPublished: Boolean? = null,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لتحديث الدرس التعليمي"))
        }

        try {
            val jsonBody = JSONObject().apply {
                if (title != null) put("title", title.trim())
                if (clearSummary) {
                    put("summary", JSONObject.NULL)
                } else if (summary != null) {
                    put("summary", summary.trim())
                }
                if (clearDuration) {
                    put("duration_minutes", JSONObject.NULL)
                } else if (durationMinutes != null) {
                    put("duration_minutes", durationMinutes)
                }
                if (clearChapterId) {
                    put("chapter_id", JSONObject.NULL)
                } else if (chapterId != null) {
                    put("chapter_id", chapterId.trim())
                }
                if (orderIndex != null) put("order_index", orderIndex)
                if (isPublished != null) put("is_published", isPublished)
            }

            if (jsonBody.length() == 0) {
                return@withContext Result.success(Unit)
            }

            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_lessons?id=eq.$lessonId"

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .patch(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapHttpError(response.code, "تحديث الدرس التعليمي", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in updateCourseLesson", e)
            Result.failure(mapNetworkException(e, "فشل تحديث الدرس التعليمي"))
        }
    }

    suspend fun deleteCourseLesson(
        lessonId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لحذف الدرس التعليمي"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/course_lessons?id=eq.$lessonId"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapHttpError(response.code, "حذف الدرس التعليمي", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteCourseLesson", e)
            Result.failure(mapNetworkException(e, "فشل حذف الدرس التعليمي"))
        }
    }

    // -------------------------------------------------------------------------
    // 3.4 Lesson PDFs (ملفات الـ PDF للدروس - بيانات وصفية)
    // -------------------------------------------------------------------------

    suspend fun fetchPdfsForLesson(
        lessonId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<List<LessonPdf>> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لجلب ملفات الدرس"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/lesson_pdfs?lesson_id=eq.$lessonId&select=*&order=order_index.asc"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = JSONArray(responseBody)
                val list = mutableListOf<LessonPdf>()
                for (i in 0 until jsonArray.length()) {
                    list.add(parseLessonPdf(jsonArray.getJSONObject(i)))
                }
                Result.success(list)
            } else {
                val errorMsg = mapHttpError(response.code, "جلب ملفات الدرس", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchPdfsForLesson", e)
            Result.failure(mapNetworkException(e, "فشل جلب ملفات الدرس"))
        }
    }

    suspend fun insertLessonPdf(
        lessonId: String,
        subjectId: String,
        title: String,
        filePath: String,
        fileSizeBytes: Long? = null,
        mimeType: String = "application/pdf",
        orderIndex: Int = 0,
        isPublished: Boolean = false,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<LessonPdf> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول (accessToken) مطلوب لإضافة ملف للدرس"))
        }
        if (lessonId.isBlank()) {
            return@withContext Result.failure(Exception("معرف الدرس (lessonId) مطلوب ولا يمكن أن يكون فارغاً"))
        }
        if (subjectId.isBlank()) {
            return@withContext Result.failure(Exception("معرف المادة (subjectId) مطلوب ولا يمكن أن يكون فارغاً"))
        }
        if (title.isBlank()) {
            return@withContext Result.failure(Exception("عنوان ملف الـ PDF مطلوب ولا يمكن أن يكون فارغاً"))
        }
        if (filePath.isBlank()) {
            return@withContext Result.failure(Exception("مسار الملف (filePath) مطلوب ولا يمكن أن يكون فارغاً"))
        }
        if (!filePath.endsWith(".pdf", ignoreCase = true)) {
            return@withContext Result.failure(Exception("مسار الملف يجب أن ينتهي بامتداد .pdf"))
        }
        if (!mimeType.equals("application/pdf", ignoreCase = true)) {
            return@withContext Result.failure(Exception("نوع الملف غير مسموح به ($mimeType)، يجب أن يكون application/pdf"))
        }
        if (fileSizeBytes != null) {
            val maxBytes = 50L * 1024L * 1024L
            if (fileSizeBytes <= 0L || fileSizeBytes > maxBytes) {
                return@withContext Result.failure(
                    Exception("حجم الملف ($fileSizeBytes بايت) غير صالح؛ يجب أن يكون أكبر من 0 ولا يتجاوز 50 ميغابايت")
                )
            }
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/lesson_pdfs"

            val jsonBody = JSONObject().apply {
                put("lesson_id", lessonId)
                put("subject_id", subjectId)
                put("title", title.trim())
                put("file_path", filePath)
                if (fileSizeBytes != null) {
                    put("file_size_bytes", fileSizeBytes)
                } else {
                    put("file_size_bytes", JSONObject.NULL)
                }
                put("mime_type", mimeType)
                put("order_index", orderIndex)
                put("is_published", isPublished)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful || response.code in 200..299) {
                    val created = parseSingleRepresentation(responseBody, ::parseLessonPdf)
                    if (created != null) {
                        Result.success(created)
                    } else {
                        Result.failure(Exception("تعذر استخراج بيانات الملف المضاف من استجابة الخادم"))
                    }
                } else {
                    val errorMsg = mapHttpError(response.code, "إضافة ملف للدرس", responseBody)
                    Log.w(TAG, errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in insertLessonPdf", e)
            Result.failure(mapNetworkException(e, "فشل إضافة ملف للدرس"))
        }
    }

    suspend fun deleteLessonPdf(
        pdfId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لحذف سجل ملف الدرس"))
        }
        val cleanId = pdfId.trim()
        if (cleanId.isBlank()) {
            return@withContext Result.failure(Exception("معرف السجل (pdfId) مطلوب ولا يمكن أن يكون فارغاً"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/lesson_pdfs?id=eq.$cleanId"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Prefer", "return=representation")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful || response.code in 200..299) {
                    val trimmed = responseBody.trim()
                    if (trimmed.startsWith("[")) {
                        try {
                            val jsonArray = JSONArray(trimmed)
                            if (jsonArray.length() == 0) {
                                Result.failure(
                                    Exception("لم يتم العثور على سجل الملف المطلوب أو لا تملك الصلاحية الكافية لحذفه")
                                )
                            } else {
                                Result.success(Unit)
                            }
                        } catch (e: Exception) {
                            Result.failure(Exception("فشل تحليل استجابة تأكيد الحذف من الخادم: ${e.message}"))
                        }
                    } else {
                        Result.failure(Exception("استجابة غير متوقعة من الخادم لتأكيد الحذف: $trimmed"))
                    }
                } else {
                    val errorMsg = mapHttpError(response.code, "حذف سجل ملف الدرس", responseBody)
                    Log.w(TAG, errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in deleteLessonPdf", e)
            Result.failure(mapNetworkException(e, "فشل حذف سجل ملف الدرس"))
        }
    }

    // -------------------------------------------------------------------------
    // 3.5 Parsers & Error Mapping Helpers
    // -------------------------------------------------------------------------

    private fun parseCourseUnit(obj: JSONObject): CourseUnit {
        return CourseUnit(
            id = obj.optString("id"),
            subjectId = obj.optString("subject_id"),
            title = obj.optString("title"),
            description = if (obj.isNull("description")) null else obj.optString("description"),
            orderIndex = obj.optInt("order_index", 1),
            isPublished = obj.optBoolean("is_published", true),
            createdAt = obj.optString("created_at"),
            updatedAt = obj.optString("updated_at")
        )
    }

    private fun parseCourseChapter(obj: JSONObject): CourseChapter {
        return CourseChapter(
            id = obj.optString("id"),
            unitId = obj.optString("unit_id"),
            subjectId = obj.optString("subject_id"),
            title = obj.optString("title"),
            description = if (obj.isNull("description")) null else obj.optString("description"),
            orderIndex = obj.optInt("order_index", 1),
            isPublished = obj.optBoolean("is_published", true),
            createdAt = obj.optString("created_at"),
            updatedAt = obj.optString("updated_at")
        )
    }

    private fun parseCourseLesson(obj: JSONObject): CourseLesson {
        return CourseLesson(
            id = obj.optString("id"),
            unitId = obj.optString("unit_id"),
            subjectId = obj.optString("subject_id"),
            chapterId = if (obj.isNull("chapter_id")) null else obj.optString("chapter_id"),
            title = obj.optString("title"),
            summary = if (obj.isNull("summary")) null else obj.optString("summary"),
            durationMinutes = if (obj.isNull("duration_minutes")) null else obj.optInt("duration_minutes"),
            orderIndex = obj.optInt("order_index", 1),
            isPublished = obj.optBoolean("is_published", true),
            createdAt = obj.optString("created_at"),
            updatedAt = obj.optString("updated_at")
        )
    }

    private fun parseLessonPdf(obj: JSONObject): LessonPdf {
        return LessonPdf(
            id = obj.optString("id"),
            lessonId = obj.optString("lesson_id"),
            subjectId = obj.optString("subject_id"),
            title = obj.optString("title"),
            filePath = obj.optString("file_path"),
            fileSizeBytes = if (obj.isNull("file_size_bytes")) null else obj.optLong("file_size_bytes"),
            mimeType = obj.optString("mime_type", "application/pdf"),
            orderIndex = obj.optInt("order_index", 1),
            isPublished = obj.optBoolean("is_published", true),
            createdAt = obj.optString("created_at"),
            updatedAt = obj.optString("updated_at")
        )
    }

    private fun <T> parseSingleRepresentation(responseBody: String, parser: (JSONObject) -> T): T? {
        val trimmed = responseBody.trim()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                if (arr.length() > 0) parser(arr.getJSONObject(0)) else null
            } else if (trimmed.startsWith("{")) {
                parser(JSONObject(trimmed))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapHttpError(code: Int, operation: String, responseBody: String): String {
        return when (code) {
            401 -> "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح (رمز HTTP: 401)"
            403 -> "تم رفض الوصول: صلاحيات غير كافية لتنفيذ $operation (رمز HTTP: 403)"
            404 -> "السجل المطلوب غير موجود أثناء $operation (رمز HTTP: 404)"
            409 -> "تعارض في البيانات أو قيد تكرار أثناء $operation (رمز HTTP: 409)"
            else -> "فشل $operation من الخادم (رمز HTTP: $code)"
        }
    }

    private fun mapNetworkException(e: Exception, fallbackMsg: String): Exception {
        val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
            "تعذر الاتصال بالخادم، يرجى التحقق من اتصال الإنترنت"
        } else {
            e.message ?: fallbackMsg
        }
        return Exception(friendlyMsg, e)
    }
}

