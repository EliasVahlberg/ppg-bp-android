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
import kotlinx.coroutines.CoroutineScope
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
                SharedRepo.manualStop = false
                startForeground(NOTIF_ID, createNotification(ConnectionState.Idle, null))
                val profileName = intent.getStringExtra("PROFILE") ?: "calibration"
                SharedRepo.repo?.startSession(Profile.byName(profileName))
                val lastDeviceId = prefs?.getString(KEY_DEVICE_ID, null) ?: "156AC536"
                Log.i(TAG, "Auto-connecting to device: $lastDeviceId (profile=$profileName)")
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
            while (SharedRepo.repo?.connectionState?.value !is ConnectionState.Connected) {
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
            is ConnectionState.Connected -> "Recording: ${state.name}"
            is ConnectionState.Connecting -> "Connecting..."
            is ConnectionState.Searching -> "Searching..."
            else -> "Polar BP Recorder"
        }
        
        val text = when (state) {
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
