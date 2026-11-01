package com.example.data

import android.util.Log
import com.example.model.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed class AuthResult {
    data class Success(val session: AuthSession) : AuthResult()
    data class RequireEmailConfirmation(val email: String, val message: String) : AuthResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : AuthResult()
}

sealed class DeepLinkAuthResult {
    data class SessionAcquired(val session: AuthSession) : DeepLinkAuthResult()
    data class EmailConfirmedNeedLogin(val message: String) : DeepLinkAuthResult()
    data class Error(val message: String) : DeepLinkAuthResult()
    object Ignored : DeepLinkAuthResult()
}

data class UserProfile(
    val id: String,
    val username: String = "",
    val displayName: String = "",
    val academicYear: Int = 1,
    val semester: Int = 1,
    val role: String = "student"
) {
    val isAdmin: Boolean
        get() = role.trim().equals("admin", ignoreCase = true)
}


object SupabaseAuthService {
    private const val TAG = "SupabaseAuthService"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun isConfigured(config: SupabaseConfig): Boolean {
        val cleanUrl = config.projectUrl.trim().trimEnd('/')
        return cleanUrl.startsWith("http") && config.anonKey.isNotBlank()
    }

    /**
     * تسجيل الدخول بالبريد الإلكتروني وكلمة المرور عبر Supabase Auth REST API
     */
    suspend fun signInWithEmail(
        email: String,
        pass: String,
        config: SupabaseConfig = SupabaseConfig()
    ): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext AuthResult.Error("إعدادات Supabase غير مهيأة بعد")
        }

        val cleanEmail = email.trim()
        val cleanPass = pass.trim()

        if (cleanEmail.isEmpty() || cleanPass.isEmpty()) {
            return@withContext AuthResult.Error("يرجى إدخال البريد الإلكتروني وكلمة المرور")
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/auth/v1/token?grant_type=password"

            val jsonBody = JSONObject().apply {
                put("email", cleanEmail)
                put("password", cleanPass)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token")
                val expiresIn = json.optLong("expires_in", 3600L)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id").orEmpty()
                val userEmail = userObj?.optString("email", cleanEmail).orEmpty()

                val metadata = userObj?.optJSONObject("user_metadata")
                val displayName = metadata?.optString("display_name")
                    ?: metadata?.optString("username")
                    ?: cleanEmail.substringBefore("@")

                val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

                val session = AuthSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    userId = userId,
                    email = userEmail,
                    displayName = displayName,
                    expiresAt = expiresAt
                )

                AuthResult.Success(session)
            } else {
                val errorMsg = parseAuthError(response.code, responseBody)
                AuthResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * إنشاء حساب جديد باستخدام البريد الإلكتروني وكلمة المرور
     * مع إرسال username و display_name ضمن metadata ليقوم trigger بتعبئة جدول profiles تلقائياً
     */
    suspend fun signUpWithEmail(
        email: String,
        pass: String,
        username: String,
        displayName: String,
        config: SupabaseConfig = SupabaseConfig()
    ): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext AuthResult.Error("إعدادات Supabase غير مهيأة بعد")
        }

        val cleanEmail = email.trim()
        val cleanPass = pass.trim()
        val cleanUser = username.trim()
        val cleanName = displayName.trim()

        if (cleanEmail.isEmpty() || cleanPass.isEmpty()) {
            return@withContext AuthResult.Error("يرجى إدخال البريد الإلكتروني وكلمة المرور")
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val callbackUrl = "midad://auth-callback"
            val encodedRedirect = java.net.URLEncoder.encode(callbackUrl, "UTF-8")
            val endpoint = "$cleanUrl/auth/v1/signup?redirect_to=$encodedRedirect"

            val userData = JSONObject().apply {
                put("display_name", if (cleanName.isNotEmpty()) cleanName else cleanUser)
                put("username", cleanUser)
            }

            val options = JSONObject().apply {
                put("email_redirect_to", callbackUrl)
            }

            val jsonBody = JSONObject().apply {
                put("email", cleanEmail)
                put("password", cleanPass)
                put("data", userData)
                put("options", options)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val accessToken = json.optString("access_token", "")
                val refreshToken = json.optString("refresh_token", "")

                if (accessToken.isNotEmpty()) {
                    val expiresIn = json.optLong("expires_in", 3600L)
                    val userObj = json.optJSONObject("user")
                    val userId = userObj?.optString("id", json.optString("id")).orEmpty()
                    val userEmail = userObj?.optString("email", cleanEmail).orEmpty()
                    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

                    val session = AuthSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        userId = userId,
                        email = userEmail,
                        displayName = if (cleanName.isNotEmpty()) cleanName else cleanUser,
                        expiresAt = expiresAt
                    )
                    AuthResult.Success(session)
                } else {
                    // تفعيل تأكيد البريد الإلكتروني في Supabase يتطلب من المستخدم تأكيد حسابه أولاً
                    AuthResult.RequireEmailConfirmation(
                        email = cleanEmail,
                        message = "تم إنشاء الحساب بنجاح. أرسلنا رسالة تأكيد إلى بريدك الإلكتروني ($cleanEmail). يرجى فتح الرسالة وتأكيد الحساب قبل تسجيل الدخول."
                    )
                }
            } else {
                val errorMsg = parseAuthError(response.code, responseBody)
                AuthResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * تجديد رمز الجلسة باستخدام refresh_token
     */
    suspend fun refreshToken(
        refreshToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): AuthResult = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext AuthResult.Error("إعدادات Supabase غير مهيأة بعد")
        }

        if (refreshToken.isBlank()) {
            return@withContext AuthResult.Error("رمز التحديث مفقود")
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/auth/v1/token?grant_type=refresh_token"

            val jsonBody = JSONObject().apply {
                put("refresh_token", refreshToken)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val newAccessToken = json.optString("access_token")
                val newRefreshToken = json.optString("refresh_token", refreshToken)
                val expiresIn = json.optLong("expires_in", 3600L)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id").orEmpty()
                val userEmail = userObj?.optString("email").orEmpty()

                val metadata = userObj?.optJSONObject("user_metadata")
                val displayName = metadata?.optString("display_name")
                    ?: metadata?.optString("username")
                    ?: userEmail.substringBefore("@")

                val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)

                val session = AuthSession(
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    userId = userId,
                    email = userEmail,
                    displayName = displayName,
                    expiresAt = expiresAt
                )

                AuthResult.Success(session)
            } else {
                AuthResult.Error("فشل تجديد الجلسة. يرجى تسجيل الدخول مجددًا.")
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    /**
     * تسجيل الخروج وإلغاء الجلسة لدى Supabase
     */
    suspend fun signOut(
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config) || accessToken.isBlank()) {
            return@withContext Result.success(Unit)
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/auth/v1/logout"

            val emptyBody = "".toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $accessToken")
                .post(emptyBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful || response.code == 204) {
                Result.success(Unit)
            } else {
                Log.w(TAG, "Sign out response status: ${response.code}")
                Result.success(Unit) // Local sign out still proceeds
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during remote sign out: ${e.message}")
            Result.success(Unit)
        }
    }

    /**
     * جلب سجل المستخدم من جدول public.profiles باستخدام معرف المستخدم و JWT الخاص به
     */
    suspend fun fetchUserProfile(
        userId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (userId.isBlank() || accessToken.isBlank()) {
            return@withContext Result.failure(Exception("معرف المستخدم أو رمز الوصول مفقود"))
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/rest/v1/profiles?id=eq.$userId&select=*"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                val jsonArray = org.json.JSONArray(responseBody)
                if (jsonArray.length() == 0) {
                    return@withContext Result.failure(Exception("لم يتم العثور على سجل في جدول profiles للمستخدم $userId"))
                }
                val obj = jsonArray.getJSONObject(0)
                val id = obj.optString("id", userId)
                val username = obj.optString("username", "")
                val displayName = obj.optString("display_name", "")
                val academicYear = obj.optInt("academic_year", 1)
                val semester = obj.optInt("semester", 1)
                val role = obj.optString("role", "student")

                val profile = UserProfile(
                    id = id,
                    username = username,
                    displayName = displayName,
                    academicYear = academicYear,
                    semester = semester,
                    role = role
                )
                Result.success(profile)
            } else {
                val errorMsg = "فشل استعلام جدول profiles من الخادم (رمز HTTP: ${response.code})"
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception in fetchUserProfile: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * معالجة رابط العودة من تأكيد البريد (midad://auth-callback)
     */
    suspend fun handleDeepLinkCallback(
        uri: android.net.Uri,
        config: SupabaseConfig = SupabaseConfig()
    ): DeepLinkAuthResult = withContext(Dispatchers.IO) {
        if (uri.scheme != "midad" || uri.host != "auth-callback") {
            return@withContext DeepLinkAuthResult.Ignored
        }

        try {
            val params = parseUriParams(uri)

            // 1. التحقق من أخطاء الرابط (مثل انتهاء الصلاحية)
            val error = params["error"]
            val errorDesc = params["error_description"]
            if (!error.isNullOrBlank() || !errorDesc.isNullOrBlank()) {
                val fullError = errorDesc ?: error ?: "فشل التحقق من الرابط"
                val friendlyMsg = when {
                    fullError.contains("expired", ignoreCase = true) || fullError.contains("invalid", ignoreCase = true) ->
                        "رابط تأكيد البريد الإلكتروني غير صالح أو انتهت صلاحيته. يرجى طلب رابط جديد أو تسجيل الدخول."
                    fullError.contains("access_denied", ignoreCase = true) ->
                        "تم إلغاء أو رفض طلب التحقق من الرابط."
                    else ->
                        "حدث خطأ أثناء تأكيد البريد الإلكتروني: $fullError"
                }
                return@withContext DeepLinkAuthResult.Error(friendlyMsg)
            }

            // 2. التحقق من وجود access_token و refresh_token (صيغة Fragment أو Query)
            val accessToken = params["access_token"]
            val refreshToken = params["refresh_token"]
            val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600L

            if (!accessToken.isNullOrBlank()) {
                val userRes = fetchUserFromToken(accessToken, config)
                if (userRes.isSuccess) {
                    val (userId, email, displayName) = userRes.getOrThrow()
                    val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
                    val session = AuthSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken.orEmpty(),
                        userId = userId,
                        email = email,
                        displayName = displayName,
                        expiresAt = expiresAt
                    )
                    return@withContext DeepLinkAuthResult.SessionAcquired(session)
                } else {
                    return@withContext DeepLinkAuthResult.EmailConfirmedNeedLogin(
                        "تم تأكيد بريدك الإلكتروني بنجاح! يمكنك الآن إدخال كلمة المرور لتسجيل الدخول."
                    )
                }
            }

            // 3. التحقق من وجود code (Authorization Code Flow / PKCE)
            val code = params["code"]
            if (!code.isNullOrBlank()) {
                val codeSession = exchangeAuthCode(code, config)
                if (codeSession.isSuccess) {
                    return@withContext DeepLinkAuthResult.SessionAcquired(codeSession.getOrThrow())
                } else {
                    return@withContext DeepLinkAuthResult.EmailConfirmedNeedLogin(
                        "تم تأكيد بريدك الإلكتروني بنجاح! يرجى إدخال كلمة المرور لتسجيل الدخول."
                    )
                }
            }

            // 4. إذا وصل الرابط دون جلسة مباشرة
            DeepLinkAuthResult.EmailConfirmedNeedLogin(
                "تم تأكيد بريدك الإلكتروني بنجاح! يمكنك الآن تسجيل الدخول إلى المنصة."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error handling auth deep link: ${e.message}")
            DeepLinkAuthResult.Error("حدث خطأ أثناء معالجة رابط التأكيد: ${e.localizedMessage ?: "خطأ غير معروف"}")
        }
    }

    /**
     * تفكيك معلمات الرابط من الـ Query والـ Fragment
     */
    fun parseUriParams(uri: android.net.Uri): Map<String, String> {
        val map = mutableMapOf<String, String>()

        // 1. Query parameters (?key=value)
        if (uri.isHierarchical) {
            try {
                for (key in uri.queryParameterNames) {
                    val v = uri.getQueryParameter(key)
                    if (v != null) map[key] = v
                }
            } catch (_: Exception) {}
        }

        // 2. Hash Fragment parameters (#key=value&...)
        val fragment = uri.fragment
        if (!fragment.isNullOrBlank()) {
            val pairs = fragment.split('&')
            for (pair in pairs) {
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) {
                    try {
                        val k = java.net.URLDecoder.decode(parts[0], "UTF-8")
                        val v = java.net.URLDecoder.decode(parts[1], "UTF-8")
                        map[k] = v
                    } catch (_: Exception) {
                        map[parts[0]] = parts[1]
                    }
                }
            }
        }

        return map
    }

    private fun fetchUserFromToken(
        accessToken: String,
        config: SupabaseConfig
    ): Result<Triple<String, String, String>> {
        return try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/auth/v1/user"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val id = json.optString("id")
                val email = json.optString("email")
                val meta = json.optJSONObject("user_metadata")
                val displayName = meta?.optString("display_name")
                    ?: meta?.optString("username")
                    ?: email.substringBefore("@")
                Result.success(Triple(id, email, displayName))
            } else {
                Result.failure(Exception("Failed to fetch user (status: ${response.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun exchangeAuthCode(
        code: String,
        config: SupabaseConfig
    ): Result<AuthSession> {
        return try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/auth/v1/token?grant_type=pkce"

            val jsonBody = JSONObject().apply {
                put("auth_code", code)
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val accessToken = json.optString("access_token")
                val refreshToken = json.optString("refresh_token")
                val expiresIn = json.optLong("expires_in", 3600L)
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id").orEmpty()
                val email = userObj?.optString("email").orEmpty()
                val meta = userObj?.optJSONObject("user_metadata")
                val displayName = meta?.optString("display_name") ?: email.substringBefore("@")

                val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
                Result.success(AuthSession(accessToken, refreshToken, userId, email, displayName, expiresAt))
            } else {
                Result.failure(Exception("Failed to exchange auth code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * ترجمة وتفسير أخطاء المصادقة إلى رسائل عربية واضحة
     */
    private fun parseAuthError(statusCode: Int, responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val rawMsg = json.optString("msg", json.optString("message", json.optString("error_description", "")))
            val errorCode = json.optString("error", json.optString("error_code", ""))

            when {
                rawMsg.contains("Email not confirmed", ignoreCase = true) ->
                    "لم يتم تأكيد البريد الإلكتروني بعد. يرجى مراجعة بريدك الوارد لتأكيد الحساب."
                rawMsg.contains("Invalid login credentials", ignoreCase = true) || rawMsg.contains("invalid_grant", ignoreCase = true) ->
                    "البريد الإلكتروني أو كلمة المرور غير صحيحة. يرجى التأكد والمحاولة مرة أخرى."
                rawMsg.contains("User already registered", ignoreCase = true) ->
                    "هذا البريد الإلكتروني مسجل بالفعل. يمكنك تسجيل الدخول مباشرة."
                rawMsg.contains("Password should be at least", ignoreCase = true) ->
                    "يجب أن تتكون كلمة المرور من 6 أحرف على الأقل."
                rawMsg.contains("rate limit", ignoreCase = true) || statusCode == 429 ->
                    "تم تجاوز الحد الأقصى للمحاولات. يرجى الانتظار قليلاً ثم المحاولة ثانية."
                rawMsg.isNotEmpty() ->
                    rawMsg
                statusCode == 400 ->
                    "البيانات المدخلة غير صحيحة. يرجى التأكد من البريد الإلكتروني وكلمة المرور."
                else ->
                    "حدث خطأ في المصادقة (رمز: $statusCode)."
            }
        } catch (_: Exception) {
            "حدث خطأ غير متوقع أثناء معالجة الطلب (رمز: $statusCode)."
        }
    }

    private fun handleException(e: Exception): AuthResult {
        Log.w(TAG, "Network or server exception during auth: ${e.javaClass.simpleName}")
        return when (e) {
            is UnknownHostException, is SocketTimeoutException, is IOException ->
                AuthResult.Error(
                    message = "تعذر الاتصال بالخادم. يرجى التحقق من اتصالك بالإنترنت والمحاولة مجددًا.",
                    isNetworkError = true
                )
            else ->
                AuthResult.Error("حدث خطأ أثناء الاتصال: ${e.localizedMessage ?: "خطأ غير معروف"}")
        }
    }
}
