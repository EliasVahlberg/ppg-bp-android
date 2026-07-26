/*
 * Foreground service for continuous PPG recording.
 * Keeps the app alive when screen is off and prevents OnePlus HANS from freezing.
 */

package com.polarppgbp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.polarppgbp.recorder.Profile
import com.polarppgbp.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "RecordingService"
private const val CHANNEL_ID = "polar_recording"
private const val NOTIF_ID = 1
const val PREFS_NAME = "polar_prefs"
const val KEY_DEVICE_ID = "last_device_id"
const val KEY_SERVER_URL = "server_url"
const val KEY_SERVER_TOKEN = "server_token"

// Shared repository instance for service and activity
object SharedRepo {
    var repo: PolarRepository? = null
    var deviceId: String? = null
    var manualStop: Boolean = false
}

class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var prefs: SharedPreferences? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RecordingService created")
        createNotificationChannel()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        if (SharedRepo.repo == null) {
            SharedRepo.repo = PolarRepository(applicationContext)
        }
        
        scope.launch {
            SharedRepo.repo?.connectionState?.collectLatest { state ->
                updateNotification(state, SharedRepo.repo?.metrics?.value)
                if (state is ConnectionState.Connected) {
                    SharedRepo.deviceId = state.deviceId
                    prefs?.edit()?.putString(KEY_DEVICE_ID, state.deviceId)?.apply()
                }
                if (state is ConnectionState.Idle || state is ConnectionState.Failed) {
                    if (!SharedRepo.manualStop) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            "START" -> {
                val lastDeviceId = prefs?.getString(KEY_DEVICE_ID, null)
                    ?.takeIf { it.isNotBlank() }
                    ?: BuildConfig.DEFAULT_DEVICE_ID.takeIf { it.isNotBlank() }
                if (lastDeviceId.isNullOrBlank()) {
                    // The caller already called startForegroundService(), so Android
                    // requires startForeground() to be called within a few seconds
                    // regardless of outcome -- skipping it here (as this code
                    // previously did) crashes the whole app process with a
                    // ForegroundServiceDidNotStartInTimeException, not just this
                    // service. Start briefly with an explanatory notification, then
                    // stop cleanly, rather than silently killing the app on a plain
                    // config error.
                    startForeground(NOTIF_ID, createNotification(ConnectionState.Idle, null))
                    Log.w(TAG, "No device ID configured (no prior pairing, no DEFAULT_DEVICE_ID build config). " +
                        "Use the debug SET_SERVER/device_id broadcast or pair from the UI first.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                // #17: a hard blocker (radio off, permission revoked) will never clear by
                // itself, so starting a session would produce an empty bundle and a UI
                // that claims to be reconnecting. Refuse loudly instead, mirroring the
                // missing-device-ID path above and #1's fail-loudly principle.
                val blocker = SharedRepo.repo?.currentBlocker()
                if (blocker != null) {
                    startForeground(
                        NOTIF_ID,
                        createNotification(ConnectionState.Blocked(blocker), null),
                    )
                    Log.w(TAG, "Not starting: ${blocker.label}. ${blocker.remedy}")
                    SharedRepo.repo?.refreshBlocker()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                SharedRepo.manualStop = false
                startForeground(NOTIF_ID, createNotification(ConnectionState.Idle, null))
                val profileOverride = intent.getStringExtra("PROFILE")
                val profile = if (profileOverride != null) {
                    Profile.byName(profileOverride)
                } else {
                    com.polarppgbp.settings.SettingsStore(this).get().toProfile()
                }
                SharedRepo.repo?.startSession(
                    profile,
                    SettingsStore(applicationContext).getRotationPeriodMinutes(),
                )
                Log.i(TAG, "Auto-connecting to device: $lastDeviceId (profile=${profile.name})")
                SharedRepo.deviceId = lastDeviceId
                SharedRepo.repo?.connect(lastDeviceId)
                startHeartbeat()
            }
            "STOP" -> {
                SharedRepo.manualStop = true
                stopHeartbeat()
                SharedRepo.repo?.disconnect()
                val bundleDir = SharedRepo.repo?.stopSession()
                if (bundleDir != null) {
                    com.polarppgbp.sync.SyncScheduler.enqueue(applicationContext, bundleDir)
                    Log.i(TAG, "Enqueued sync for bundle: $bundleDir")
                } else {
                    Log.i(TAG, "No active session to finalise")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "RecordingService destroyed")
        reconnectJob?.cancel()
        stopHeartbeat()
        SharedRepo.repo?.disconnect()
        SharedRepo.repo?.stopSession()
        // The connectionState collector lives in `scope` and was never cancelled, so a
        // destroyed service kept observing state and re-arming scheduleReconnect() --
        // disconnect() above sets Idle, which the collector reads as "link dropped".
        // Result: a zombie reconnect loop outliving the service. Cancel last, after the
        // teardown above has run.
        scope.cancel()
        super.onDestroy()
    }

    /** Periodically fsync open ROP writers so a crash loses minimal data. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(10_000L)
                SharedRepo.repo?.syncSession()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val deviceId = SharedRepo.deviceId ?: prefs?.getString(KEY_DEVICE_ID, null) ?: return@launch
            var delayMs = 1000L
            var loggedBlocker: Blocker? = null
            while (SharedRepo.repo?.connectionState?.value !is ConnectionState.Connected) {
                // #17: never retry through a hard blocker. Backoff cannot turn a radio
                // back on, and attempting it reports "reconnecting" for a link that will
                // not return. Hold here, quietly, until the blocker clears -- turning
                // Bluetooth back on mid-session then resumes the recording rather than
                // requiring the user to notice and restart.
                val state = SharedRepo.repo?.connectionState?.value
                if (state is ConnectionState.Blocked) {
                    if (loggedBlocker != state.cause) {
                        Log.w(TAG, "Not reconnecting: ${state.cause.label}. ${state.cause.remedy}")
                        loggedBlocker = state.cause
                    }
                    delayMs = 1000L
                    delay(2_000L)
                    continue
                }
                loggedBlocker = null
                Log.i(TAG, "Reconnecting to $deviceId in ${delayMs}ms")
                delay(delayMs)
                if (SharedRepo.repo?.connectionState?.value !is ConnectionState.Connected) {
                    SharedRepo.repo?.connect(deviceId)
                }
                delayMs = minOf(delayMs * 2, 30_000L)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Polar Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous PPG recording status"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(state: ConnectionState?, metrics: LiveMetrics?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = when (state) {
            is ConnectionState.Blocked -> state.cause.label
            is ConnectionState.Connected -> "Recording: ${state.name}"
            is ConnectionState.Connecting -> "Connecting..."
            is ConnectionState.Searching -> "Searching..."
            else -> "Polar BP Recorder"
        }
        
        val text = when (state) {
            is ConnectionState.Blocked -> "Not recording. ${state.cause.remedy}"
            is ConnectionState.Connected -> "PPG: ${metrics?.ppgSamples ?: 0} | ACC: ${metrics?.accSamples ?: 0} | GYRO: ${metrics?.gyroSamples ?: 0}"
            is ConnectionState.Failed -> "Disconnected: ${state.reason}"
            else -> "Waiting for device"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: ConnectionState?, metrics: LiveMetrics?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, createNotification(state, metrics))
    }
}
