/*
 * Server health check (#16): tell the user whether the configured server is
 * actually usable, before trusting it with data.
 *
 * The failure modes have different fixes, so a single red/green is not enough:
 * a wrong address and a wrong token look identical to a user otherwise. The
 * network probing and its interpretation are kept apart so the interpretation --
 * which is where the user-facing wording lives -- is unit-testable.
 */

package com.polarppgbp.sync

import android.content.Context
import android.util.Log
import com.polarppgbp.KEY_SERVER_TOKEN
import com.polarppgbp.KEY_SERVER_URL
import com.polarppgbp.PREFS_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ServerHealth"

/** How far the check got. Ordered: each stage implies the previous one passed. */
enum class HealthStage {
    NOT_CONFIGURED,
    UNREACHABLE,
    REACHABLE,
    AUTHENTICATED,
}

/**
 * Raw observations from probing, with no interpretation applied.
 *
 * [transportError] is the exception message from the unauthenticated probe, which is
 * how an unreachable host reports itself.
 */
data class HealthProbe(
    val url: String?,
    val tokenPresent: Boolean,
    val transportError: String? = null,
    val healthCode: Int? = null,
    val healthBody: String? = null,
    val authCode: Int? = null,
    val authError: String? = null,
)

data class HealthReport(
    val stage: HealthStage,
    val url: String?,
    val serverVersion: String? = null,
    /** null when the version could not be determined. */
    val versionCompatible: Boolean? = null,
    /** Plain language, names the failing stage, pasteable into a debug report (#13). */
    val detail: String,
) {
    val ok: Boolean get() = stage == HealthStage.AUTHENTICATED && versionCompatible != false
}

object ServerHealth {

    /**
     * Server version this app was built against. A mismatch is reported, never
     * enforced: refusing to sync against 0.3 would be worse than uploading to it,
     * and the user cannot fix a version skew from the phone anyway.
     */
    const val EXPECTED_SERVER_VERSION = "0.2.0"

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    /** Compare on major.minor; patch differences are not breaking. */
    internal fun versionsCompatible(server: String?, expected: String = EXPECTED_SERVER_VERSION): Boolean? {
        if (server.isNullOrBlank()) return null
        fun majorMinor(v: String) = v.trim().split('.').take(2).joinToString(".")
        return majorMinor(server) == majorMinor(expected)
    }

    /**
     * Turn raw observations into something worth showing a user.
     *
     * Pure, so every branch is unit-tested rather than reproduced by breaking a real
     * server.
     */
    internal fun interpret(probe: HealthProbe): HealthReport {
        val where = probe.url ?: "server"

        if (probe.url.isNullOrBlank() || !probe.tokenPresent) {
            return HealthReport(
                stage = HealthStage.NOT_CONFIGURED,
                url = probe.url,
                detail = "No server configured. Recordings stay on this phone until one is set.",
            )
        }

        if (probe.transportError != null || probe.healthCode == null) {
            return HealthReport(
                stage = HealthStage.UNREACHABLE,
                url = probe.url,
                detail = "Could not reach $where. ${probe.transportError ?: "No response."} " +
                    "Check the address, and that this phone is on the same network or Tailnet.",
            )
        }

        if (probe.healthCode !in 200..299) {
            return HealthReport(
                stage = HealthStage.UNREACHABLE,
                url = probe.url,
                detail = "Reached $where but /health returned HTTP ${probe.healthCode}. " +
                    "Something is answering on that address, but it does not look like this server.",
            )
        }

        val version = probe.healthBody?.let {
            runCatching { JSONObject(it).optString("version").ifBlank { null } }.getOrNull()
        }
        val compatible = versionsCompatible(version)

        // Reachable confirmed. Now the token.
        if (probe.authCode == null) {
            return HealthReport(
                stage = HealthStage.REACHABLE,
                url = probe.url,
                serverVersion = version,
                versionCompatible = compatible,
                detail = "Reached $where (version ${version ?: "unknown"}) but the token check " +
                    "did not complete. ${probe.authError ?: ""}".trim(),
            )
        }

        if (probe.authCode == 401 || probe.authCode == 403) {
            return HealthReport(
                stage = HealthStage.REACHABLE,
                url = probe.url,
                serverVersion = version,
                versionCompatible = compatible,
                detail = "Reached $where but the token was rejected (HTTP ${probe.authCode}). " +
                    "The address is right; re-enter the access token.",
            )
        }

        if (probe.authCode !in 200..299) {
            return HealthReport(
                stage = HealthStage.REACHABLE,
                url = probe.url,
                serverVersion = version,
                versionCompatible = compatible,
                detail = "Reached $where but an authenticated request failed with " +
                    "HTTP ${probe.authCode}. The server is up but not accepting requests.",
            )
        }

        val versionNote = when (compatible) {
            true -> "version $version"
            false -> "version $version, but this app expects $EXPECTED_SERVER_VERSION — " +
                "uploads may be rejected"
            null -> "version not reported"
        }
        return HealthReport(
            stage = HealthStage.AUTHENTICATED,
            url = probe.url,
            serverVersion = version,
            versionCompatible = compatible,
            detail = "Server reachable and token accepted ($versionNote).",
        )
    }

    /**
     * Probe the configured server. On-demand only: no periodic polling, since #6
     * already surfaces time since last successful sync, which is the metric that
     * actually matters.
     *
     * Uses `GET /api/v1/sessions` for the authenticated stage because it is read-only.
     * Proving ingest by uploading a synthetic session would write junk into the
     * canonical store, so that is deliberately not attempted here.
     */
    suspend fun check(context: Context): HealthReport = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = prefs.getString(KEY_SERVER_URL, null)?.trimEnd('/')
        val token = prefs.getString(KEY_SERVER_TOKEN, null)

        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            return@withContext interpret(HealthProbe(url = base, tokenPresent = !token.isNullOrBlank()))
        }

        var healthCode: Int? = null
        var healthBody: String? = null
        var transportError: String? = null
        try {
            val conn = get("$base/health", token = null)
            healthCode = conn.responseCode
            healthBody = runCatching {
                (if (healthCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            conn.disconnect()
        } catch (e: Exception) {
            transportError = e.message ?: e.javaClass.simpleName
        }

        var authCode: Int? = null
        var authError: String? = null
        if (transportError == null && healthCode in 200..299) {
            try {
                val conn = get("$base/api/v1/sessions?limit=1", token)
                authCode = conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                authError = e.message ?: e.javaClass.simpleName
            }
        }

        // Deliberately logs the URL and stage but never the token.
        interpret(
            HealthProbe(
                url = base,
                tokenPresent = true,
                transportError = transportError,
                healthCode = healthCode,
                healthBody = healthBody,
                authCode = authCode,
                authError = authError,
            ),
        ).also { Log.i(TAG, "health check: ${it.stage} — ${it.detail}") }
    }

    private fun get(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Short timeouts: an unreachable host on a home LAN can otherwise hang for
            // a long time behind a button the user just pressed.
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
}
