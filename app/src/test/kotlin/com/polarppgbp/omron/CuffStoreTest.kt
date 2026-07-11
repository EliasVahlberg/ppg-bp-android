package com.polarppgbp.omron

import org.junit.Assert.assertEquals
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
}
