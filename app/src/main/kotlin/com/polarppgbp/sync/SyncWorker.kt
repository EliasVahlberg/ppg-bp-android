/*
 * Uploads a finished session bundle to ppg-pi-server (Option A loop):
 *   POST /api/v1/sessions               (open audit row)
 *   PUT  /api/v1/upload/{uuid}/{file}   (each bundle file, gzip + X-SHA256)
 *   POST /api/v1/sessions/{uuid}/complete  (server runs convert_session)
 *
 * Runs under WorkManager with a network constraint + exponential backoff, so
 * a session recorded offline (the common case for an unattended user) uploads
 * automatically once connectivity returns. On success a `.synced` marker is
 * written into the bundle dir so it is never re-uploaded; raw files are kept
 * (the server keeps raw too) and can be purged later.
 *
 * Uses java.net.HttpURLConnection to avoid adding an HTTP dependency.
 */

package com.polarppgbp.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.polarppgbp.KEY_SERVER_TOKEN
import com.polarppgbp.KEY_SERVER_URL
import com.polarppgbp.PREFS_NAME
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dirPath = inputData.getString(KEY_BUNDLE_DIR) ?: return Result.failure()
        val dir = File(dirPath)
        val manifest = File(dir, "manifest.json")
        if (!manifest.isFile) {
            Log.w(TAG, "no manifest.json in $dirPath — nothing to sync")
            return Result.failure()
        }
        if (File(dir, MARKER).exists()) {
            Log.i(TAG, "already synced: $dirPath")
            return Result.success()
        }

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = prefs.getString(KEY_SERVER_URL, null)?.trimEnd('/')
        val token = prefs.getString(KEY_SERVER_TOKEN, null)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "server not configured (set $KEY_SERVER_URL/$KEY_SERVER_TOKEN)")
            return Result.failure()
        }

        return try {
            val mf = JSONObject(manifest.readText())
            val uuid = mf.getString("session_uuid")
            val deviceName = mf.optString("device_name").ifBlank { null }

            openSession(base, token, uuid, deviceName)
            val files = dir.listFiles { f -> f.isFile && f.name != MARKER }
                ?.sortedBy { it.name } ?: emptyList()
            for (f in files) putFile(base, token, uuid, f)
            val completeResp = complete(base, token, uuid)

            File(dir, MARKER).writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "synced $uuid (${files.size} files): $completeResp")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "sync failed for $dirPath: ${e.message}", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun openSession(base: String, token: String, uuid: String, device: String?) {
        val body = JSONObject().put("phone_session_uuid", uuid)
            .apply { if (device != null) put("device_name", device) }.toString()
        val conn = post("$base/api/v1/sessions", token, "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        readOrThrow(conn, "open session")
    }

    private fun putFile(base: String, token: String, uuid: String, f: File) {
        val raw = f.readBytes()
        val conn = (URL("$base/api/v1/upload/$uuid/${f.name}").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("X-SHA256", sha256(raw))
        }
        conn.outputStream.use { it.write(gzip(raw)) }
        readOrThrow(conn, "put ${f.name}")
    }

    private fun complete(base: String, token: String, uuid: String): String {
        val conn = post("$base/api/v1/sessions/$uuid/complete", token, null)
        conn.outputStream.use { /* empty body */ }
        return readOrThrow(conn, "complete")
    }

    private fun post(url: String, token: String, contentType: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
        }

    private fun readOrThrow(conn: HttpURLConnection, what: String): String {
        val code = conn.responseCode
        return if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            throw RuntimeException("$what -> HTTP $code: $err")
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    companion object {
        const val TAG = "SyncWorker"
        const val KEY_BUNDLE_DIR = "bundle_dir"
        const val MARKER = ".synced"
        const val MAX_ATTEMPTS = 5
    }
}
