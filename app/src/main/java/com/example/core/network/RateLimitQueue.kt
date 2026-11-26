package com.example.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class RateLimitBannerState(
    val message: String,
    val retryInSeconds: Int? = null,
    val isServerLimited: Boolean = false
)

/**
 * Token-bucket request pacer + exponential backoff with jitter for HTTP 429 & quota status.
 */
class RateLimitQueue(
    private val minIntervalMs: Long = 350L
) {
    private val mutex = Mutex()
    private var lastRequestAtMs: Long = 0L
    @Volatile
    private var rateLimitStatus: String = "ok"

    private val _bannerState = MutableStateFlow<RateLimitBannerState?>(null)
    val bannerState: StateFlow<RateLimitBannerState?> = _bannerState.asStateFlow()

    fun updateQuotaStatus(status: String?) {
        val normalized = status?.lowercase() ?: "ok"
        rateLimitStatus = normalized
        if (normalized == "limited") {
            _bannerState.value = RateLimitBannerState(
                message = "Account rate limit active — pacing requests automatically",
                isServerLimited = true
            )
        } else if (_bannerState.value?.isServerLimited == true) {
            _bannerState.value = null
        }
    }

    suspend fun acquireSlot() {
        mutex.withLock {
            val spacing = if (rateLimitStatus == "limited") 2_500L else minIntervalMs
            val now = System.currentTimeMillis()
            val waitMs = (lastRequestAtMs + spacing) - now
            if (waitMs > 0) {
                delay(waitMs)
            }
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    /**
     * Computes backoff delay in ms, honoring Retry-After header if present, otherwise exponential
     * backoff with jitter (2s, 4s, 8s, 16s capped at 30s).
     */
    fun computeBackoffMs(attempt: Int, retryAfterHeader: String?): Long {
        val headerSec = retryAfterHeader?.trim()?.toLongOrNull()
        if (headerSec != null && headerSec > 0) {
            return min(headerSec, 60L) * 1000L
        }
        val base = (2_000.0 * 2.0.pow(attempt.coerceAtMost(4))).toLong()
        val jitter = Random.nextLong(250L, 950L)
        return min(base + jitter, 30_000L)
    }

    suspend fun countdownBackoff(waitMs: Long, onTick: ((Int) -> Unit)? = null) {
        var remainingSec = ((waitMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        while (remainingSec > 0) {
            _bannerState.value = RateLimitBannerState(
                message = "Rate limit reached, retrying in ${remainingSec}s...",
                retryInSeconds = remainingSec,
                isServerLimited = false
            )
            onTick?.invoke(remainingSec)
            delay(1_000L)
            remainingSec--
        }
        clearTransientBanner()
    }

    fun clearTransientBanner() {
        if (_bannerState.value?.isServerLimited == false) {
            _bannerState.value = null
        }
    }
}
