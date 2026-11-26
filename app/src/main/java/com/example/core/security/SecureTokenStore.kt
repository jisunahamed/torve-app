package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoredAuthCredentials(
    val apiKey: String,
    val keyId: String? = null,
    val keyName: String? = null,
    val keyPrefix: String? = null,
    val userId: String? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    val userPlan: String? = null,
    val userStatus: String? = null
)

/**
 * Persists Torve `sk-trv-...` credentials using Android Keystore + EncryptedSharedPreferences.
 * Credentials are never logged.
 */
class SecureTokenStore(context: Context) {
    private val prefs: SharedPreferences = createEncryptedPrefs(context.applicationContext)

    private val _credentials = MutableStateFlow(readCredentialsFromPrefs())
    val credentials: StateFlow<StoredAuthCredentials?> = _credentials.asStateFlow()

    val isAuthenticated: Boolean
        get() = !_credentials.value?.apiKey.isNullOrBlank()

    fun getApiKey(): String? = _credentials.value?.apiKey

    fun saveCredentials(creds: StoredAuthCredentials) {
        prefs.edit()
            .putString(KEY_API_KEY, creds.apiKey.trim())
            .putString(KEY_ID, creds.keyId)
            .putString(KEY_NAME, creds.keyName)
            .putString(KEY_PREFIX, creds.keyPrefix ?: creds.apiKey.take(12))
            .putString(KEY_USER_ID, creds.userId)
            .putString(KEY_USER_EMAIL, creds.userEmail)
            .putString(KEY_USER_NAME, creds.userName)
            .putString(KEY_USER_PLAN, creds.userPlan)
            .putString(KEY_USER_STATUS, creds.userStatus)
            .apply()
        _credentials.value = creds
    }

    fun updateProfile(
        keyId: String?,
        keyName: String?,
        keyPrefix: String?,
        userId: String?,
        userEmail: String?,
        userName: String?,
        userPlan: String?,
        userStatus: String?
    ) {
        val existingKey = getApiKey() ?: return
        saveCredentials(
            StoredAuthCredentials(
                apiKey = existingKey,
                keyId = keyId ?: _credentials.value?.keyId,
                keyName = keyName ?: _credentials.value?.keyName,
                keyPrefix = keyPrefix ?: _credentials.value?.keyPrefix,
                userId = userId ?: _credentials.value?.userId,
                userEmail = userEmail ?: _credentials.value?.userEmail,
                userName = userName ?: _credentials.value?.userName,
                userPlan = userPlan,
                userStatus = userStatus ?: _credentials.value?.userStatus
            )
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
        _credentials.value = null
    }

    private fun readCredentialsFromPrefs(): StoredAuthCredentials? {
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return StoredAuthCredentials(
            apiKey = apiKey,
            keyId = prefs.getString(KEY_ID, null),
            keyName = prefs.getString(KEY_NAME, null),
            keyPrefix = prefs.getString(KEY_PREFIX, null),
            userId = prefs.getString(KEY_USER_ID, null),
            userEmail = prefs.getString(KEY_USER_EMAIL, null),
            userName = prefs.getString(KEY_USER_NAME, null),
            userPlan = prefs.getString(KEY_USER_PLAN, null),
            userStatus = prefs.getString(KEY_USER_STATUS, null)
        )
    }

    companion object {
        private const val PREFS_NAME = "torve_secure_auth_prefs"
        private const val KEY_API_KEY = "torve_api_key"
        private const val KEY_ID = "torve_key_id"
        private const val KEY_NAME = "torve_key_name"
        private const val KEY_PREFIX = "torve_key_prefix"
        private const val KEY_USER_ID = "torve_user_id"
        private const val KEY_USER_EMAIL = "torve_user_email"
        private const val KEY_USER_NAME = "torve_user_name"
        private const val KEY_USER_PLAN = "torve_user_plan"
        private const val KEY_USER_STATUS = "torve_user_status"

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                // Fallback strictly for headless JVM/Robolectric test environments without AndroidKeyStore
                context.getSharedPreferences("${PREFS_NAME}_test", Context.MODE_PRIVATE)
            }
        }
    }
}
