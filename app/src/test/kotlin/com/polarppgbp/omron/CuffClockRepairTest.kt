package com.polarppgbp.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #18: "did the repair work" has a subtle wrong answer. A write that sets the value
 * without starting the RTC looks exactly like success at the moment of writing, so the
 * classification is pinned here.
 */
class CuffClockRepairTest {

    private fun obs(
        cuffIso: String? = "2026-07-26T23:00:20",
        offset: Long? = 0L,
        valid: Boolean = true,
        halted: Boolean = false,
        detail: String = "test",
    ) = CuffClockObservation(
        cuffIso = cuffIso,
        phoneIso = "2026-07-26T23:00:20",
        offsetSeconds = offset,
        uncertaintySeconds = 1,
        clockValid = valid,
        halted = halted,
        detail = detail,
    )

    @Test
    fun repairIsOfferedOnlyForAHaltedClock() {
        assertTrue(CuffClockRepair.needed(obs(halted = true, valid = false, offset = null)))
        // Ordinary drift is a measurement problem (#9), not a broken device. Writing for
        // drift would mutate device state on a schedule, which #18 explicitly rules out.
        assertFalse(CuffClockRepair.needed(obs(offset = 300)))
        assertFalse(CuffClockRepair.needed(obs(offset = 0)))
    }

    @Test
    fun agreementAfterTheSettleDelayProvesTheClockIsRunning() {
        val r = CuffClockRepair.classify("2026-07-26T23:00:00", obs(offset = -1), 20_000)
        assertEquals(RepairOutcome.WRITTEN_AND_ADVANCING, r.outcome)
        assertTrue(r.succeeded)
        assertTrue("must explain why one read is enough", r.detail.contains("would be 20s behind"))
    }

    @Test
    fun valueTookButClockFrozenIsNotReportedAsSuccess() {
        // The written value is still there, but 20 s later it is 20 s behind the phone:
        // the RTC never started. This is the failure mode that would otherwise pass.
        val r = CuffClockRepair.classify("2026-07-26T23:00:00", obs(offset = -20), 20_000)
        assertEquals(RepairOutcome.WRITTEN_NOT_ADVANCING, r.outcome)
        assertFalse(r.succeeded)
        assertTrue(r.detail.contains("without advancing"))
    }

    @Test
    fun sentinelStillPresentMeansTheRtcIsNotRunning() {
        val r = CuffClockRepair.classify(
            "2026-07-26T23:00:00",
            obs(halted = true, valid = false, offset = null),
            20_000,
        )
        assertEquals(RepairOutcome.WRITTEN_NOT_ADVANCING, r.outcome)
        assertTrue("should point at the battery", r.detail.contains("battery"))
    }

    @Test
    fun writeFailureLeavesTheDeviceUnchangedAndSaysSo() {
        val r = CuffClockRepair.classify(null, null, 0, writeError = "Timed out waiting for 15000 ms")
        assertEquals(RepairOutcome.FAILED, r.outcome)
        assertFalse(r.succeeded)
        assertTrue(r.detail.contains("cuff is unchanged"))
    }

    @Test
    fun missedVerificationIsNotAFailedWrite() {
        // The cuff sleeps within about a minute, so a missed reconnect is expected and
        // must not be reported as a broken clock.
        val r = CuffClockRepair.classify("2026-07-26T23:00:00", null, 20_000)
        assertEquals(RepairOutcome.WRITTEN_UNVERIFIED, r.outcome)
        assertTrue(r.detail.contains("transfer button"))
    }

    @Test
    fun undecodableVerificationIsUnverifiedNotBroken() {
        val r = CuffClockRepair.classify(
            "2026-07-26T23:00:00",
            obs(valid = false, offset = null, detail = "checksum bad"),
            20_000,
        )
        assertEquals(RepairOutcome.WRITTEN_UNVERIFIED, r.outcome)
    }

    @Test
    fun anUnexpectedOffsetIsNotClaimedAsSuccess() {
        // Cuff far ahead: not frozen, not right either. Must not read as success.
        val r = CuffClockRepair.classify("2026-07-26T23:00:00", obs(offset = 3600), 20_000)
        assertEquals(RepairOutcome.WRITTEN_UNVERIFIED, r.outcome)
        assertFalse(r.succeeded)
    }

    @Test
    fun settleDelayMustExceedTheAdvancingTolerance() {
        // Otherwise a frozen clock falls inside the tolerance and passes as running.
        assertTrue(
            CuffClockRepair.SETTLE_MS / 1000 > CuffClockRepair.ADVANCING_TOLERANCE_S * 2,
        )
    }
}
