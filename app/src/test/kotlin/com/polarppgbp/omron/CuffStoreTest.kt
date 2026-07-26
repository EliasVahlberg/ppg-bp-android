package com.polarppgbp.omron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CuffStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reading(minute: Int, sys: Int = 120) =
        OmronProtocol.CuffReading(sys, 70, 72, 2026, 5, 31, 17, minute, 0, false, false)

    @Test
    fun ingestDedupesAcrossReads() {
        val store = CuffStore(tmp.newFolder("cuff"))
        val a = reading(1)
        val b = reading(2)

        val r1 = store.ingest(listOf(a, b), "AA:BB")
        assertEquals(2, r1.newCount)
        assertEquals(2, r1.total)

        // Re-read the rolling buffer: a,b already known; only c is new.
        val c = reading(3)
        val r2 = store.ingest(listOf(a, b, c), "AA:BB")
        assertEquals(1, r2.newCount)
        assertEquals(3, r2.total)

        // Same readings again -> nothing new.
        val r3 = store.ingest(listOf(a, b, c), "AA:BB")
        assertEquals(0, r3.newCount)
        assertEquals(3, r3.total)
        assertEquals(3, store.count())
    }

    @Test
    fun differentValuesSameMinuteAreDistinct() {
        val store = CuffStore(tmp.newFolder("cuff"))
        val r = store.ingest(listOf(reading(5, sys = 120), reading(5, sys = 130)), null)
        assertEquals(2, r.newCount)
    }

    // ---------------------------------------------------------------- #9

    private fun observation(
        offset: Long? = 0L,
        valid: Boolean = true,
        halted: Boolean = false,
    ) = CuffClockObservation(
        cuffIso = "2026-07-26T22:30:00",
        phoneIso = "2026-07-26T22:30:00",
        offsetSeconds = offset,
        uncertaintySeconds = 1,
        clockValid = valid,
        halted = halted,
        detail = "test",
    )

    @Test
    fun storedReadingsCarryPhoneReadTimeAndMeasuredOffset() {
        val dir = tmp.newFolder()
        val store = CuffStore(dir)
        store.ingest(listOf(reading(1)), "AA:BB", observation(offset = 42))
        val line = java.io.File(dir, "cuff_readings.jsonl").readLines().first()
        assertTrue(line.contains("\"phone_read_at\":\"2026-07-26T22:30:00\""))
        assertTrue(line.contains("\"clock_offset_s\":42"))
        assertTrue(line.contains("\"clock_valid\":true"))
    }

    @Test
    fun aClockCorrectionDoesNotReinsertPreviouslySyncedReadings() {
        // The acceptance criterion for #9. Identity is built from the raw cuff timestamp,
        // which the cuff never rewrites, so the same records re-read after the phone
        // measures a different offset must still dedup to zero new rows.
        val store = CuffStore(tmp.newFolder())
        val readings = listOf(reading(1), reading(2), reading(3))
        val first = store.ingest(readings, "AA:BB", observation(offset = 0))
        assertEquals(3, first.newCount)

        val afterCorrection = store.ingest(readings, "AA:BB", observation(offset = -3600))
        assertEquals("a new offset must not create new rows", 0, afterCorrection.newCount)
        assertEquals(3, afterCorrection.total)
    }

    @Test
    fun readingsWithASuspectTimestampAreQuarantinedNotDropped() {
        val store = CuffStore(tmp.newFolder())
        val good = reading(1)
        val bad = reading(2).copy(clockSuspect = true, slotIndex = 7)
        val res = store.ingest(listOf(good, bad), "AA:BB", observation())
        assertEquals("only the trustworthy reading enters the canonical store", 1, res.newCount)
        assertEquals(1, res.quarantinedCount)
        assertEquals(1, store.quarantinedCount())
    }

    @Test
    fun aHaltedClockQuarantinesEveryReadingInThatRead() {
        // While the RTC is halted, every reading gets the same timestamp, so identical
        // values would collide and the second would be silently dropped.
        val store = CuffStore(tmp.newFolder())
        val res = store.ingest(
            listOf(reading(1), reading(2)),
            "AA:BB",
            observation(offset = null, valid = false, halted = true),
        )
        assertEquals(0, res.newCount)
        assertEquals(2, res.quarantinedCount)
    }

    @Test
    fun quarantinedReadingsAreNotUploadedAsCanonical() {
        val store = CuffStore(tmp.newFolder())
        store.ingest(
            listOf(reading(1)),
            "AA:BB",
            observation(offset = null, valid = false, halted = true),
        )
        assertNull("nothing joinable to upload", store.uploadBody())
    }

    @Test
    fun resyncingTheSameHaltedRecordDoesNotDuplicateIt() {
        val store = CuffStore(tmp.newFolder())
        val halted = observation(offset = null, valid = false, halted = true)
        val readings = listOf(reading(1).copy(slotIndex = 3))
        assertEquals(1, store.ingest(readings, "AA:BB", halted).quarantinedCount)
        assertEquals(0, store.ingest(readings, "AA:BB", halted).quarantinedCount)
        assertEquals(1, store.quarantinedCount())
    }

    @Test
    fun everySyncAppendsOneLineToTheClockSeries() {
        // A series, not a single value: drift only means anything as a trend.
        val dir = tmp.newFolder()
        val store = CuffStore(dir)
        store.ingest(listOf(reading(1)), null, observation(offset = 1))
        store.ingest(listOf(reading(1)), null, observation(offset = 2))
        store.ingest(listOf(reading(1)), null, observation(offset = 3))
        val log = java.io.File(dir, "cuff_clock_log.jsonl").readLines()
        assertEquals(3, log.size)
        assertTrue(log[2].contains("\"clock_offset_s\":3"))
    }
}
