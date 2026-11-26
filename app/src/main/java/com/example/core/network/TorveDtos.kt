package com.example.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object TorveApiConfig {
    const val BROWSER_AUTH_URL = "https://torveai.com/auth/desktop"
    const val DASHBOARD_KEYS_URL = "https://torveai.com/dashboard"
    const val DESKTOP_API_BASE = "https://api.torveai.com/api/desktop"
    const val INFERENCE_API_BASE = "https://api.torveai.com/v1"
    const val CALLBACK_URI = "torve://auth/callback"
    const val CLIENT_APP_ID = "torve-android"
    const val HEADER_CLIENT_APP = "X-Torve-Client-App"
}

// Endpoint 1: POST /api/desktop/auth/exchange
@Serializable
data class AuthExchangeRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String = TorveApiConfig.CALLBACK_URI,
    @SerialName("device_name") val deviceName: String
)

@Serializable
data class AuthExchangeResponse(
    val apiKey: String,
    val key: TorveKeyInfo? = null,
    val user: TorveUserInfo? = null
)

@Serializable
data class TorveKeyInfo(
    val id: String = "",
    val name: String = "",
    val prefix: String = "",
    val createdAt: String = ""
)

@Serializable
data class TorveUserInfo(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val plan: String? = null,
    val status: String = "active"
)

// Endpoint 2: POST /api/desktop/auth/revoke
@Serializable
data class AuthRevokeResponse(
    val revoked: Boolean = false
)

// Endpoint 3: GET /api/desktop/me
@Serializable
data class MeResponse(
    val user: TorveUserInfo? = null,
    val key: TorveKeyInfo? = null,
    val quota: QuotaInfo? = null,
    val allowance: JsonObject? = null
)

@Serializable
data class QuotaInfo(
    val usedTokensToday: Long = 0L,
    val usedRequestsToday: Int = 0,
    val rateLimitStatus: String = "ok",
    val resetsAt: String = ""
)

// Endpoint 4: GET /api/desktop/usage
@Serializable
data class UsageResponse(
    val range: String = "7d",
    val totals: UsageTotals = UsageTotals(),
    val byModel: List<ModelUsage> = emptyList(),
    val byApp: List<AppUsage> = emptyList()
)

@Serializable
data class UsageTotals(
    val requests: Int = 0,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val totalTokens: Long = 0L
)

@Serializable
data class ModelUsage(
    val modelId: String = "",
    val requests: Int = 0,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val avgLatencyMs: Long = 0L,
    val lastUsedAt: String = ""
)

@Serializable
data class AppUsage(
    val appId: String = "",
    val requests: Int = 0,
    val totalTokens: Long = 0L
)

// Endpoint 5: GET /v1/models
@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<TorveModel> = emptyList()
)

@Serializable
data class TorveModel(
    val id: String,
    val name: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val tier: String? = null,
    @SerialName("supports_tools") val supportsTools: Boolean = true,
    @SerialName("supports_reasoning") val supportsReasoning: Boolean = false
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: id
}

// Endpoint 6: POST /v1/chat/completions
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto = StreamOptionsDto(includeUsage = true),
    val tools: List<ToolDefinitionDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class StreamOptionsDto(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null
)

@Serializable
data class ToolDefinitionDto(
    val type: String = "function",
    val function: ToolFunctionSchemaDto
)

@Serializable
data class ToolFunctionSchemaDto(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunctionDto
)

@Serializable
data class ToolCallFunctionDto(
    val name: String,
    val arguments: String
)

// SSE Streaming Chunk DTOs
@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: TokenUsageDto? = null
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCallDelta>? = null
)

@Serializable
data class ChunkToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkFunctionDelta? = null
)

@Serializable
data class ChunkFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class TokenUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

@Serializable
data class ApiErrorEnvelope(
    val error: JsonElement? = null,
    val message: String? = null
)
