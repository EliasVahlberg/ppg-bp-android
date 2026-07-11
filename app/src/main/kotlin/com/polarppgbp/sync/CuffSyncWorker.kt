/*
 * Uploads the local cuff-readings store to ppg-pi-server:
 *   POST /api/v1/cuff   {"readings":[ {...}, ... ]}
 *
 * The cuff exposes a rolling buffer and the server dedups by reading id, so we
 * simply POST the phone's whole local store; only new rows are inserted. Small
 * payload (~100 readings), plain JSON (the endpoint parses the body via
 * pydantic and does not decompress gzip). Runs under WorkManager with a
 * network constraint + backoff so it uploads once connectivity returns.
 */

package com.polarppgbp.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.polarppgbp.KEY_SERVER_TOKEN
import com.polarppgbp.KEY_SERVER_URL
import com.polarppgbp.PREFS_NAME
import com.polarppgbp.omron.CuffStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class CuffSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = prefs.getString(KEY_SERVER_URL, null)?.trimEnd('/')
        val token = prefs.getString(KEY_SERVER_TOKEN, null)
        if (base.isNullOrBlank() || token.isNullOrBlank()) {
            Log.w(TAG, "server not configured — skipping cuff sync")
            return Result.failure()
        }

        val store = CuffStore(File(applicationContext.filesDir, "cuff"))
        val body = store.uploadBody() ?: run {
            Log.i(TAG, "no cuff readings to upload")
            return Result.success()
        }

        return try {
            val conn = (URL("$base/api/v1/cuff").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                Log.i(TAG, "cuff sync ok: $resp")
                Result.success()
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                throw RuntimeException("cuff upload -> HTTP $code: $err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "cuff sync failed: ${e.message}", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "CuffSyncWorker"
        const val MAX_ATTEMPTS = 5
    }
}
