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
 *   adb shell am broadcast -a com.polarppgbp.debug.GET_SETTINGS
 *   adb shell am broadcast -a com.polarppgbp.debug.SET_PROFILE --es profile custom
 *   adb shell am broadcast -a com.polarppgbp.debug.SET_RATE --es sensor PPG --es hz 135
 *   adb shell am broadcast -a com.polarppgbp.debug.RESET_SETTINGS
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
import com.polarppgbp.rop.SensorType
import com.polarppgbp.settings.ProfileChoice
import com.polarppgbp.settings.SettingsStore
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
                // Mirrors MainViewModel.startRecording(): if no explicit
                // `profile` extra is given, don't force one — let
                // RecordingService resolve from SettingsStore, exactly like
                // tapping the real Start button would. `profile` here is an
                // explicit *override*, for scripting/e2e convenience, not
                // the default path.
                val profileOverride = intent.getStringExtra("profile")
                val deviceId = intent.getStringExtra("device_id")
                if (deviceId != null) {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_DEVICE_ID, deviceId).apply()
                }
                val svc = Intent(context, RecordingService::class.java).apply {
                    action = "START"
                    if (profileOverride != null) putExtra("PROFILE", profileOverride)
                }
                context.startForegroundService(svc)
                Log.i(TAG, "START_RECORDING profile=${profileOverride ?: "(from settings)"} device_id=${deviceId ?: "(pref)"}")
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

            // ---- settings (#1) ----
            // These call the exact same SettingsStore methods the Settings
            // screen's onClick/onSelect handlers call (via MainViewModel) —
            // a debug command is a scripted equivalent of the user action,
            // not a separate code path, so this stays true if either side
            // changes later.

            ACTION_GET_SETTINGS -> {
                val s = SettingsStore(context).get()
                Log.i(
                    TAG,
                    "GET_SETTINGS profile=${s.profileChoice.storageName} " +
                        "ppg=${s.customPpgHz} acc=${s.customAccHz} gyro=${s.customGyroHz} " +
                        "resolved=${s.toProfile().rates}",
                )
            }

            ACTION_SET_PROFILE -> {
                val name = intent.getStringExtra("profile")
                val choice = ProfileChoice.entries.firstOrNull { it.storageName == name }
                if (choice == null) {
                    Log.w(TAG, "SET_PROFILE: unknown profile '$name', expected one of " +
                        ProfileChoice.entries.joinToString { it.storageName })
                } else {
                    SettingsStore(context).setProfileChoice(choice)
                    Log.i(TAG, "SET_PROFILE profile=${choice.storageName}")
                }
            }

            ACTION_SET_RATE -> {
                val sensorName = intent.getStringExtra("sensor")
                val hz = intent.getStringExtra("hz")?.toIntOrNull()
                val sensor = sensorName?.uppercase()?.let { n ->
                    runCatching { SensorType.valueOf(n) }.getOrNull()
                }
                if (sensor == null || hz == null) {
                    Log.w(TAG, "SET_RATE: need --es sensor PPG|ACC|GYRO --es hz <int>, got sensor=$sensorName hz=$hz")
                } else {
                    SettingsStore(context).setCustomRate(sensor, hz)
                    Log.i(TAG, "SET_RATE sensor=$sensor hz=$hz")
                }
            }

            ACTION_RESET_SETTINGS -> {
                SettingsStore(context).resetToDefaults()
                Log.i(TAG, "RESET_SETTINGS -> ${SettingsStore(context).get()}")
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
                        val result = if (pair) client.pairAndRead(address) else client.readRecords(address)
                        val readings = result.readings
                        val store = com.polarppgbp.omron.CuffStore(java.io.File(context.filesDir, "cuff"))
                        val res = store.ingest(readings, address, result.clock)
                        Log.i(TAG, "${intent.action}: ${readings.size} on cuff, ${res.newCount} new, ${res.total} stored total")
                        Log.i(TAG, "  stored at ${res.path}")
                        // #9: drift is only useful if it is visible.
                        Log.i(TAG, "  CLOCK ${result.clock.detail}")
                        Log.i(TAG, "  clock json ${result.clock.toJson()}")
                        if (res.quarantinedCount > 0) {
                            Log.w(TAG, "  QUARANTINED ${res.quarantinedCount} reading(s) with an untrustworthy timestamp")
                        }
                        SyncScheduler.enqueueCuff(context.applicationContext)
                        // Sort before taking the tail: decodeRegion() returns
                        // records in ring-buffer *slot* order, not chronological
                        // order, so a bare takeLast() prints arbitrary slots
                        // while implying they are the most recent readings.
                        readings.sortedBy { it.takenAtIso() }.takeLast(12).forEach { r ->
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

            ACTION_READ_CUFF_SETTINGS -> {
                // Hardware probe for #9 (cuff clock) and #10 (unread counter).
                // Dumps the settings region raw, annotated with the offset
                // ranges we believe are meaningful, so the real byte layout can
                // be confirmed before either issue is implemented. Read-only.
                val address = intent.getStringExtra("address")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val client = OmronCuffClient(
                            context.applicationContext,
                            onStatus = { Log.i(TAG, "cuff: $it") },
                        )
                        val region = client.readSettingsRegion(address)
                        val base = com.polarppgbp.omron.OmronProtocol.SETTINGS_READ_ADDR
                        Log.i(TAG, "READ_CUFF_SETTINGS: ${region.size} bytes @ 0x%04X".format(base))
                        region.toList().chunked(16).forEachIndexed { row, chunk ->
                            val off = row * 16
                            Log.i(
                                TAG,
                                "  +0x%02X (0x%04X): %s".format(
                                    off, base + off,
                                    chunk.joinToString(" ") { "%02X".format(it) },
                                ),
                            )
                        }
                        val phoneNow = System.currentTimeMillis()
                        Log.i(TAG, "  phone time at read: $phoneNow (${java.util.Date(phoneNow)})")
                        fun slice(range: Pair<Int, Int>, label: String) {
                            val (from, to) = range
                            if (to > region.size) {
                                Log.w(TAG, "  $label: region too short (${region.size} bytes, need $to)")
                                return
                            }
                            Log.i(
                                TAG,
                                "  $label [0x%02X..0x%02X): %s".format(
                                    from, to,
                                    region.slice(from until to).joinToString(" ") { "%02X".format(it) },
                                ),
                            )
                        }
                        slice(com.polarppgbp.omron.OmronProtocol.UNREAD_RECORDS_RANGE, "UNREAD_RECORDS #10")
                        slice(com.polarppgbp.omron.OmronProtocol.TIME_SYNC_RANGE, "TIME_SYNC      #9")
                    } catch (e: Exception) {
                        Log.e(TAG, "READ_CUFF_SETTINGS failed: ${e.message}", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_WRITE_CUFF_TIME -> {
                // #18: recover a halted cuff RTC. The ONLY write path to the cuff.
                // Defaults to the phone's current local time; `--es time` accepts
                // "yyyy-MM-ddTHH:mm:ss". Refuses to write unless the clock is
                // halted, unless `--ez force true`.
                val address = intent.getStringExtra("address")
                val force = intent.getBooleanExtra("force", false)
                val timeArg = intent.getStringExtra("time")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        var setpoint: OmronCuffClient.ClockSetpoint? = null
                        if (timeArg != null) {
                            val m = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})""")
                                .matchEntire(timeArg.trim())
                                ?: throw IllegalArgumentException(
                                    "bad --es time '$timeArg', want yyyy-MM-ddTHH:mm:ss",
                                )
                            val g = m.groupValues.drop(1).map { it.toInt() }
                            setpoint = OmronCuffClient.ClockSetpoint(g[0], g[1], g[2], g[3], g[4], g[5])
                        }
                        Log.i(
                            TAG,
                            "WRITE_CUFF_TIME setpoint=${setpoint ?: "phone clock at write time"} force=$force",
                        )
                        val client = OmronCuffClient(
                            context.applicationContext,
                            onStatus = { Log.i(TAG, "cuff: $it") },
                        )
                        val r = client.writeCuffClock(
                            deviceAddress = address,
                            setpoint = setpoint,
                            force = force,
                        )
                        val base = com.polarppgbp.omron.OmronProtocol.SETTINGS_READ_ADDR
                        fun dump(label: String, bytes: ByteArray) {
                            Log.i(TAG, "  $label (${bytes.size} bytes @ 0x%04X)".format(base))
                            bytes.toList().chunked(16).forEachIndexed { row, chunk ->
                                val off = row * 16
                                Log.i(
                                    TAG,
                                    "    +0x%02X (0x%04X): %s".format(
                                        off, base + off,
                                        chunk.joinToString(" ") { "%02X".format(it) },
                                    ),
                                )
                            }
                        }
                        Log.i(TAG, "WRITE_CUFF_TIME: wrote @0x%04X".format(r.writeAddress))
                        Log.i(TAG, "  payload: ${com.polarppgbp.omron.OmronProtocol.bytesToHex(r.payload)}")
                        dump("BEFORE", r.snapshotBefore)
                        dump("AFTER ", r.snapshotAfter)
                        Log.i(TAG, "  clockBefore: ${r.clockBefore}")
                        Log.i(TAG, "  clockAfter : ${r.clockAfter}")
                        if (r.unexpectedChanges.isEmpty()) {
                            Log.i(TAG, "  VERIFY OK: no bytes changed outside the time block")
                        } else {
                            Log.e(
                                TAG,
                                "  VERIFY FAIL: unexpected changes at offsets " +
                                    r.unexpectedChanges.joinToString(", ") { "0x%02X".format(it) },
                            )
                        }
                        Log.i(
                            TAG,
                            "  clock now valid=${r.clockAfter.valid} halted=${r.clockAfter.halted}",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "WRITE_CUFF_TIME failed: ${e.message}", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_REPAIR_CUFF_CLOCK -> {
                // #18 hardware verification without driving the UI. `force` writes even
                // when the clock is not halted, which is how the write-and-verify
                // mechanics can be exercised on a working cuff.
                val force = intent.getBooleanExtra("force", false)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val client = OmronCuffClient(
                            context.applicationContext,
                            onStatus = { Log.i(TAG, "cuff: $it") },
                        )
                        val report = client.repairCuffClock(force = force)
                        Log.i(TAG, "REPAIR outcome=${report.outcome} succeeded=${report.succeeded}")
                        Log.i(TAG, "  wrote=${report.wroteIso} verified=${report.verifiedIso} offset=${report.verifiedOffsetSeconds}")
                        Log.i(TAG, "  detail=${report.detail}")
                    } catch (e: Exception) {
                        Log.e(TAG, "REPAIR failed: ${e.message}", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_CHECK_SERVER -> {
                // #16: run the same staged health check the Settings button runs, so the
                // stage classification can be verified without driving the UI.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val report = com.polarppgbp.sync.ServerHealth.check(context.applicationContext)
                        Log.i(TAG, "CHECK_SERVER stage=${report.stage} ok=${report.ok}")
                        Log.i(TAG, "  url=${report.url} version=${report.serverVersion} " +
                            "compatible=${report.versionCompatible}")
                        Log.i(TAG, "  detail=${report.detail}")
                    } catch (e: Exception) {
                        Log.e(TAG, "CHECK_SERVER failed: ${e.message}", e)
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
        const val ACTION_REPAIR_CUFF_CLOCK = "com.polarppgbp.debug.REPAIR_CUFF_CLOCK"
        const val ACTION_CHECK_SERVER = "com.polarppgbp.debug.CHECK_SERVER"
        const val ACTION_READ_CUFF_SETTINGS = "com.polarppgbp.debug.READ_CUFF_SETTINGS"
        const val ACTION_WRITE_CUFF_TIME = "com.polarppgbp.debug.WRITE_CUFF_TIME"
        const val ACTION_PAIR_CUFF = "com.polarppgbp.debug.PAIR_CUFF"
        const val ACTION_GET_SETTINGS = "com.polarppgbp.debug.GET_SETTINGS"
        const val ACTION_SET_PROFILE = "com.polarppgbp.debug.SET_PROFILE"
        const val ACTION_SET_RATE = "com.polarppgbp.debug.SET_RATE"
        const val ACTION_RESET_SETTINGS = "com.polarppgbp.debug.RESET_SETTINGS"
    }
}
