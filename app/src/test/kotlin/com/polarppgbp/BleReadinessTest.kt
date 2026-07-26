package com.polarppgbp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #17: hard blockers must be distinguishable from a dropped link.
 *
 * The Android lookups are separated from the decision so the decision itself is
 * testable without a device.
 */
class BleReadinessTest {

    @Test
    fun readyWhenPermissionsGrantedAndRadioOn() {
        assertNull(BleReadiness.blockerOf(permissionsGranted = true, bluetoothOn = true))
    }

    @Test
    fun radioOffIsABlocker() {
        assertEquals(
            Blocker.BLUETOOTH_OFF,
            BleReadiness.blockerOf(permissionsGranted = true, bluetoothOn = false),
        )
    }

    @Test
    fun missingPermissionIsABlocker() {
        assertEquals(
            Blocker.PERMISSION_MISSING,
            BleReadiness.blockerOf(permissionsGranted = false, bluetoothOn = true),
        )
    }

    @Test
    fun permissionOutranksRadioState() {
        // Both wrong: report the permission, because ACTION_REQUEST_ENABLE itself needs
        // BLUETOOTH_CONNECT on Android 12+. Reporting "Bluetooth is off" first would
        // offer a remedy that cannot work yet.
        assertEquals(
            Blocker.PERMISSION_MISSING,
            BleReadiness.blockerOf(permissionsGranted = false, bluetoothOn = false),
        )
    }

    @Test
    fun everyBlockerCarriesUserFacingText() {
        // The whole point of #17 is that the user is told what is wrong and what to do.
        Blocker.entries.forEach {
            assertEquals("$it label should be non-blank", true, it.label.isNotBlank())
            assertEquals("$it remedy should be non-blank", true, it.remedy.isNotBlank())
        }
    }

    // ---- phase mapping: the misinformation this issue is really about ----

    @Test
    fun blockedNeverReportsReconnecting() {
        // Regression guard for the observed bug: Bluetooth off while recording used to
        // render as "Lost connection — reconnecting", promising a recovery that could
        // never happen.
        val blocked = ConnectionState.Blocked(Blocker.BLUETOOTH_OFF)
        assertEquals(Phase.BLOCKED, phaseOf(recording = true, s = blocked))
        assertEquals(Phase.BLOCKED, phaseOf(recording = false, s = blocked))
    }

    @Test
    fun droppedLinkStillReportsReconnecting() {
        assertEquals(
            Phase.RECONNECTING,
            phaseOf(recording = true, s = ConnectionState.Failed("link lost")),
        )
        assertEquals(
            Phase.RECONNECTING,
            phaseOf(recording = true, s = ConnectionState.Idle),
        )
    }

    @Test
    fun unaffectedPhasesAreUnchanged() {
        assertEquals(Phase.STOPPED, phaseOf(false, ConnectionState.Idle))
        assertEquals(Phase.CAPTURING, phaseOf(true, ConnectionState.Connected("abc", "Sense")))
        assertEquals(Phase.CONNECTING, phaseOf(true, ConnectionState.Connecting("abc")))
        assertEquals(Phase.CONNECTING, phaseOf(true, ConnectionState.Searching()))
    }

    @Test
    fun blockedDetailNamesTheCauseAndTheFix() {
        val detail = detailOf(true, ConnectionState.Blocked(Blocker.BLUETOOTH_OFF))
        assertEquals(true, detail.contains(Blocker.BLUETOOTH_OFF.label))
        assertEquals(true, detail.contains(Blocker.BLUETOOTH_OFF.remedy))
    }
}
