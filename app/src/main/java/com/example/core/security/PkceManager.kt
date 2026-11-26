package com.example.core.security

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

data class PkceSession(
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val deviceName: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * RFC 7636 / RFC 8252 PKCE Session Manager.
 * Keeps `code_verifier` strictly in memory only and verifies `state` in constant time.
 */
class PkceManager(
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val activeSession = AtomicReference<PkceSession?>(null)

    fun startSession(deviceName: String): PkceSession {
        val stateBytes = ByteArray(32)
        secureRandom.nextBytes(stateBytes)
        val state = base64UrlNoPad(stateBytes) // 43 URL-safe chars

        val verifierBytes = ByteArray(48)
        secureRandom.nextBytes(verifierBytes)
        val codeVerifier = base64UrlNoPad(verifierBytes) // 64 chars in [A-Za-z0-9-_]

        val challenge = computeS256Challenge(codeVerifier)
        val sanitizedDevice = deviceName.trim().ifBlank { "Android" }.take(64)

        val session = PkceSession(
            state = state,
            codeVerifier = codeVerifier,
            codeChallenge = challenge,
            deviceName = sanitizedDevice
        )
        activeSession.set(session)
        return session
    }

    fun buildAuthorizationUrl(session: PkceSession): String {
        val redirectEncoded = urlEncode(CALLBACK_URI)
        val stateEncoded = urlEncode(session.state)
        val challengeEncoded = urlEncode(session.codeChallenge)
        val deviceEncoded = urlEncode(session.deviceName)
        return "$BROWSER_AUTH_URL?redirect_uri=$redirectEncoded&state=$stateEncoded&code_challenge=$challengeEncoded&code_challenge_method=S256&device_name=$deviceEncoded"
    }

    /**
     * Strictly verifies state against the in-memory session and atomically consumes it.
     * Returns null if there is no active session, if state mismatches, or if expired (> 5 min).
     */
    fun consumeIfStateMatches(returnedState: String?): PkceSession? {
        if (returnedState.isNullOrBlank()) return null
        val current = activeSession.get() ?: return null
        val expectedBytes = current.state.toByteArray(StandardCharsets.US_ASCII)
        val actualBytes = returnedState.toByteArray(StandardCharsets.US_ASCII)
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            return null
        }
        // Expire after 5 minutes per Torve contract
        val elapsedMs = System.currentTimeMillis() - current.createdAtMs
        if (elapsedMs > SESSION_TTL_MS) {
            activeSession.compareAndSet(current, null)
            return null
        }
        return if (activeSession.compareAndSet(current, null)) current else null
    }

    fun clearSession() {
        activeSession.set(null)
    }

    fun hasActiveSession(): Boolean = activeSession.get() != null

    companion object {
        const val BROWSER_AUTH_URL = "https://torveai.com/auth/desktop"
        const val CALLBACK_URI = "torve://auth/callback"
        private const val SESSION_TTL_MS = 5 * 60 * 1000L

        fun computeS256Challenge(codeVerifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
            return base64UrlNoPad(digest)
        }

        fun base64UrlNoPad(bytes: ByteArray): String {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun urlEncode(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
        }
    }
}
