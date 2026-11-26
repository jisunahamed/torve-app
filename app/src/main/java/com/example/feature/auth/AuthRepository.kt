package com.example.feature.auth

import android.net.Uri
import android.os.Build
import com.example.core.database.AppPreferencesStore
import com.example.core.network.MeResponse
import com.example.core.network.RateLimitQueue
import com.example.core.network.TorveApiClient
import com.example.core.network.TorveApiError
import com.example.core.network.TorveApiResult
import com.example.core.network.TorveModel
import com.example.core.network.UsageResponse
import com.example.core.security.PkceManager
import com.example.core.security.SecureTokenStore
import com.example.core.security.StoredAuthCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

sealed class AuthUiEvent {
    data class OpenCustomTab(val url: String) : AuthUiEvent()
}

data class AccountSessionState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val credentials: StoredAuthCredentials? = null,
    val me: MeResponse? = null,
    val usage: UsageResponse? = null,
    val usageRange: String = "7d",
    val usageScope: String = "account",
    val models: List<TorveModel> = emptyList(),
    val error: String? = null,
    val isKeyLimitError: Boolean = false
)

/**
 * Dual-mode Auth & Account coordinator:
 * - Mode A: RFC 7636 PKCE Custom Tab OAuth (torve://auth/callback -> POST /api/desktop/auth/exchange)
 * - Mode B: Direct `sk-trv-...` API Key login (validated against GET /v1/models + optional GET /api/desktop/me)
 * - Sign-out: POST /api/desktop/auth/revoke (always clears local token even on 403 manual keys)
 */
class AuthRepository(
    private val pkceManager: PkceManager,
    private val tokenStore: SecureTokenStore,
    private val apiClient: TorveApiClient,
    private val preferencesStore: AppPreferencesStore,
    private val rateLimitQueue: RateLimitQueue
) {
    private val _sessionState = MutableStateFlow(
        AccountSessionState(
            isAuthenticated = tokenStore.isAuthenticated,
            credentials = tokenStore.credentials.value
        )
    )
    val sessionState: StateFlow<AccountSessionState> = _sessionState.asStateFlow()

    fun beginBrowserPkceAuth(): String {
        _sessionState.value = _sessionState.value.copy(error = null, isKeyLimitError = false)
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        val session = pkceManager.startSession(deviceName)
        return pkceManager.buildAuthorizationUrl(session)
    }

    /**
     * Handles `torve://auth/callback?code=...&state=...` strictly verifying in-memory PKCE state.
     */
    suspend fun handleAuthCallbackUri(uri: Uri): Boolean {
        if (uri.scheme != "torve" || uri.host != "auth" || uri.path?.startsWith("/callback") != true) {
            return false
        }

        val errorParam = uri.getQueryParameter("error")
        if (!errorParam.isNullOrBlank()) {
            pkceManager.clearSession()
            val desc = uri.getQueryParameter("error_description") ?: errorParam
            _sessionState.value = _sessionState.value.copy(
                isLoading = false,
                error = "Authorization declined: $desc",
                isKeyLimitError = false
            )
            return true
        }

        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        if (code.isNullOrBlank() || returnedState.isNullOrBlank()) {
            pkceManager.clearSession()
            _sessionState.value = _sessionState.value.copy(
                isLoading = false,
                error = "Invalid callback parameters from Torve AI.",
                isKeyLimitError = false
            )
            return true
        }

        val pkceSession = pkceManager.consumeIfStateMatches(returnedState)
        if (pkceSession == null) {
            _sessionState.value = _sessionState.value.copy(
                isLoading = false,
                error = "Security check failed: OAuth state mismatch or expired login session. Please try again.",
                isKeyLimitError = false
            )
            return true
        }

        _sessionState.value = _sessionState.value.copy(isLoading = true, error = null, isKeyLimitError = false)

        when (
            val exchangeResult = apiClient.exchangeAuthCode(
                code = code,
                codeVerifier = pkceSession.codeVerifier,
                deviceName = pkceSession.deviceName
            )
        ) {
            is TorveApiResult.Success -> {
                val res = exchangeResult.data
                val creds = StoredAuthCredentials(
                    apiKey = res.apiKey,
                    keyId = res.key?.id,
                    keyName = res.key?.name,
                    keyPrefix = res.key?.prefix,
                    userId = res.user?.id,
                    userEmail = res.user?.email,
                    userName = res.user?.name,
                    userPlan = res.user?.plan,
                    userStatus = res.user?.status
                )
                tokenStore.saveCredentials(creds)
                _sessionState.value = _sessionState.value.copy(
                    isAuthenticated = true,
                    isLoading = false,
                    credentials = creds,
                    error = null,
                    isKeyLimitError = false
                )
                hydrateSession()
            }

            is TorveApiResult.Failure -> {
                val err = exchangeResult.error
                _sessionState.value = _sessionState.value.copy(
                    isLoading = false,
                    error = err.message,
                    isKeyLimitError = err is TorveApiError.KeyLimitReached
                )
            }
        }
        return true
    }

    /**
     * Mode B: Direct `sk-trv-...` API Key sign-in. Validates immediately against GET /v1/models
     * and optionally hydrates GET /api/desktop/me.
     */
    suspend fun signInWithApiKey(rawKey: String): Boolean {
        val trimmedKey = rawKey.trim()
        if (!trimmedKey.startsWith("sk-trv-") || trimmedKey.length < 12) {
            _sessionState.value = _sessionState.value.copy(
                error = "API key must begin with 'sk-trv-'. Copy a valid key from torveai.com.",
                isKeyLimitError = false
            )
            return false
        }

        _sessionState.value = _sessionState.value.copy(isLoading = true, error = null, isKeyLimitError = false)

        return when (val modelsResult = apiClient.getModels(trimmedKey)) {
            is TorveApiResult.Success -> {
                val models = modelsResult.data
                val creds = StoredAuthCredentials(
                    apiKey = trimmedKey,
                    keyName = "Direct API Key",
                    keyPrefix = trimmedKey.take(12)
                )
                tokenStore.saveCredentials(creds)
                preferencesStore.saveCachedModels(models)
                ensureDefaultModelSelected(models)
                _sessionState.value = _sessionState.value.copy(
                    isAuthenticated = true,
                    isLoading = false,
                    credentials = creds,
                    models = models,
                    error = null,
                    isKeyLimitError = false
                )
                hydrateSession()
                true
            }

            is TorveApiResult.Failure -> {
                _sessionState.value = _sessionState.value.copy(
                    isLoading = false,
                    error = modelsResult.error.message,
                    isKeyLimitError = false
                )
                false
            }
        }
    }

    /**
     * Hydrates /api/desktop/me, /api/desktop/usage, and /v1/models in parallel.
     */
    suspend fun hydrateSession(
        usageRange: String = _sessionState.value.usageRange,
        usageScope: String = _sessionState.value.usageScope
    ) = coroutineScope {
        val apiKey = tokenStore.getApiKey() ?: return@coroutineScope

        // Load cached models immediately for snappy UI
        val cached = preferencesStore.cachedModelsFlow.first()
        if (cached.isNotEmpty() && _sessionState.value.models.isEmpty()) {
            _sessionState.value = _sessionState.value.copy(models = cached)
        }

        val meDeferred = async { apiClient.getMe(apiKey) }
        val usageDeferred = async { apiClient.getUsage(apiKey, usageRange, usageScope) }
        val modelsDeferred = async { apiClient.getModels(apiKey) }

        val meResult = meDeferred.await()
        val usageResult = usageDeferred.await()
        val modelsResult = modelsDeferred.await()

        var updatedMe = _sessionState.value.me
        var updatedUsage = _sessionState.value.usage
        var updatedModels = _sessionState.value.models

        if (meResult is TorveApiResult.Success) {
            updatedMe = meResult.data
            rateLimitQueue.updateQuotaStatus(updatedMe.quota?.rateLimitStatus)
            tokenStore.updateProfile(
                keyId = updatedMe.key?.id,
                keyName = updatedMe.key?.name,
                keyPrefix = updatedMe.key?.prefix,
                userId = updatedMe.user?.id,
                userEmail = updatedMe.user?.email,
                userName = updatedMe.user?.name,
                userPlan = updatedMe.user?.plan,
                userStatus = updatedMe.user?.status
            )
        }

        if (usageResult is TorveApiResult.Success) {
            updatedUsage = usageResult.data
        }

        if (modelsResult is TorveApiResult.Success && modelsResult.data.isNotEmpty()) {
            updatedModels = modelsResult.data
            preferencesStore.saveCachedModels(updatedModels)
            ensureDefaultModelSelected(updatedModels)
        }

        _sessionState.value = _sessionState.value.copy(
            isAuthenticated = tokenStore.isAuthenticated,
            credentials = tokenStore.credentials.value,
            me = updatedMe,
            usage = updatedUsage,
            usageRange = usageRange,
            usageScope = usageScope,
            models = updatedModels
        )
    }

    suspend fun refreshUsage(range: String, scope: String) {
        val apiKey = tokenStore.getApiKey() ?: return
        _sessionState.value = _sessionState.value.copy(usageRange = range, usageScope = scope)
        val result = apiClient.getUsage(apiKey, range, scope)
        if (result is TorveApiResult.Success) {
            _sessionState.value = _sessionState.value.copy(usage = result.data)
        }
    }

    /**
     * Sign-out: calls POST /api/desktop/auth/revoke and ALWAYS clears local encrypted token
     * even if the server returns 403 (manual dashboard key) or is offline.
     */
    suspend fun signOut() {
        val apiKey = tokenStore.getApiKey()
        if (!apiKey.isNullOrBlank()) {
            runCatching { apiClient.revokeKey(apiKey) }
        }
        pkceManager.clearSession()
        tokenStore.clear()
        preferencesStore.clearSessionCache()
        rateLimitQueue.updateQuotaStatus("ok")
        _sessionState.value = AccountSessionState(isAuthenticated = false)
    }

    fun clearError() {
        _sessionState.value = _sessionState.value.copy(error = null, isKeyLimitError = false)
    }

    private suspend fun ensureDefaultModelSelected(models: List<TorveModel>) {
        val settings = preferencesStore.settingsFlow.first()
        if (settings.defaultModelId.isBlank() && models.isNotEmpty()) {
            val preferred = models.firstOrNull { it.supportsTools } ?: models.first()
            preferencesStore.setDefaultModelId(preferred.id)
        }
    }
}
