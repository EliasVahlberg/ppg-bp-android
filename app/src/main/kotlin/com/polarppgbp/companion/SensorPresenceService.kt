package com.polarppgbp.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.polarppgbp.KEY_DEVICE_ID
import com.polarppgbp.PREFS_NAME
import com.polarppgbp.RecordingService
import com.polarppgbp.SharedRepo

/**
 * Starts a recording when the Polar sensor becomes visible (#21).
 *
 * The system binds this service when an associated device appears, which has two
 * properties a self-managed BLE scan does not: it works with the app closed, and the
 * binding raises the process priority so the low-memory killer is less likely to take
 * the process during a multi-hour recording.
 *
 * Starting a foreground service from the background is normally refused on Android 12+.
 * A companion app is one of the documented exemptions, which is the whole reason for
 * choosing this route over AlarmManager: verified on device by watching for
 * `reasonCode:ALARM_MANAGER`-style allowlisting in the ActivityManager log rather than
 * the `SYSTEM_ALLOW_LISTED` grant that an adb broadcast produces (adb makes any start
 * look permitted, so it cannot be used to test this).
 */
@RequiresApi(Build.VERSION_CODES.S)
class SensorPresenceService : CompanionDeviceService() {

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val decision = AutoRecordPolicy.decide(
            enabled = prefs.getBoolean(KEY_AUTO_RECORD, false),
            alreadyRecording = SharedRepo.repo?.recording?.value == true,
            lastAutoStartMs = prefs.getLong(KEY_LAST_AUTO_START, 0L).takeIf { it > 0L },
            nowMs = System.currentTimeMillis(),
        )
        Log.i(TAG, "onDeviceAppeared: ${associationInfo.deviceMacAddress} -> $decision")

        when (decision) {
            is AutoRecordPolicy.Decision.Skip -> {
                prefs.edit().putString(KEY_LAST_AUTO_RESULT, "skipped: ${decision.reason}").apply()
            }

            AutoRecordPolicy.Decision.Start -> {
                // Remember the device the association named, so a first-ever recording
                // triggered this way has a target even if the user has never picked one
                // in the UI.
                associationInfo.deviceMacAddress?.toString()?.uppercase()?.let {
                    if (prefs.getString(KEY_DEVICE_ID, null).isNullOrBlank()) {
                        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                    }
                }
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong(KEY_LAST_AUTO_START, now)
                    .putString(KEY_LAST_AUTO_RESULT, "starting")
                    .apply()
                try {
                    startForegroundService(
                        Intent(this, RecordingService::class.java).setAction("START")
                    )
                } catch (e: Exception) {
                    // Expected shape of a refusal is ForegroundServiceStartNotAllowedException.
                    // Record it rather than crashing: an unattended start that cannot
                    // happen must leave a trace, because nobody is watching the screen.
                    Log.e(TAG, "auto start refused", e)
                    prefs.edit()
                        .putString(KEY_LAST_AUTO_RESULT, "refused: ${e.javaClass.simpleName}")
                        .apply()
                }
            }
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // Deliberately does nothing. The sensor dropping out of range is exactly the
        // case the reconnect loop exists for, and stopping here would end a session
        // every time she walks away from the phone.
        Log.i(TAG, "onDeviceDisappeared: ${associationInfo.deviceMacAddress}")
    }

    companion object {
        private const val TAG = "SensorPresence"
        const val KEY_AUTO_RECORD = "auto_record_on_presence"
        const val KEY_LAST_AUTO_START = "last_auto_start_ms"
        const val KEY_LAST_AUTO_RESULT = "last_auto_result"
    }
}
