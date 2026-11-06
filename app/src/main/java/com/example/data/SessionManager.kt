package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val expiresAt: Long // Epoch timestamp in milliseconds
)

object SessionManager {
    private const val TAG = "SessionManager"
    private const val PREFS_NAME = "midad_auth_session_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_EXPIRES_AT = "expires_at"

    // ثوابت تشفير كاش الاشتراكات عبر Android Keystore (المرحلة 2-C-1-R)
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ENROLLMENTS_KEY_ALIAS = "midad_enrollments_cache_key_v1"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val MIN_PAYLOAD_SIZE_BYTES = GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8) // 28 بايت

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * خطاف اختباري داخلي حصراً لبيئات Robolectric / JVM Unit Tests لتزويد مفتاح اختباري
     */
    @Volatile
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var testKeyProvider: (() -> SecretKey)? = null

    @Synchronized
    private fun getOrCreateEnrollmentsSecretKey(): SecretKey {
        testKeyProvider?.invoke()?.let { return it }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ENROLLMENTS_KEY_ALIAS)) {
            val entry = keyStore.getEntry(ENROLLMENTS_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ENROLLMENTS_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun init(context: Context) {
        synchronized(this) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun saveSession(session: AuthSession) {
        prefs?.edit()?.apply {
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            putString(KEY_REFRESH_TOKEN, session.refreshToken)
            putString(KEY_USER_ID, session.userId)
            putString(KEY_EMAIL, session.email)
            putString(KEY_DISPLAY_NAME, session.displayName)
            putLong(KEY_EXPIRES_AT, session.expiresAt)
            commit()
        }
    }

    fun getSession(): AuthSession? {
        val p = prefs ?: return null
        val token = p.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refreshToken = p.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val userId = p.getString(KEY_USER_ID, null) ?: return null
        val email = p.getString(KEY_EMAIL, "").orEmpty()
        val displayName = p.getString(KEY_DISPLAY_NAME, "").orEmpty()
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)

        return AuthSession(
            accessToken = token,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            displayName = displayName,
            expiresAt = expiresAt
        )
    }

    fun updateTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        prefs?.edit()?.apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
            apply()
        }
    }

    fun clearSession() {
        prefs?.edit()?.clear()?.commit()
    }

    /**
     * حفظ قائمة المواد المشترك بها الطالب مشفرة محلياً بواسطة AES-256-GCM عبر Android Keystore
     * مع مصادقة AAD مرتبطة بمعرف المستخدم لضمان استمرارية الوصول أوفلاين دون إنترنت ومنع انتحال الهوية (المرحلة 2-C-1-R)
     */
    fun saveCachedEnrolledSubjectIds(userId: String, subjectIds: Set<String>) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) return
        val key = "cached_enrolled_subjects_${cleanUserId}"

        try {
            val secretKey = getOrCreateEnrollmentsSecretKey()

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)   // النظام يولّد IV تلقائيًا
            val iv = cipher.iv
            check(iv.size == GCM_IV_LENGTH_BYTES) { "طول IV غير متوقع: ${iv.size}" }
            // ربط مشفر البيانات بهوية المستخدم الحالي عبر AAD لمنع نقل التشفير لمستخدم آخر
            cipher.updateAAD(cleanUserId.toByteArray(Charsets.UTF_8))

            val jsonArr = JSONArray()
            subjectIds.forEach { jsonArr.put(it) }
            val plainBytes = jsonArr.toString().toByteArray(Charsets.UTF_8)

            val ciphertextWithTag = cipher.doFinal(plainBytes)

            // تركيب البايتات: [IV: 12 بايت] + [Ciphertext + Auth Tag]
            val payload = ByteArray(iv.size + ciphertextWithTag.size)
            System.arraycopy(iv, 0, payload, 0, iv.size)
            System.arraycopy(ciphertextWithTag, 0, payload, iv.size, ciphertextWithTag.size)

            val base64Str = Base64.encodeToString(payload, Base64.NO_WRAP)
            prefs?.edit()?.putString(key, base64Str)?.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt and save cached enrolled subjects for user $cleanUserId", e)
        }
    }

    /**
     * استرجاع وفك تشفير قائمة المواد المشترك بها الطالب والتحقق الصارم من مصادقتها وسلامتها (المرحلة 2-C-1-R)
     * في حال التلاعب أو التلف أو محاولة استخدام كاش مستخدم آخر، ترفض الدالة البيانات وتفرغها لمنع منح أي صلاحيات غير موثوقة.
     */
    fun getCachedEnrolledSubjectIds(userId: String): Set<String> {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) return emptySet()
        val key = "cached_enrolled_subjects_${cleanUserId}"

        val rawValue = prefs?.getString(key, null) ?: return emptySet()
        if (rawValue.isBlank()) return emptySet()

        return try {
            val payload = Base64.decode(rawValue, Base64.NO_WRAP)
            if (payload.size < MIN_PAYLOAD_SIZE_BYTES) {
                Log.w(TAG, "Cached enrollment payload for user $cleanUserId is corrupted or too short")
                prefs?.edit()?.remove(key)?.commit()
                return emptySet()
            }

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(payload, 0, iv, 0, GCM_IV_LENGTH_BYTES)

            val cipherLen = payload.size - GCM_IV_LENGTH_BYTES
            val ciphertextWithTag = ByteArray(cipherLen)
            System.arraycopy(payload, GCM_IV_LENGTH_BYTES, ciphertextWithTag, 0, cipherLen)

            val secretKey = getOrCreateEnrollmentsSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            // التحقق الصارم من أن معرّف المستخدم يطابق الـ AAD الذي شُفّرت به البيانات
            cipher.updateAAD(cleanUserId.toByteArray(Charsets.UTF_8))

            val decryptedBytes = cipher.doFinal(ciphertextWithTag)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val jsonArr = JSONArray(jsonStr)
            val resultSet = mutableSetOf<String>()
            for (i in 0 until jsonArr.length()) {
                resultSet.add(jsonArr.getString(i))
            }
            resultSet
        } catch (e: Exception) {
            Log.w(TAG, "Integrity check or decryption failed for cached enrollments of user $cleanUserId: ${e.message}")
            try {
                // مسح الكاش التالف فوراً لعدم تكرار محاولة قراءته
                prefs?.edit()?.remove(key)?.commit()
            } catch (_: Exception) {}
            emptySet()
        }
    }

    fun isTokenExpired(bufferMillis: Long = 60_000L): Boolean {
        val session = getSession() ?: return true
        if (session.expiresAt <= 0L) return false
        return System.currentTimeMillis() >= (session.expiresAt - bufferMillis)
    }
}
