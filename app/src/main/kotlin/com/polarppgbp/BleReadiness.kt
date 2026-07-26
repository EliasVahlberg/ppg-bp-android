package com.polarppgbp

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * A hard, non-transient reason the BLE link cannot work (#17).
 *
 * These are deliberately distinct from [ConnectionState.Failed]: a failure is
 * something a retry might resolve, whereas a blocker will never clear on its own
 * no matter how long the app waits. Retrying through one wastes battery and, worse,
 * reports "reconnecting" to the user when nothing is going to reconnect.
 */
enum class Blocker(val label: String, val remedy: String) {
    PERMISSION_MISSING(
        label = "Bluetooth permission missing",
        remedy = "Grant the Bluetooth permissions to record.",
    ),
    BLUETOOTH_OFF(
        label = "Bluetooth is off",
        remedy = "Turn Bluetooth on to record.",
    ),
}

object BleReadiness {

    /** Runtime permissions the Polar link needs. minSdk is 33, so both are always runtime. */
    val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /**
     * Pure decision, kept separate from the Android lookups so it can be unit-tested.
     *
     * Permission outranks radio state because on Android 12+ even *asking* the user to
     * enable Bluetooth (`ACTION_REQUEST_ENABLE`) requires `BLUETOOTH_CONNECT`. Reporting
     * "Bluetooth is off" first would send the user to a remedy that cannot work yet.
     */
    fun blockerOf(permissionsGranted: Boolean, bluetoothOn: Boolean): Blocker? = when {
        !permissionsGranted -> Blocker.PERMISSION_MISSING
        !bluetoothOn -> Blocker.BLUETOOTH_OFF
        else -> null
    }

    fun hasPermissions(context: Context): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reads the adapter directly rather than `Settings.Global.bluetooth_on`. Observed
     * 2026-07-26 on OxygenOS: the global setting still read 1 while the adapter was
     * genuinely off, so the setting cannot be trusted.
     */
    fun isBluetoothOn(context: Context): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    fun current(context: Context): Blocker? =
        blockerOf(hasPermissions(context), isBluetoothOn(context))
}
