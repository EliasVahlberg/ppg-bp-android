/*
 * Debug-only command interface (#22).
 *
 * Lives in the `debug` source set, so it is compiled into debug builds ONLY
 * and never ships in release. Registered (exported) via
 * app/src/debug/AndroidManifest.xml. Lets a developer drive the recorder
 * deterministically over adb instead of tapping the UI:
 *
 *   adb shell am broadcast -a com.polarppgbp.debug.START_RECORDING \
 *       --es profile calibration --es device_id AABBCCDD
 *   adb shell am broadcast -a com.polarppgbp.debug.STOP_RECORDING
 *   adb shell am broadcast -a com.polarppgbp.debug.STATUS
 *
 * Results are logged under the "DebugCmd" tag:
 *   adb logcat -d -s DebugCmd
 */

package com.polarppgbp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.polarppgbp.KEY_DEVICE_ID
import com.polarppgbp.KEY_SERVER_TOKEN
import com.polarppgbp.KEY_SERVER_URL
import com.polarppgbp.PREFS_NAME
import com.polarppgbp.RecordingService
import com.polarppgbp.SharedRepo
import com.polarppgbp.omron.OmronCuffClient
import com.polarppgbp.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "DebugCmd"

class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                val profile = intent.getStringExtra("profile") ?: "calibration"
                val deviceId = intent.getStringExtra("device_id")
                if (deviceId != null) {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_DEVICE_ID, deviceId).apply()
                }
                val svc = Intent(context, RecordingService::class.java).apply {
                    action = "START"
                    putExtra("PROFILE", profile)
                }
                context.startForegroundService(svc)
                Log.i(TAG, "START_RECORDING profile=$profile device_id=${deviceId ?: "(pref)"}")
            }

            ACTION_STOP -> {
                val svc = Intent(context, RecordingService::class.java).apply { action = "STOP" }
                context.startService(svc)
                Log.i(TAG, "STOP_RECORDING")
            }

            ACTION_STATUS -> {
                val repo = SharedRepo.repo
                Log.i(
                    TAG,
                    "STATUS state=${repo?.connectionState?.value} " +
                        "metrics=${repo?.metrics?.value} " +
                        "session=${repo?.currentSessionDir() ?: "(none)"}",
                )
            }

            ACTION_SET_SERVER -> {
                val url = intent.getStringExtra("url")
                val token = intent.getStringExtra("token")
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                if (url != null) prefs.putString(KEY_SERVER_URL, url)
                if (token != null) prefs.putString(KEY_SERVER_TOKEN, token)
                prefs.apply()
                Log.i(TAG, "SET_SERVER url=$url token=${token?.take(8)?.plus("…")}")
            }

            ACTION_SYNC_NOW -> {
                val root = SharedRepo.repo?.bundlesRoot()
                    ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
                val n = SyncScheduler.enqueueAllUnsynced(context, root)
                SyncScheduler.enqueueCuff(context)
                Log.i(TAG, "SYNC_NOW enqueued=$n sessions + cuff from ${root.absolutePath}")
            }

            ACTION_READ_CUFF, ACTION_PAIR_CUFF -> {
                val address = intent.getStringExtra("address")
                val pair = intent.action == ACTION_PAIR_CUFF
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val client = OmronCuffClient(
                            context.applicationContext,
                            onStatus = { Log.i(TAG, "cuff: $it") },
                        )
                        val readings = if (pair) client.pairAndRead(address) else client.readRecords(address)
                        val store = com.polarppgbp.omron.CuffStore(java.io.File(context.filesDir, "cuff"))
                        val res = store.ingest(readings, address)
                        Log.i(TAG, "${intent.action}: ${readings.size} on cuff, ${res.newCount} new, ${res.total} stored total")
                        Log.i(TAG, "  stored at ${res.path}")
                        SyncScheduler.enqueueCuff(context.applicationContext)
                        readings.takeLast(12).forEach { r ->
                            Log.i(
                                TAG,
                                "  ${r.takenAtIso()}  ${r.sysMmHg}/${r.diaMmHg} mmHg  " +
                                    "${r.pulseBpm} bpm" + (if (r.irregularHeartbeat) " [IHB]" else ""),
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "${intent.action} failed: ${e.message}", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    companion object {
        const val ACTION_START = "com.polarppgbp.debug.START_RECORDING"
        const val ACTION_STOP = "com.polarppgbp.debug.STOP_RECORDING"
        const val ACTION_STATUS = "com.polarppgbp.debug.STATUS"
        const val ACTION_SET_SERVER = "com.polarppgbp.debug.SET_SERVER"
        const val ACTION_SYNC_NOW = "com.polarppgbp.debug.SYNC_NOW"
        const val ACTION_READ_CUFF = "com.polarppgbp.debug.READ_CUFF"
        const val ACTION_PAIR_CUFF = "com.polarppgbp.debug.PAIR_CUFF"
    }
}
