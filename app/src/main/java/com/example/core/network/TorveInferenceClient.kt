package com.example.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed class InferenceStreamEvent {
    data class ReasoningDelta(val text: String) : InferenceStreamEvent()
    data class ContentDelta(val text: String) : InferenceStreamEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsChunk: String?
    ) : InferenceStreamEvent()
    data class UsageUpdate(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    ) : InferenceStreamEvent()
    data class RateLimitRetry(val attempt: Int, val waitSeconds: Int) : InferenceStreamEvent()
    data class Done(val finishReason: String?) : InferenceStreamEvent()
    data class Error(val message: String, val httpCode: Int? = null) : InferenceStreamEvent()
}

/**
 * Streaming SSE inference client for POST https://api.torveai.com/v1/chat/completions.
 * Uses callTimeout = 0 (no short wall-clock cutoff) + 90s per-chunk idle readTimeout,
 * incremental SSE line parsing, immediate cancel support, and HTTP 429 exponential backoff.
 */
class TorveInferenceClient(
    private val rateLimitQueue: RateLimitQueue,
    private val json: Json = TorveApiClient.defaultTorveJson(),
    private val streamingClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.SECONDS) // Per contract: NO short wall-clock timeout
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // Per-chunk idle timeout
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val activeCall = AtomicReference<Call?>(null)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun cancelActiveStream() {
        activeCall.getAndSet(null)?.cancel()
    }

    fun streamChatCompletion(
        apiKey: String,
        model: TorveModel?,
        modelId: String,
        messages: List<ChatMessageDto>,
        tools: List<ToolDefinitionDto>? = null
    ): Flow<InferenceStreamEvent> = flow {
        val resolvedMaxTokens = computeSafeMaxTokens(model)
        val allowTools = !tools.isNullOrEmpty() && (model?.supportsTools ?: true)
        val requestDto = ChatCompletionRequest(
            model = modelId,
            messages = messages,
            stream = true,
            maxTokens = resolvedMaxTokens,
            streamOptions = StreamOptionsDto(includeUsage = true),
            tools = if (allowTools) tools else null,
            toolChoice = if (allowTools) "auto" else null
        )
        val bodyJson = json.encodeToString(ChatCompletionRequest.serializer(), requestDto)

        var attempt = 0
        val maxRetries = 4

        while (currentCoroutineContext().isActive) {
            rateLimitQueue.acquireSlot()

            val httpRequest = Request.Builder()
                .url("${TorveApiConfig.INFERENCE_API_BASE}/chat/completions")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .header("Authorization", "Bearer $apiKey")
                .header(TorveApiConfig.HEADER_CLIENT_APP, TorveApiConfig.CLIENT_APP_ID)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .build()

            val call = streamingClient.newCall(httpRequest)
            activeCall.set(call)

            try {
                val response = call.execute()
                response.use { res ->
                    if (res.code == 429 && attempt < maxRetries) {
                        val waitMs = rateLimitQueue.computeBackoffMs(attempt, res.header("Retry-After"))
                        attempt++
                        val waitSec = ((waitMs + 999L) / 1000L).toInt().coerceAtLeast(1)
                        emit(InferenceStreamEvent.RateLimitRetry(attempt, waitSec))
                        rateLimitQueue.countdownBackoff(waitMs) { remaining ->
                            // Banner state Flow updates UI every second
                        }
                        return@use
                    }

                    if (!res.isSuccessful) {
                        val errBody = res.body?.string().orEmpty()
                        emit(
                            InferenceStreamEvent.Error(
                                message = parseErrorBody(res.code, errBody),
                                httpCode = res.code
                            )
                        )
                        return@flow
                    }

                    val source = res.body?.source()
                    if (source == null) {
                        emit(InferenceStreamEvent.Error("Empty response stream from Torve AI", res.code))
                        return@flow
                    }

                    var lastFinishReason: String? = null
                    while (currentCoroutineContext().isActive && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                        if (!trimmed.startsWith("data:")) continue

                        val dataPayload = trimmed.removePrefix("data:").trim()
                        if (dataPayload == "[DONE]") {
                            emit(InferenceStreamEvent.Done(lastFinishReason))
                            return@flow
                        }

                        val chunk = parseSseChunk(dataPayload) ?: continue
                        chunk.usage?.let { usage ->
                            emit(
                                InferenceStreamEvent.UsageUpdate(
                                    promptTokens = usage.promptTokens,
                                    completionTokens = usage.completionTokens,
                                    totalTokens = usage.totalTokens
                                )
                            )
                        }

                        for (choice in chunk.choices) {
                            if (choice.finishReason != null) {
                                lastFinishReason = choice.finishReason
                            }
                            val delta = choice.delta ?: continue
                            delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                                emit(InferenceStreamEvent.ReasoningDelta(it))
                            }
                            delta.content?.takeIf { it.isNotEmpty() }?.let {
                                emit(InferenceStreamEvent.ContentDelta(it))
                            }
                            delta.toolCalls?.forEach { tc ->
                                emit(
                                    InferenceStreamEvent.ToolCallDelta(
                                        index = tc.index,
                                        id = tc.id,
                                        name = tc.function?.name,
                                        argumentsChunk = tc.function?.arguments
                                    )
                                )
                            }
                        }
                    }

                    emit(InferenceStreamEvent.Done(lastFinishReason))
                    return@flow
                }
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive || call.isCanceled()) {
                    emit(InferenceStreamEvent.Done("cancelled"))
                    return@flow
                }
                emit(InferenceStreamEvent.Error("Stream interrupted: ${e.localizedMessage ?: "connection lost"}"))
                return@flow
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }
    }.flowOn(Dispatchers.IO)

    internal fun parseSseChunk(dataPayload: String): ChatCompletionChunk? {
        return runCatching {
            json.decodeFromString(ChatCompletionChunk.serializer(), dataPayload)
        }.getOrNull()
    }

    private fun computeSafeMaxTokens(model: TorveModel?): Int {
        val defaultMax = 16384
        val ceiling = 32768
        val modelCap = model?.maxOutputTokens?.takeIf { it > 0 } ?: return defaultMax
        return modelCap.coerceIn(1024, ceiling)
    }

    private fun parseErrorBody(code: Int, body: String): String {
        val parsed = runCatching {
            val env = json.decodeFromString(ApiErrorEnvelope.serializer(), body)
            env.message ?: env.error?.toString()
        }.getOrNull()?.trim('"')
        return parsed?.takeIf { it.isNotBlank() } ?: when (code) {
            401 -> "Unauthorized (401). Please verify your Torve API key."
            403 -> "Forbidden (403). Your plan may not include this model."
            429 -> "Rate limit exceeded (429). Please wait a moment before retrying."
            else -> "Inference request failed with HTTP $code."
        }
    }
}
