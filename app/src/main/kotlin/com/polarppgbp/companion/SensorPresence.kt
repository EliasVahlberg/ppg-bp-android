package com.polarppgbp.companion

import android.companion.CompanionDeviceManager
import android.content.Context
import android.util.Log

/**
 * Arms and disarms presence observation for the associated sensor (#21).
 *
 * An association alone is not enough. Until something calls
 * [CompanionDeviceManager.startObservingDevicePresence] the association carries
 * `mNotifyOnDeviceNearby=false` and the system never binds
 * [SensorPresenceService] -- verified on device by creating an association from the
 * shell and watching `dumpsys companiondevice` report an empty bound-applications list.
 *
 * Observation is not documented to survive a reboot, and there are field reports of it
 * silently stopping until re-armed, so this is called from app start and from
 * BOOT_COMPLETED rather than once at association time. Re-arming an already-armed
 * association is harmless.
 */
object SensorPresence {

    private const val TAG = "SensorPresence"

    /** True when at least one association is now being observed. */
    fun arm(context: Context): Boolean = forEachAssociation(context) { cdm, mac ->
        cdm.startObservingDevicePresence(mac)
        Log.i(TAG, "observing presence of $mac")
    }

    fun disarm(context: Context): Boolean = forEachAssociation(context) { cdm, mac ->
        cdm.stopObservingDevicePresence(mac)
        Log.i(TAG, "stopped observing $mac")
    }

    fun associatedMacs(context: Context): List<String> =
        runCatching {
            context.getSystemService(CompanionDeviceManager::class.java)
                ?.myAssociations
                ?.mapNotNull { it.deviceMacAddress?.toString() }
                ?: emptyList()
        }.getOrElse {
            Log.w(TAG, "could not read associations", it)
            emptyList()
        }

    private inline fun forEachAssociation(
        context: Context,
        action: (CompanionDeviceManager, String) -> Unit,
    ): Boolean {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        val macs = associatedMacs(context)
        if (macs.isEmpty()) {
            Log.i(TAG, "no association yet, nothing to observe")
            return false
        }
        var any = false
        for (mac in macs) {
            // One bad association must not stop the others being armed, and a throw here
            // would propagate into app startup.
            runCatching { action(cdm, mac) }
                .onSuccess { any = true }
                .onFailure { Log.w(TAG, "presence observation failed for $mac", it) }
        }
        return any
    }
}
