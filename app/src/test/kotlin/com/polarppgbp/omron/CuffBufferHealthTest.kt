package com.polarppgbp.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #10: overwritten readings leave no trace on the device, so the gap has to be inferred.
 * Reproducing this on hardware would take three weeks, which is exactly why the
 * arithmetic is pinned here.
 */
class CuffBufferHealthTest {

    /** A reading [daysAgo] before 2026-07-26 12:00, at [hour]. */
    private fun reading(day: Int, hour: Int, minute: Int = 0, sys: Int = 120) =
        OmronProtocol.CuffReading(sys, 70, 65, 2026, 7, day, hour, minute, 0, false, false)

    /** [perDay] readings spread across each day's waking hours, so timestamps stay valid. */
    private fun series(days: IntRange, perDay: Int): List<OmronProtocol.CuffReading> =
        days.flatMap { d ->
            (0 until perDay).map { i ->
                val minutesFromSix = i * (720 / perDay)
                reading(d, 6 + minutesFromSix / 60, minutesFromSix % 60, sys = 100 + i)
            }
        }

    /** Exactly [capacity] readings, i.e. a full ring buffer. */
    private fun fullBuffer(fromDay: Int, perDay: Int, capacity: Int = 100) =
        series(fromDay until fromDay + (capacity / perDay) + 1, perDay).take(capacity)

    @Test
    fun emptyCuffReportsNothingAlarming() {
        val h = CuffBufferHealth.assess(emptyList(), null, null)
        assertFalse(h.gapDetected)
        assertNull(h.warning)
        assertEquals(0, h.slotsUsed)
    }

    @Test
    fun firstSyncCannotInferAGap() {
        // Nothing stored yet, so an old-looking oldest record proves nothing.
        val h = CuffBufferHealth.assess(series(1..10, 2), null, 20)
        assertFalse(h.gapDetected)
        assertNull(h.warning)
    }

    @Test
    fun overlapWithTheStoreMeansNothingWasLost() {
        val readings = series(10..20, 2)
        // Stored newest sits inside the device's span, so the sync is continuous.
        val h = CuffBufferHealth.assess(readings, "2026-07-15T08:00:00", 4)
        assertFalse(h.gapDetected)
        assertNull(h.warning)
    }

    @Test
    fun aGapIsDetectedAndNamedWhenTheOldestRecordIsNewerThanTheStore() {
        val readings = fullBuffer(15, 4)
        val h = CuffBufferHealth.assess(readings, "2026-07-01T08:00:00", 40)
        assertTrue(h.gapDetected)
        assertEquals("2026-07-01T08:00:00", h.gapFromIso)
        assertEquals("2026-07-15T06:00:00", h.gapToIso)
        assertNotNull(h.warning)
        assertTrue("must name the window", h.warning!!.contains("2026-07-15T06:00:00"))
        assertTrue("must say it is unrecoverable", h.warning!!.contains("for good"))
    }

    @Test
    fun gapWarningEstimatesHowManyReadingsWereLost() {
        // ~14 days missing at ~4/day, so the estimate should land near 56.
        val h = CuffBufferHealth.assess(fullBuffer(15, 4), "2026-07-01T06:00:00", null)
        val lost = Regex("roughly (\\d+) reading").find(h.warning!!)!!.groupValues[1].toInt()
        assertTrue("estimate $lost should be near 56", lost in 45..65)
    }

    @Test
    fun aPartlyFilledBufferIsNeverReportedAsOverflow() {
        // 30 of 100 slots used: nothing can have been overwritten, whatever the timestamps
        // say. A store newer than the device means something else (memory cleared, another
        // cuff), and calling that silent data loss would be wrong.
        val h = CuffBufferHealth.assess(series(20..24, 6), "2026-07-01T06:00:00", 5)
        assertFalse(h.gapDetected)
        assertNull(h.warning)
    }

    @Test
    fun cadenceAndHeadroomComeFromTheDeviceItself() {
        // 4/day across 10 days: 100 slots is ~25 days of buffer.
        val h = CuffBufferHealth.assess(series(10..20, 4), "2026-07-10T08:00:00", 8)
        assertNotNull(h.readingsPerDay)
        assertEquals(4.0, h.readingsPerDay!!, 0.6)
        assertEquals(25.0, h.daysOfHeadroom!!, 4.0)
        assertNull("25 days of headroom is not a warning", h.warning)
    }

    @Test
    fun lowHeadroomWarnsBeforeAnythingIsLost() {
        // 20 readings/day burns through 100 slots in 5 days.
        val h = CuffBufferHealth.assess(series(20..25, 20), "2026-07-20T06:00:00", 10)
        assertFalse("nothing lost yet", h.gapDetected)
        assertNotNull(h.warning)
        assertTrue(h.warning!!.contains("Sync at least"))
    }

    @Test
    fun cadenceIsNotGuessedFromTooShortASpan() {
        // Three readings in one morning is not a rate, and pretending it is would produce
        // an absurd headroom figure and a false warning.
        val h = CuffBufferHealth.assess(
            listOf(reading(26, 8), reading(26, 9), reading(26, 10)),
            "2026-07-26T07:00:00",
            3,
        )
        assertNull(h.readingsPerDay)
        assertNull(h.daysOfHeadroom)
        assertNull(h.warning)
    }

    @Test
    fun unreadCounterIsReportedButNeverRequired() {
        val readings = series(10..20, 2)
        val withCounter = CuffBufferHealth.assess(readings, "2026-07-15T08:00:00", 7)
        val without = CuffBufferHealth.assess(readings, "2026-07-15T08:00:00", null)
        assertTrue(withCounter.detail.contains("7 unread on device"))
        assertFalse(without.detail.contains("unread on device"))
        // Gap detection must not depend on the device's counter.
        assertEquals(withCounter.gapDetected, without.gapDetected)
    }

    @Test
    fun fullBufferIsReported() {
        val readings = series(1..25, 4) // 100 readings
        val h = CuffBufferHealth.assess(readings, "2026-07-02T08:00:00", 100)
        assertEquals(100, h.slotsUsed)
        assertTrue(h.full)
        assertTrue(h.detail.contains("100/100 slots used"))
    }
}
