package com.polarppgbp.companion

import com.polarppgbp.companion.AutoRecordPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #21: the decision to start a recording unattended, isolated from Android. */
class AutoRecordPolicyTest {

    private val now = 1_785_000_000_000L

    @Test
    fun `starts when enabled, idle and no recent auto start`() {
        assertEquals(
            Decision.Start,
            AutoRecordPolicy.decide(enabled = true, alreadyRecording = false, lastAutoStartMs = null, nowMs = now),
        )
    }

    @Test
    fun `does nothing when the user has not enabled it`() {
        val d = AutoRecordPolicy.decide(enabled = false, alreadyRecording = false, lastAutoStartMs = null, nowMs = now)
        assertTrue(d is Decision.Skip)
        assertEquals("auto-record off", (d as Decision.Skip).reason)
    }

    @Test
    fun `does not interrupt or duplicate a recording in progress`() {
        val d = AutoRecordPolicy.decide(enabled = true, alreadyRecording = true, lastAutoStartMs = null, nowMs = now)
        assertTrue(d is Decision.Skip)
        assertEquals("already recording", (d as Decision.Skip).reason)
    }

    /**
     * The important one. A sensor at the edge of range produces repeated appeared
     * callbacks, and without a floor each would start a new session, fragmenting one
     * wearing period into many short bundles.
     */
    @Test
    fun `flapping presence does not produce a burst of short sessions`() {
        var last: Long? = null
        var started = 0
        // Ten appear callbacks over four minutes, as a marginal signal would give.
        for (i in 0 until 10) {
            val t = now + i * 24_000L
            if (AutoRecordPolicy.decide(true, alreadyRecording = false, lastAutoStartMs = last, nowMs = t) == Decision.Start) {
                started++
                last = t
            }
        }
        assertEquals("only the first appearance should start a session", 1, started)
    }

    @Test
    fun `a genuine second wearing period later in the day does start`() {
        val morning = now
        val afternoon = now + 6 * 60 * 60 * 1000L
        assertEquals(
            Decision.Start,
            AutoRecordPolicy.decide(true, alreadyRecording = false, lastAutoStartMs = morning, nowMs = afternoon),
        )
    }

    @Test
    fun `exactly at the floor is still suppressed, just past it is allowed`() {
        val atFloor = AutoRecordPolicy.decide(true, false, now, now + AutoRecordPolicy.MIN_GAP_MS - 1)
        assertTrue(atFloor is Decision.Skip)
        assertEquals(
            Decision.Start,
            AutoRecordPolicy.decide(true, false, now, now + AutoRecordPolicy.MIN_GAP_MS),
        )
    }

    /** An NTP correction or manual clock change must not wedge auto-record forever. */
    @Test
    fun `a clock that went backwards does not block starting`() {
        assertEquals(
            Decision.Start,
            AutoRecordPolicy.decide(true, alreadyRecording = false, lastAutoStartMs = now + 86_400_000L, nowMs = now),
        )
    }

    @Test
    fun `the skip reason names the wait so it can be shown to the user`() {
        val d = AutoRecordPolicy.decide(true, false, now, now + 60_000L)
        assertTrue(d is Decision.Skip)
        assertTrue((d as Decision.Skip).reason.contains("60s ago"))
    }
}
