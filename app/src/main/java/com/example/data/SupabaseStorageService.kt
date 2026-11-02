package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.model.SupabaseConfig
import com.example.model.TopicAttachment
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * نتيجة رفع ملف إلى مساحة التخزين الخاصة في Supabase
 */
data class StorageUploadResult(
    val filePath: String,
    val fileSizeBytes: Long,
    val mimeType: String = "application/pdf"
)

object SupabaseStorageService {
    private const val TAG = "SupabaseStorage"
    private const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L // 50MB

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var testClient: OkHttpClient? = null

    private fun getClient(): OkHttpClient = testClient ?: defaultClient

    private fun isConfigured(config: SupabaseConfig): Boolean {
        val cleanUrl = config.projectUrl.trim().trimEnd('/')
        return cleanUrl.startsWith("http") && config.anonKey.isNotBlank()
    }

    // =========================================================================
    // 1. Private Storage: Streaming PDF Upload (رفع موثق بانسيابية)
    // =========================================================================

    /**
     * رفع ملف PDF مباشرة إلى الـ Private Bucket دون تحميله بالكامل إلى الذاكرة
     * باستخدام JWT الخاص بالمستخدم ومسار منظم وآمن
     */
    suspend fun uploadPdf(
        context: Context,
        fileUri: Uri,
        subjectId: String,
        lessonId: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<StorageUploadResult> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول (accessToken) مطلوب لرفع الملفات"))
        }

        try {
            // 1. التحقق الصارم من نوع الملف وتوقيع PDF الثنائي (%PDF-)
            val detectedMime = context.contentResolver.getType(fileUri)
            val rawFileName = getFileName(context, fileUri)

            // رفض الملف فورًا إذا كان موفر الملفات يصرّح بـ MIME مخالف لـ PDF
            if (detectedMime != null && !detectedMime.equals("application/pdf", ignoreCase = true)) {
                return@withContext Result.failure(
                    Exception("نوع الملف غير مسموح به ($detectedMime)، يجب اختيار ملف بصيغة PDF")
                )
            }

            // فحص توقيع الملف الفعلي (%PDF-) في أول 1024 بايت دون تحميله كاملاً للذاكرة
            if (!isValidPdfSignature(context, fileUri)) {
                return@withContext Result.failure(
                    Exception("الملف المحدد تالف أو ليس ملف PDF صالحاً (توقيع الملف غير مطابق لـ %PDF-)")
                )
            }

            // 2. التحقق من حجم الملف عبر OpenableColumns.SIZE إن توفر
            val reportedSize = getFileSize(context, fileUri)
            if (reportedSize > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(
                    Exception("حجم الملف (${formatFileSize(reportedSize)}) يتجاوز الحد الأقصى المسموح به (50 ميغابايت)")
                )
            }
            if (reportedSize == 0L) {
                return@withContext Result.failure(Exception("ملف الـ PDF فارغ (0 بايت) ولا يمكن رفعه"))
            }

            // 3. بناء المسار الآمن مع منع ثغرات Path Traversal
            val safeSubject = sanitizePathSegment(subjectId)
            val safeLesson = sanitizePathSegment(lessonId)
            val safeFileName = sanitizeFileName(rawFileName)
            val relativePath = "subjects/$safeSubject/lessons/$safeLesson/${System.currentTimeMillis()}_$safeFileName"
            val encodedPath = encodeStoragePath(relativePath)

            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/storage/v1/object/${config.bucketName}/$encodedPath"

            // 4. إنشاء RequestBody انسيابي يقرأ تدريجياً عبر BufferedSink
            val mediaType = "application/pdf".toMediaTypeOrNull()
            val requestBody = createStreamingRequestBody(context, fileUri, mediaType, reportedSize)

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/pdf")
                .post(requestBody)
                .build()

            val response = getClient().newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                // إذا لم يكن الحجم معلوماً مسبقاً، نحاول استخراجه من الجهاز أو افتراض حجم تقريبي
                val finalSize = if (reportedSize > 0) reportedSize else getFileSize(context, fileUri).coerceAtLeast(1L)
                Result.success(
                    StorageUploadResult(
                        filePath = relativePath,
                        fileSizeBytes = finalSize,
                        mimeType = "application/pdf"
                    )
                )
            } else {
                val errorMsg = mapStorageHttpError(response.code, "رفع الملف", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during uploadPdf", e)
            val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                e.message ?: "تعذر الاتصال بالخادم أثناء رفع الملف"
            } else {
                e.message ?: "حدث خطأ غير متوقع أثناء الرفع"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    // =========================================================================
    // 2. Private Storage: Authenticated Direct Download & Direct Encryption
    // =========================================================================

    /**
     * تنزيل موثق لملف PDF من الـ Private Bucket وتشفيره تدفقياً ومباشرة عبر AES-256-GCM
     * دون كتابة أي بايت PDF مكشوف على قرص التخزين نهائياً.
     *
     * @param filePath مسار الملف النسبي في الـ Private Bucket
     * @param destinationEncryptedFile الملف المشفر النهائي المستهدف (.enc)
     * @param accessToken رمز الوصول الموثق (JWT)
     * @param config إعدادات Supabase (الرابط، مفتاح anon، اسم الـ Bucket)
     * @param onProgress دالة إشعار تقدم القراءة والتشفير (bytesRead, totalBytes)
     */
    suspend fun downloadAndEncryptAuthenticatedPdf(
        filePath: String,
        accessToken: String,
        destinationEncryptedFile: File,
        config: SupabaseConfig = SupabaseConfig(),
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(IllegalArgumentException("إعدادات Supabase غير مهيأة بعد"))
        }
        val cleanToken = accessToken.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("رمز الوصول (accessToken) مطلوب لتنزيل وتشفير الملفات"))
        }
        val cleanPath = filePath.trim()
        if (cleanPath.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("مسار الملف غير محدد"))
        }

        try {
            val encodedPath = encodeStoragePath(cleanPath)
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/storage/v1/object/authenticated/${config.bucketName}/$encodedPath"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer $cleanToken")
                .addHeader("Accept", "application/pdf, application/octet-stream")
                .get()
                .build()

            val call = getClient().newCall(request)

            // ربط إلغاء الكوروتين بإلغاء اتصال OkHttp Call فعلياً وبشكل لحظي
            @OptIn(InternalCoroutinesApi::class)
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = false
            ) { cause ->
                if (cause != null) {
                    try {
                        call.cancel()
                    } catch (_: Throwable) {}
                }
            }

            try {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    if (call.isCanceled() || !coroutineContext.isActive) {
                        throw CancellationException("تم إلغاء اتصال الشبكة لتنزيل الملف", e)
                    }
                    throw e
                }

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        val errorMsg = mapStorageHttpError(resp.code, "تنزيل وتشفير الملف", errBody)
                        Log.w(TAG, errorMsg)
                        return@withContext Result.failure(IOException(errorMsg))
                    }

                    val body = resp.body
                        ?: return@withContext Result.failure(IOException("استجابة الخادم فارغة"))

                    // التحقق من ترويسة Content-Type دون الاعتماد عليها وحدها
                    val contentType = resp.header("Content-Type").orEmpty()
                    if (contentType.contains("json", ignoreCase = true)) {
                        val jsonBody = body.string()
                        return@withContext Result.failure(IOException("استجاب الخادم برسالة خطأ بدلاً من الملف: $jsonBody"))
                    }

                    val totalBytes = body.contentLength()
                    if (totalBytes > MAX_FILE_SIZE_BYTES) {
                        return@withContext Result.failure(
                            IllegalArgumentException("حجم الملف في ترويسة الخادم ($totalBytes بايت) يتجاوز الحد الأقصى المسموح به (50 ميغابايت)")
                        )
                    }

                    // تمرير دفق InputStream مباشرة إلى خدمة التشفير دون كتابة نسخة مكشوفة على القرص
                    val encryptResult = body.byteStream().use { stream ->
                        PdfSecurityStorageService.encryptStream(
                            inputStream = stream,
                            destinationEncryptedFile = destinationEncryptedFile,
                            maxAllowedSizeBytes = MAX_FILE_SIZE_BYTES,
                            totalBytesExpected = totalBytes,
                            onProgress = onProgress
                        )
                    }

                    // التحقق من حالة الإلغاء فور عودة نتيجة التشفير للتمييز بين الإلغاء والفشل العادي
                    if (call.isCanceled() || !coroutineContext.isActive) {
                        if (destinationEncryptedFile.exists()) {
                            destinationEncryptedFile.delete()
                        }
                        throw CancellationException("تم إلغاء تنزيل وتشفير الملف")
                    }

                    if (encryptResult.isFailure) {
                        val cause = encryptResult.exceptionOrNull()
                            ?: IOException("فشل تشفير الملف أثناء التنزيل التدفقي")
                        return@withContext Result.failure(cause)
                    }

                    Result.success(destinationEncryptedFile)
                }
            } finally {
                cancellationHandle?.dispose()
            }
        } catch (e: CancellationException) {
            if (destinationEncryptedFile.exists()) {
                destinationEncryptedFile.delete()
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception during downloadAndEncryptAuthenticatedPdf", e)
            val friendlyMsg = when (e) {
                is UnknownHostException -> "تعذر الاتصال بالخادم، يرجى التحقق من اتصال الإنترنت"
                is SocketTimeoutException -> "انتهت مهلة الاتصال بالخادم أثناء تنزيل الملف"
                is IllegalArgumentException -> e.message ?: "معاملات تنزيل وتشفير الملف غير صالحة"
                is IOException -> e.message ?: "حدث خطأ أثناء نقل وتشفير الملف من الخادم"
                else -> e.message ?: "فشل تنزيل وتشفير الملف"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    /**
     * تنزيل ملف PDF مباشرة من الـ Private Bucket عبر مسار التوثيق المباشر
     * وكتابته تدريجياً في destinationFile دون استهلاك الذاكرة (للتوافق مع الاستدعاءات القائمة)
     */
    suspend fun downloadAuthenticatedPdf(
        filePath: String,
        destinationFile: File,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig(),
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول (accessToken) مطلوب لتنزيل الملفات"))
        }
        if (filePath.isBlank()) {
            return@withContext Result.failure(Exception("مسار الملف غير محدد"))
        }

        try {
            val encodedPath = encodeStoragePath(filePath)
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val endpoint = "$cleanUrl/storage/v1/object/authenticated/${config.bucketName}/$encodedPath"

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Accept", "application/pdf, application/octet-stream")
                .get()
                .build()

            val response = getClient().newCall(request).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                val errorMsg = mapStorageHttpError(response.code, "تنزيل الملف", errBody)
                Log.w(TAG, errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = response.body
            if (body == null) {
                return@withContext Result.failure(Exception("استجابة الخادم فارغة"))
            }

            // التحقق من أن الاستجابة ليست JSON خطأ
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("json", ignoreCase = true)) {
                val jsonBody = body.string()
                return@withContext Result.failure(Exception("استجاب الخادم برسالة خطأ بدلاً من الملف: $jsonBody"))
            }

            val totalBytes = body.contentLength()
            destinationFile.parentFile?.mkdirs()

            try {
                destinationFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            onProgress?.invoke(totalRead, totalBytes)
                        }
                        output.flush()
                    }
                }
                Result.success(destinationFile)
            } catch (e: Exception) {
                // في حال انقطاع التنزيل، نحذف الملف الجزئي غير المكتمل
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during downloadAuthenticatedPdf", e)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                e.message ?: "تعذر الاتصال بالخادم أثناء تنزيل الملف"
            } else {
                e.message ?: "فشل تنزيل الملف"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    // =========================================================================
    // 3. Private Storage: Delete PDF (حذف موثق للمشرف)
    // =========================================================================

    /**
     * حذف ملف PDF من الـ Private Bucket باستخدام مساره النسبي
     */
    suspend fun deletePdf(
        filePath: String,
        accessToken: String,
        config: SupabaseConfig = SupabaseConfig()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured(config)) {
            return@withContext Result.failure(Exception("إعدادات Supabase غير مهيأة بعد"))
        }
        if (accessToken.isBlank()) {
            return@withContext Result.failure(Exception("رمز الوصول مطلوب لحذف الملف"))
        }
        val rawTrimmed = filePath.trim()
        if (rawTrimmed.isBlank()) {
            return@withContext Result.failure(Exception("مسار الملف غير محدد"))
        }
        if (rawTrimmed.endsWith("/") || rawTrimmed.endsWith("\\")) {
            return@withContext Result.failure(Exception("لا يمكن حذف مسار مجلد أو بادئة عامة، يجب تحديد ملف PDF"))
        }
        if (!rawTrimmed.endsWith(".pdf", ignoreCase = true)) {
            return@withContext Result.failure(Exception("مسار الملف يجب أن ينتهي بصيغة .pdf"))
        }

        val cleanPath = rawTrimmed.trimStart('/', '\\')
        val segments = cleanPath.split('/', '\\')
        for (seg in segments) {
            if (seg == "." || seg == ".." || seg.isBlank()) {
                return@withContext Result.failure(Exception("مسار الملف يحتوي على مقاطع غير صالحة أو غير آمنة ($seg)"))
            }
            if (!seg.matches("[a-zA-Z0-9._-]+".toRegex())) {
                return@withContext Result.failure(Exception("مسار الملف يحتوي على محارف غير آمنة في المقطع ($seg)"))
            }
        }

        try {
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            // نقطة النهاية الرسمية في Supabase Storage API للحذف الجماعي
            val endpoint = "$cleanUrl/storage/v1/object/${config.bucketName}"

            // إرسال المسار النسبي داخل مصفوفة prefixes فقط بدون وضعه في عنوان URL
            val jsonBody = JSONObject().apply {
                val prefixesArray = JSONArray().apply { put(cleanPath) }
                put("prefixes", prefixesArray)
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", config.anonKey.trim())
                .addHeader("Authorization", "Bearer ${accessToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .delete(requestBody)
                .build()

            val response = getClient().newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful || response.code in 200..299) {
                Result.success(Unit)
            } else {
                val errorMsg = mapStorageHttpError(response.code, "حذف الملف", responseBody)
                Log.w(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during deletePdf", e)
            val friendlyMsg = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                "تعذر الاتصال بالخادم أثناء حذف الملف"
            } else {
                e.message ?: "فشل حذف الملف"
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    // =========================================================================
    // 4. Legacy Compatibility Bridge (جسر التوافق القديم دون نجاح وهمي أو تسرب anon)
    // =========================================================================

    /**
     * جسر مؤقت لاستدعاءات الواجهة القديمة (MainSections.kt) يربطها بالرفع الموثق
     * بدلاً من استخدام anonKey أو إرجاع نجاح محلي وهمي
     */
    suspend fun uploadFile(
        context: Context,
        fileUri: Uri,
        fileName: String,
        subjectId: String,
        config: SupabaseConfig
    ): Result<TopicAttachment> = withContext(Dispatchers.IO) {
        val session = SessionManager.getSession()
        val token = session?.accessToken.orEmpty()
        if (token.isBlank()) {
            return@withContext Result.failure(Exception("يجب تسجيل الدخول برمز وصول صالح لرفع الملفات"))
        }

        val uploadRes = uploadPdf(
            context = context,
            fileUri = fileUri,
            subjectId = subjectId,
            lessonId = "legacy",
            accessToken = token,
            config = config
        )

        uploadRes.map { uploaded ->
            val cleanUrl = config.projectUrl.trim().trimEnd('/')
            val authenticatedPathUrl = "$cleanUrl/storage/v1/object/authenticated/${config.bucketName}/${uploaded.filePath}"
            val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())

            TopicAttachment(
                id = UUID.randomUUID().toString(),
                name = fileName,
                sizeFormatted = formatFileSize(uploaded.fileSizeBytes),
                supabaseUrl = authenticatedPathUrl,
                localUri = null,
                mimeType = uploaded.mimeType,
                uploadDate = dateStr
            )
        }
    }

    // =========================================================================
    // 5. Helpers: Streaming RequestBody, File Info & Sanitization
    // =========================================================================

    fun getFileName(context: Context, uri: Uri): String {
        var name = "document.pdf"
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !it.isNull(nameIndex)) {
                        name = it.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) { }
        return name
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = -1L
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                        size = it.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) { }
        return size
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            bytes > 0 -> "$bytes B"
            else -> "غير محدد"
        }
    }

    private fun createStreamingRequestBody(
        context: Context,
        uri: Uri,
        mediaType: okhttp3.MediaType?,
        knownLength: Long
    ): RequestBody {
        return object : RequestBody() {
            override fun contentType(): okhttp3.MediaType? = mediaType

            override fun contentLength(): Long = if (knownLength > 0) knownLength else -1L

            override fun writeTo(sink: BufferedSink) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("تعذر فتح ملف الـ PDF من الهاتف للقراءة")
                inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalWritten = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalWritten += bytesRead
                        if (totalWritten > MAX_FILE_SIZE_BYTES) {
                            throw IOException("حجم الملف يتجاوز الحد الأقصى المسموح به (50 ميغابايت)")
                        }
                        sink.write(buffer, 0, bytesRead)
                    }
                    if (totalWritten <= 0) {
                        throw IOException("ملف الـ PDF فارغ (0 بايت)")
                    }
                }
            }
        }
    }

    /**
     * التحقق الصارم من التوقيع الثنائي الفعلي لملف PDF (%PDF- / 25 50 44 46 2D) في أول 5 بايتات
     * مع استمرار القراءة حتى تجميع 5 بايتات أو بلوغ نهاية الملف دون تحميل الملف كاملاً إلى الذاكرة
     */
    private fun isValidPdfSignature(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val expectedSignature = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
                val buffer = ByteArray(5)
                var totalRead = 0
                while (totalRead < 5) {
                    val count = stream.read(buffer, totalRead, 5 - totalRead)
                    if (count == -1) break
                    totalRead += count
                }
                if (totalRead != 5) return false
                buffer.contentEquals(expectedSignature)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizePathSegment(segment: String): String {
        val clean = segment.replace("\\", "/")
            .replace("../", "")
            .replace("..", "")
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .trim('_')
        return if (clean.isBlank()) "item" else clean
    }

    private fun sanitizeFileName(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .trim('_')
        val ext = fileName.substringAfterLast('.', "pdf")
            .replace("[^a-zA-Z0-9]".toRegex(), "")
            .lowercase()
        val safeBase = if (base.isBlank()) "document" else base
        return if (ext == "pdf") "$safeBase.pdf" else "${safeBase}_$ext.pdf"
    }

    private fun encodeStoragePath(path: String): String {
        return path.split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                try {
                    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                } catch (_: Exception) {
                    segment
                }
            }
    }

    private fun mapStorageHttpError(code: Int, operation: String, responseBody: String): String {
        return when (code) {
            400 -> "طلب غير صالح أثناء $operation (يرجى التأكد من نوع الملف وحجمه)"
            401 -> "غير مصرح: انتهت صلاحية الجلسة أو مفتاح الوصول غير صالح"
            403 -> "تم رفض الوصول: لا تملك الصلاحية الكافية لـ $operation أو لم يتم نشر المادة/الملف"
            404 -> "الملف أو المجلد المطلوب غير موجود في التخزين السحابي"
            409 -> "يوجد ملف بنفس الاسم والمسار بالفعل"
            413 -> "حجم الملف يتجاوز الحد الأقصى المسموح به على الخادم (50 ميغابايت)"
            else -> "فشل $operation في التخزين السحابي (رمز HTTP: $code)"
        }
    }
}
