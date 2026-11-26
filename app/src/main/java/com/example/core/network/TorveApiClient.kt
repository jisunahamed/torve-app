package com.example.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class TorveApiResult<out T> {
    data class Success<T>(val data: T) : TorveApiResult<T>()
    data class Failure(val error: TorveApiError) : TorveApiResult<Nothing>()
}

sealed class TorveApiError(val message: String, val httpCode: Int? = null) {
    class KeyLimitReached(
        message: String = "API key limit reached on your Torve AI account. Please revoke an unused key on the torveai.com dashboard and try again."
    ) : TorveApiError(message, 409)

    class AuthRejected(code: Int, message: String) : TorveApiError(message, code)
    class NetworkError(message: String) : TorveApiError(message, null)
    class ServerError(code: Int, message: String) : TorveApiError(message, code)
}

/**
 * REST client for Torve AI's live production auth, account, usage, and model discovery endpoints.
 */
class TorveApiClient(
    private val json: Json = defaultTorveJson(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Endpoint 1: POST https://api.torveai.com/api/desktop/auth/exchange
     * Strictly never retries 409 in a loop.
     */
    suspend fun exchangeAuthCode(
        code: String,
        codeVerifier: String,
        deviceName: String
    ): TorveApiResult<AuthExchangeResponse> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            AuthExchangeRequest.serializer(),
            AuthExchangeRequest(
                code = code,
                codeVerifier = codeVerifier,
                redirectUri = TorveApiConfig.CALLBACK_URI,
                deviceName = deviceName
            )
        )
        val request = Request.Builder()
            .url("${TorveApiConfig.DESKTOP_API_BASE}/auth/exchange")
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .build()

        executeJsonRequest(request) { body ->
            json.decodeFromString(AuthExchangeResponse.serializer(), body)
        }
    }

    /**
     * Endpoint 2: POST https://api.torveai.com/api/desktop/auth/revoke
     * Returns Success(true) on 200 and Success(false) on 403 (manual dashboard key) so local token is still cleared.
     */
    suspend fun revokeKey(apiKey: String): TorveApiResult<Boolean> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${TorveApiConfig.DESKTOP_API_BASE}/auth/revoke")
            .post("{}".toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $apiKey")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when (response.code) {
                    in 200..299 -> {
                        val parsed = runCatching {
                            json.decodeFromString(AuthRevokeResponse.serializer(), raw)
                        }.getOrNull()
                        TorveApiResult.Success(parsed?.revoked ?: true)
                    }
                    403 -> TorveApiResult.Success(false) // Manually created dashboard key; caller clears local token
                    else -> TorveApiResult.Failure(mapHttpError(response.code, raw))
                }
            }
        } catch (e: IOException) {
            TorveApiResult.Failure(TorveApiError.NetworkError(e.message ?: "Network unavailable"))
        }
    }

    /**
     * Endpoint 3: GET https://api.torveai.com/api/desktop/me
     */
    suspend fun getMe(apiKey: String): TorveApiResult<MeResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${TorveApiConfig.DESKTOP_API_BASE}/me")
            .get()
            .header("Authorization", "Bearer $apiKey")
            .build()

        executeJsonRequest(request) { body ->
            json.decodeFromString(MeResponse.serializer(), body)
        }
    }

    /**
     * Endpoint 4: GET https://api.torveai.com/api/desktop/usage?range=7d&scope=account
     */
    suspend fun getUsage(
        apiKey: String,
        range: String = "7d",
        scope: String = "account"
    ): TorveApiResult<UsageResponse> = withContext(Dispatchers.IO) {
        val safeRange = if (range in setOf("24h", "7d", "30d")) range else "7d"
        val safeScope = if (scope in setOf("account", "key")) scope else "account"
        val request = Request.Builder()
            .url("${TorveApiConfig.DESKTOP_API_BASE}/usage?range=$safeRange&scope=$safeScope")
            .get()
            .header("Authorization", "Bearer $apiKey")
            .build()

        executeJsonRequest(request) { body ->
            json.decodeFromString(UsageResponse.serializer(), body)
        }
    }

    /**
     * Endpoint 5: GET https://api.torveai.com/v1/models
     */
    suspend fun getModels(apiKey: String): TorveApiResult<List<TorveModel>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${TorveApiConfig.INFERENCE_API_BASE}/models")
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header(TorveApiConfig.HEADER_CLIENT_APP, TorveApiConfig.CLIENT_APP_ID)
            .build()

        executeJsonRequest(request) { body ->
            json.decodeFromString(ModelsListResponse.serializer(), body).data
        }
    }

    private fun <T> executeJsonRequest(
        request: Request,
        parser: (String) -> T
    ): TorveApiResult<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    try {
                        TorveApiResult.Success(parser(rawBody))
                    } catch (e: Exception) {
                        TorveApiResult.Failure(
                            TorveApiError.ServerError(response.code, "Invalid server response: ${e.message}")
                        )
                    }
                } else {
                    TorveApiResult.Failure(mapHttpError(response.code, rawBody))
                }
            }
        } catch (e: IOException) {
            TorveApiResult.Failure(
                TorveApiError.NetworkError("Network error connecting to Torve AI: ${e.localizedMessage ?: "offline"}")
            )
        }
    }

    private fun mapHttpError(code: Int, rawBody: String): TorveApiError {
        val parsedMsg = extractErrorMessage(rawBody)
        return when (code) {
            409 -> TorveApiError.KeyLimitReached(
                parsedMsg?.takeIf { it.isNotBlank() }
                    ?: "API key limit reached. Revoke an unused desktop/mobile key on torveai.com/dashboard and try again."
            )
            400 -> TorveApiError.AuthRejected(
                400,
                parsedMsg ?: "Invalid or expired authorization code. Please try signing in again."
            )
            401 -> TorveApiError.AuthRejected(
                401,
                parsedMsg ?: "Authentication failed. Your API key or code is invalid or expired."
            )
            403 -> TorveApiError.AuthRejected(
                403,
                parsedMsg ?: "Access denied. Check your Torve AI plan status or complete Telegram verification on torveai.com."
            )
            else -> TorveApiError.ServerError(
                code,
                parsedMsg ?: "Torve AI returned HTTP $code."
            )
        }
    }

    private fun extractErrorMessage(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        return runCatching {
            val envelope = json.decodeFromString(ApiErrorEnvelope.serializer(), rawBody)
            envelope.message?.takeIf { it.isNotBlank() } ?: when (val err = envelope.error) {
                is JsonPrimitive -> err.contentOrNull
                is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }.getOrNull()
    }

    companion object {
        fun defaultTorveJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            encodeDefaults = true
        }
    }
}
