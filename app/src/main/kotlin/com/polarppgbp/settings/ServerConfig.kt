/*
 * Server configuration (#15): validation and normalisation for the sync target.
 *
 * Kept free of Android dependencies so it can be unit-tested. The point of
 * validating at entry time is that the alternative is discovering the mistake in a
 * WorkManager job hours later, where the only symptom is that sync silently never
 * happened and the phone quietly became a single point of data loss.
 */

package com.polarppgbp.settings

/** Outcome of validating user-entered server details. */
sealed interface ServerConfigResult {
    data class Valid(val url: String, val token: String) : ServerConfigResult

    /** [urlError] / [tokenError] are user-facing; null means that field is fine. */
    data class Invalid(val urlError: String?, val tokenError: String?) : ServerConfigResult
}

object ServerConfig {

    /** Server tokens are 64 hex characters today. Used only to warn, never to reject. */
    private val LOOKS_LIKE_TOKEN = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Normalise a user-entered base URL, or return null if it cannot be salvaged.
     *
     * Deliberately forgiving about a missing scheme, because typing `192.168.1.5:8000`
     * is the natural thing to do and rejecting it teaches nothing. Trailing slashes are
     * stripped here; the sync workers also `trimEnd('/')`, so this only has to agree
     * with them rather than be the sole defence.
     */
    fun normaliseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // Any other scheme is a mistake rather than something to guess at.
            trimmed.contains("://") -> return null
            else -> "http://$trimmed"
        }

        val afterScheme = withScheme.substringAfter("://")
        val hostPort = afterScheme.substringBefore('/')
        if (hostPort.isEmpty()) return null

        // Reject a bare port or an empty host ("http://:8000").
        val host = hostPort.substringBefore(':')
        if (host.isEmpty()) return null

        if (hostPort.contains(':')) {
            val port = hostPort.substringAfter(':')
            val portNum = port.toIntOrNull() ?: return null
            if (portNum !in 1..65535) return null
        }

        return withScheme.trimEnd('/')
    }

    /**
     * True when the token looks like one the server issued. Only drives a hint: the
     * token format is the server's business and hard-rejecting it here would break the
     * app the day the server changes it.
     */
    fun looksLikeServerToken(token: String): Boolean = LOOKS_LIKE_TOKEN.matches(token.trim())

    fun validate(rawUrl: String, rawToken: String): ServerConfigResult {
        val url = normaliseUrl(rawUrl)
        val token = rawToken.trim()

        val urlError = when {
            rawUrl.isBlank() -> "Enter the server address, e.g. http://192.168.1.5:8000"
            url == null -> "Not a valid address. Expected host:port or http://host:port"
            else -> null
        }
        val tokenError = when {
            rawToken.isBlank() -> "Enter the access token issued by the server"
            token.any { it.isWhitespace() } -> "The token must not contain spaces"
            else -> null
        }

        return if (urlError == null && tokenError == null) {
            ServerConfigResult.Valid(url!!, token)
        } else {
            ServerConfigResult.Invalid(urlError, tokenError)
        }
    }

    /**
     * Render a token for display without revealing it. Shows only the length and the
     * last four characters, enough to tell two tokens apart when checking what is
     * stored, and never enough to reproduce one from a screenshot.
     */
    fun maskToken(token: String?): String = when {
        token.isNullOrBlank() -> "not set"
        token.length <= 4 -> "•".repeat(token.length)
        else -> "•".repeat(token.length - 4) + token.takeLast(4)
    }
}
