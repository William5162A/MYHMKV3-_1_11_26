package com.example.data

import android.content.Context
import android.content.SharedPreferences

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val expiresAt: Long // Epoch timestamp in milliseconds
)

object SessionManager {
    private const val PREFS_NAME = "midad_auth_session_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_EXPIRES_AT = "expires_at"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
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
            apply()
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
        prefs?.edit()?.clear()?.apply()
    }

    fun isTokenExpired(bufferMillis: Long = 60_000L): Boolean {
        val session = getSession() ?: return true
        if (session.expiresAt <= 0L) return false
        return System.currentTimeMillis() >= (session.expiresAt - bufferMillis)
    }
}
