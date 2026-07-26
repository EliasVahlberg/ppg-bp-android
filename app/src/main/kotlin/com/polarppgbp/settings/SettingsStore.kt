/*
 * Persisted recording settings (#1) — profile choice and per-sensor sample
 * rates, backed by the same SharedPreferences file every other prefs-based
 * component already uses (PREFS_NAME, see RecordingService.kt).
 *
 * Write-through: every setter applies immediately (no separate "save" step)
 * — matches how CuffStore/other settings in this app already persist.
 *
 * The candidate rate lists are fixed (Option A from the #1 scoping
 * discussion) rather than queried live from the device: we know the exact
 * sensor hardware we support (Polar Verity Sense / Polar Sense), so the
 * picker can work fully offline, before a sensor is ever in range. Live
 * validation against whatever the device actually reports happens
 * separately (see PolarRepository's cached-capabilities check), at
 * recording-start time, not configuration time.
 */

package com.polarppgbp.settings

import android.content.Context
import com.polarppgbp.KEY_SERVER_TOKEN
import com.polarppgbp.KEY_SERVER_URL
import com.polarppgbp.PREFS_NAME
import com.polarppgbp.recorder.Profile
import com.polarppgbp.rop.SensorType

const val KEY_PROFILE_NAME = "settings_profile_name"
const val KEY_CUSTOM_PPG_HZ = "settings_custom_ppg_hz"
const val KEY_CUSTOM_ACC_HZ = "settings_custom_acc_hz"
const val KEY_CUSTOM_GYRO_HZ = "settings_custom_gyro_hz"

/** Candidate sample rates per sensor, from the Polar Verity Sense online
 * streaming spec (docs/products/PolarVeritySense.md in the SDK reference,
 * confirmed against a real device's reported PolarSensorSetting during
 * manual testing). Offline-recording rates are a different, smaller set —
 * not used here since this app always uses online (BLE) streaming. */
object SupportedRates {
    val PPG = listOf(28, 44, 55, 135, 176)
    val ACC = listOf(26, 52, 104, 208, 416)
    val GYRO = listOf(26, 52, 104, 208, 416)

    fun forSensor(sensor: SensorType): List<Int> = when (sensor) {
        SensorType.PPG -> PPG
        SensorType.ACC -> ACC
        SensorType.GYRO -> GYRO
        else -> emptyList()
    }
}

enum class ProfileChoice(val storageName: String, val label: String) {
    CALIBRATION("calibration", "Calibration"),
    MONITOR("monitor", "Monitor"),
    CUSTOM("custom", "Custom");

    companion object {
        fun fromStorageName(name: String?): ProfileChoice =
            entries.firstOrNull { it.storageName == name } ?: DEFAULT

        val DEFAULT = CALIBRATION
    }
}

data class RecordingSettings(
    val profileChoice: ProfileChoice,
    val customPpgHz: Int,
    val customAccHz: Int,
    val customGyroHz: Int,
) {
    /** Resolve to the Profile that RecordingService/PolarRepository actually consume. */
    fun toProfile(): Profile = when (profileChoice) {
        ProfileChoice.CALIBRATION -> Profile.CALIBRATION
        ProfileChoice.MONITOR -> Profile.MONITOR
        ProfileChoice.CUSTOM -> Profile(
            "custom",
            mapOf(
                SensorType.PPG to customPpgHz,
                SensorType.ACC to customAccHz,
                SensorType.GYRO to customGyroHz,
            ),
        )
    }

    companion object {
        val DEFAULT = RecordingSettings(
            profileChoice = ProfileChoice.DEFAULT,
            customPpgHz = Profile.CALIBRATION.rates[SensorType.PPG]!!,
            customAccHz = Profile.CALIBRATION.rates[SensorType.ACC]!!,
            customGyroHz = Profile.CALIBRATION.rates[SensorType.GYRO]!!,
        )
    }
}

/**
 * Thin, write-through wrapper over SharedPreferences for recording settings.
 * Every setter applies immediately; there is no separate save/commit step.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): RecordingSettings {
        val default = RecordingSettings.DEFAULT
        return RecordingSettings(
            profileChoice = ProfileChoice.fromStorageName(prefs.getString(KEY_PROFILE_NAME, null)),
            customPpgHz = prefs.getInt(KEY_CUSTOM_PPG_HZ, default.customPpgHz),
            customAccHz = prefs.getInt(KEY_CUSTOM_ACC_HZ, default.customAccHz),
            customGyroHz = prefs.getInt(KEY_CUSTOM_GYRO_HZ, default.customGyroHz),
        )
    }

    fun setProfileChoice(choice: ProfileChoice) {
        prefs.edit().putString(KEY_PROFILE_NAME, choice.storageName).apply()
    }

    fun setCustomRate(sensor: SensorType, hz: Int) {
        val key = when (sensor) {
            SensorType.PPG -> KEY_CUSTOM_PPG_HZ
            SensorType.ACC -> KEY_CUSTOM_ACC_HZ
            SensorType.GYRO -> KEY_CUSTOM_GYRO_HZ
            else -> return
        }
        prefs.edit().putInt(key, hz).apply()
    }

    // ---- server configuration (#15) ----
    //
    // Reads and writes the same KEY_SERVER_URL / KEY_SERVER_TOKEN that SyncWorker and
    // CuffSyncWorker already consume, so a value set earlier by the debug SET_SERVER
    // broadcast keeps working and the UI shows it rather than a blank field.

    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)

    fun getServerToken(): String? = prefs.getString(KEY_SERVER_TOKEN, null)

    fun isServerConfigured(): Boolean =
        !getServerUrl().isNullOrBlank() && !getServerToken().isNullOrBlank()

    /**
     * Persist an already-validated URL/token pair. Takes [ServerConfigResult.Valid] so
     * an unvalidated string cannot reach storage by mistake -- a malformed value here
     * surfaces as sync silently never running.
     */
    fun setServer(valid: ServerConfigResult.Valid) {
        prefs.edit()
            .putString(KEY_SERVER_URL, valid.url)
            .putString(KEY_SERVER_TOKEN, valid.token)
            .apply()
    }

    fun clearServer() {
        prefs.edit().remove(KEY_SERVER_URL).remove(KEY_SERVER_TOKEN).apply()
    }

    /** Resets profile + custom rates to RecordingSettings.DEFAULT. Does not
     * touch unrelated prefs (device id, server url/token). */
    fun resetToDefaults() {
        val default = RecordingSettings.DEFAULT
        prefs.edit()
            .putString(KEY_PROFILE_NAME, default.profileChoice.storageName)
            .putInt(KEY_CUSTOM_PPG_HZ, default.customPpgHz)
            .putInt(KEY_CUSTOM_ACC_HZ, default.customAccHz)
            .putInt(KEY_CUSTOM_GYRO_HZ, default.customGyroHz)
            .apply()
    }
}
