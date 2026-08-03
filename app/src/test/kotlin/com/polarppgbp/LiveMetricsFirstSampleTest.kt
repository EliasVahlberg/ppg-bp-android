package com.polarppgbp

import com.polarppgbp.rop.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #19: a counter sitting at zero has two causes that the counts alone cannot separate.
 *
 * Connecting costs a few seconds of reconnect backoff (1s, 2s, 4s, 8s...) during which
 * every counter reads zero and then all of them jump together. A genuinely silent
 * stream leaves one sensor at zero while the others climb. Recording when each sensor
 * first delivered makes the two distinguishable after the fact, which is what the
 * 2026-07-26 report lacked -- it was read as a per-sensor fault, and re-testing on
 * 2026-08-03 showed the simultaneous jump of a late connect instead.
 */
class LiveMetricsFirstSampleTest {

    @Test
    fun `first sample time is recorded per sensor`() {
        val m = LiveMetrics()
            .withSamples(SensorType.PPG, 10, elapsedMs = 4300)
            .withSamples(SensorType.ACC, 24, elapsedMs = 4350)
        assertEquals(4300L, m.firstSampleElapsedMs[SensorType.PPG])
        assertEquals(4350L, m.firstSampleElapsedMs[SensorType.ACC])
    }

    @Test
    fun `later batches do not overwrite the first arrival`() {
        val m = LiveMetrics()
            .withSamples(SensorType.PPG, 10, elapsedMs = 4300)
            .withSamples(SensorType.PPG, 10, elapsedMs = 9000)
            .withSamples(SensorType.PPG, 10, elapsedMs = 14000)
        assertEquals(4300L, m.firstSampleElapsedMs[SensorType.PPG])
        assertEquals(30L, m.ppgSamples)
    }

    @Test
    fun `a sensor that never delivers has no entry`() {
        val m = LiveMetrics()
            .withSamples(SensorType.PPG, 10, elapsedMs = 100)
            .withSamples(SensorType.GYRO, 10, elapsedMs = 120)
        assertNull(m.firstSampleElapsedMs[SensorType.ACC])
        assertEquals(0L, m.accSamples)
    }

    @Test
    fun `counts accumulate independently per sensor`() {
        val m = LiveMetrics()
            .withSamples(SensorType.PPG, 176, elapsedMs = 1000)
            .withSamples(SensorType.ACC, 416, elapsedMs = 1000)
            .withSamples(SensorType.GYRO, 416, elapsedMs = 1000)
            .withSamples(SensorType.PPG, 176, elapsedMs = 2000)
        assertEquals(352L, m.ppgSamples)
        assertEquals(416L, m.accSamples)
        assertEquals(416L, m.gyroSamples)
    }

    /** A late connect: every sensor starts within a few hundred ms of the others. */
    @Test
    fun `a simultaneous start is visible as a tight cluster of first samples`() {
        val m = LiveMetrics()
            .withSamples(SensorType.PPG, 1, elapsedMs = 13010)
            .withSamples(SensorType.ACC, 1, elapsedMs = 13120)
            .withSamples(SensorType.GYRO, 1, elapsedMs = 13240)
        val times = m.firstSampleElapsedMs.values
        assertEquals(3, times.size)
        assertTrue("spread should be small for a late connect", times.max() - times.min() < 1000)
        assertTrue("all late, so the connect was slow", times.min() > 10_000)
    }

    /** The real #19 shape: PPG healthy from the start, ACC never arrives. */
    @Test
    fun `a genuinely silent sensor is distinguishable from a late connect`() {
        val m = LiveMetrics()
            .withSamples(SensorType.PPG, 900, elapsedMs = 300)
            .withSamples(SensorType.GYRO, 2000, elapsedMs = 320)
        assertTrue(m.firstSampleElapsedMs.containsKey(SensorType.PPG))
        assertNull(m.firstSampleElapsedMs[SensorType.ACC])
        assertTrue("others started promptly", m.firstSampleElapsedMs.values.max() < 1000)
    }

    @Test
    fun `battery and warning fields are untouched by sample bookkeeping`() {
        val m = LiveMetrics(batteryPercent = 94, streamWarning = "x")
            .withSamples(SensorType.PPG, 5, elapsedMs = 10)
        assertEquals(94, m.batteryPercent)
        assertEquals("x", m.streamWarning)
    }
}
