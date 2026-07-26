package com.polarppgbp.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * #9: the cuff timestamp is the only join key to a PPG window, so the drift arithmetic
 * is the part that can be wrong in a way nobody notices. Pinned here rather than
 * inferred from device logs.
 */
class CuffClockObservationTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    /** 2026-07-26 22:30:00 local in [zone]. */
    private fun phoneMs(hour: Int, minute: Int, second: Int): Long =
        java.time.LocalDateTime.of(2026, 7, 26, hour, minute, second)
            .atZone(zone).toInstant().toEpochMilli()

    private fun clock(
        year: Int = 2026, month: Int = 7, day: Int = 26,
        hour: Int = 22, minute: Int = 30, second: Int = 0,
        secondRaw: Int = second, checksumOk: Boolean = true,
    ) = OmronProtocol.CuffClock(year, month, day, hour, minute, second, secondRaw, checksumOk)

    @Test
    fun agreeingClocksReportZeroOffset() {
        val t = phoneMs(22, 30, 0)
        val o = CuffClockObservation.of(clock(), t, t, zone)
        assertEquals(0L, o.offsetSeconds)
        assertTrue(o.clockValid)
        assertFalse(o.halted)
        assertTrue(o.detail.contains("matches the phone"))
    }

    @Test
    fun cuffAheadIsPositive() {
        val t = phoneMs(22, 30, 0)
        val o = CuffClockObservation.of(clock(minute = 35), t, t, zone)
        assertEquals(300L, o.offsetSeconds)
        assertTrue(o.detail.contains("ahead of"))
        assertTrue("5 min of drift can mispair a window", o.detail.contains("mispair"))
    }

    @Test
    fun cuffBehindIsNegative() {
        val t = phoneMs(22, 30, 0)
        val o = CuffClockObservation.of(clock(minute = 25), t, t, zone)
        assertEquals(-300L, o.offsetSeconds)
        assertTrue(o.detail.contains("behind"))
    }

    @Test
    fun offsetIsMeasuredAgainstTheMidpointOfTheRead() {
        // A read spanning 22:30:00..22:30:10 is compared against 22:30:05, so a cuff
        // reading 22:30:05 is exact rather than 5 s off. This is the bug that baked a
        // permanent ~8 s error into the first clock write.
        val o = CuffClockObservation.of(
            clock(second = 5), phoneMs(22, 30, 0), phoneMs(22, 30, 10), zone,
        )
        assertEquals(0L, o.offsetSeconds)
        assertEquals("uncertainty is half the read window", 5L, o.uncertaintySeconds)
        assertTrue(o.detail.contains("±5s") || o.detail.contains("matches"))
    }

    @Test
    fun haltedClockYieldsNoOffsetAtAll() {
        // Elapsed downtime is not drift. Averaging it into the series would corrupt it.
        val t = phoneMs(22, 30, 0)
        val o = CuffClockObservation.of(clock(second = 59, secondRaw = 0x3F), t, t, zone)
        assertTrue(o.halted)
        assertFalse(o.clockValid)
        assertNull("must not report a drift number for a stopped clock", o.offsetSeconds)
        assertTrue(o.detail.contains("halted"))
        assertTrue("must explain the consequence", o.detail.contains("join key"))
    }

    @Test
    fun badChecksumIsNotTrusted() {
        val t = phoneMs(22, 30, 0)
        val o = CuffClockObservation.of(clock(checksumOk = false), t, t, zone)
        assertFalse(o.clockValid)
        assertNull(o.offsetSeconds)
        assertTrue(o.detail.contains("checksum bad"))
    }

    @Test
    fun unreadableClockDoesNotLoseTheRead() {
        val t = phoneMs(22, 30, 0)
        val o = CuffClockObservation.of(null, t, t, zone)
        assertNull(o.cuffIso)
        assertFalse(o.clockValid)
        assertNull(o.offsetSeconds)
        assertTrue(o.detail.contains("could not be read"))
    }

    @Test
    fun unknownOffsetCountsAsExceedingAnyThreshold() {
        // Fail loud: an unmeasurable clock must not read as "no drift".
        val t = phoneMs(22, 30, 0)
        assertTrue(CuffClockObservation.of(null, t, t, zone).exceeds(60))
        assertFalse(CuffClockObservation.of(clock(), t, t, zone).exceeds(60))
        assertTrue(CuffClockObservation.of(clock(minute = 45), t, t, zone).exceeds(60))
    }

    @Test
    fun largeDriftIsDescribedInHumanUnits() {
        val t = phoneMs(22, 30, 0)
        assertTrue(
            CuffClockObservation.of(clock(hour = 20), t, t, zone).detail.contains("2h 0min"),
        )
        assertTrue(
            CuffClockObservation.of(clock(minute = 40), t, t, zone).detail.contains("10min"),
        )
    }

    @Test
    fun jsonCarriesTheFieldsTheServerSchemaNeeds() {
        val t = phoneMs(22, 30, 0)
        val json = CuffClockObservation.of(clock(minute = 31), t, t, zone).toJson()
        listOf(
            "\"phone_read_at\"", "\"cuff_clock_at_read\"", "\"clock_offset_s\":60",
            "\"clock_offset_uncertainty_s\"", "\"clock_valid\":true", "\"clock_halted\":false",
        ).forEach { assertTrue("missing $it in $json", json.contains(it)) }
    }

    @Test
    fun jsonUsesNullNotZeroForAnUnknownOffset() {
        // 0 would be indistinguishable from a perfectly synced clock downstream.
        val t = phoneMs(22, 30, 0)
        val json = CuffClockObservation.of(null, t, t, zone).toJson()
        assertTrue(json.contains("\"clock_offset_s\":null"))
        assertTrue(json.contains("\"cuff_clock_at_read\":null"))
    }
}
