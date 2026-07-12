/*
 * Unit test for UnsupportedRateException — the #1 acceptance criteria that
 * requesting a sample rate the connected device doesn't support fails loudly
 * (this exception) rather than silently substituting the device's max rate.
 *
 * chooseSetting() itself stays private to PolarRepository (it's a small
 * adapter over the live PolarSensorSetting from a real BLE connection); the
 * exception type it throws is the part worth a standalone contract test.
 * The end-to-end behavior (does a real unsupported-rate request actually
 * stop the session with ConnectionState.Failed) is covered by manual/e2e
 * verification against real hardware, not JVM unit tests, since it needs an
 * actual Polar device's reported capabilities.
 *
 * Pure JVM (no Android deps); runs under testDebugUnitTest.
 */

package com.polarppgbp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedRateExceptionTest {

    @Test
    fun carriesRequestedAndAvailableRates() {
        val e = UnsupportedRateException(300, setOf(55, 135, 176))
        assertEquals(300, e.requestedHz)
        assertEquals(setOf(55, 135, 176), e.availableHz)
    }

    @Test
    fun messageIsHumanReadable() {
        val e = UnsupportedRateException(300, setOf(55, 135, 176))
        assertTrue(e.message!!.contains("300"))
        assertTrue(e.message!!.contains("135"))
    }
}
