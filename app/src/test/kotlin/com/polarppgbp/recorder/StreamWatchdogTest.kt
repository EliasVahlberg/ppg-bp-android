package com.polarppgbp.recorder

import com.polarppgbp.rop.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #19. The trap these tests exist for: the failure looks healthy from every other angle,
 * so a watchdog that keys on anything except per-sensor counts would pass while the
 * recording produces an empty file.
 */
class StreamWatchdogTest {

    private val all = setOf(SensorType.PPG, SensorType.ACC, SensorType.GYRO)

    @Test
    fun saysNothingBeforeTheGracePeriod() {
        // A slow start must not be mistaken for the failure, or the watchdog would
        // restart every healthy recording.
        assertEquals(
            WatchdogVerdict.Waiting,
            StreamWatchdog.evaluate(emptyMap(), all, elapsedSinceStartMs = 2_000),
        )
    }

    @Test
    fun healthyWhenEveryExpectedSensorProduces() {
        val counts = mapOf(SensorType.PPG to 2_254L, SensorType.ACC to 5_482L,
                           SensorType.GYRO to 5_500L)
        assertEquals(
            WatchdogVerdict.Healthy,
            StreamWatchdog.evaluate(counts, all, elapsedSinceStartMs = 18_000),
        )
    }

    @Test
    fun theThirdSightingIsCaught() {
        // Exactly the observed state: connection up, heart rate live, all three PMD
        // streams at zero. Neither connection state nor HR appears here, deliberately.
        val counts = mapOf(SensorType.PPG to 0L, SensorType.ACC to 0L, SensorType.GYRO to 0L)
        val verdict = StreamWatchdog.evaluate(counts, all, elapsedSinceStartMs = 30_000)
        assertEquals(WatchdogVerdict.Restart(all), verdict)
    }

    @Test
    fun onlyTheSilentSensorIsRestarted() {
        // The first two sightings had ACC alone at zero. Restarting a working PPG stream
        // would throw away good data to fix a different sensor.
        val counts = mapOf(SensorType.PPG to 1_000L, SensorType.ACC to 0L,
                           SensorType.GYRO to 900L)
        assertEquals(
            WatchdogVerdict.Restart(setOf(SensorType.ACC)),
            StreamWatchdog.evaluate(counts, all, elapsedSinceStartMs = 12_000),
        )
    }

    @Test
    fun aSensorTheProfileDidNotRequestIsNeverSilent() {
        // The monitor profile can run PPG alone. An absent sensor is not a broken one.
        val counts = mapOf(SensorType.PPG to 500L)
        assertEquals(
            WatchdogVerdict.Healthy,
            StreamWatchdog.evaluate(counts, setOf(SensorType.PPG), elapsedSinceStartMs = 20_000),
        )
    }

    @Test
    fun waitsAgainAfterARestartBeforeJudging() {
        val counts = mapOf(SensorType.PPG to 0L)
        assertEquals(
            WatchdogVerdict.Waiting,
            StreamWatchdog.evaluate(
                counts, setOf(SensorType.PPG),
                elapsedSinceStartMs = 13_000,
                restartedAtElapsedMs = 10_000,
                countsAtRestart = mapOf(SensorType.PPG to 0L),
            ),
        )
    }

    @Test
    fun recoveryAfterARestartIsHealthy() {
        assertEquals(
            WatchdogVerdict.Healthy,
            StreamWatchdog.evaluate(
                counts = mapOf(SensorType.ACC to 4_000L),
                expected = setOf(SensorType.ACC),
                elapsedSinceStartMs = 25_000,
                restartedAtElapsedMs = 10_000,
                countsAtRestart = mapOf(SensorType.ACC to 0L),
            ),
        )
    }

    @Test
    fun progressIsMeasuredSinceTheRestartNotSinceZero() {
        // A stream that delivered a burst early and then died would otherwise look
        // healthy forever on its absolute count.
        val verdict = StreamWatchdog.evaluate(
            counts = mapOf(SensorType.ACC to 512L),
            expected = setOf(SensorType.ACC),
            elapsedSinceStartMs = 30_000,
            restartedAtElapsedMs = 10_000,
            countsAtRestart = mapOf(SensorType.ACC to 512L),
        )
        assertEquals(WatchdogVerdict.Failed(setOf(SensorType.ACC)), verdict)
    }

    @Test
    fun escalatesWhenARestartDidNotHelp() {
        val verdict = StreamWatchdog.evaluate(
            counts = mapOf(SensorType.PPG to 0L),
            expected = setOf(SensorType.PPG),
            elapsedSinceStartMs = 40_000,
            restartedAtElapsedMs = 10_000,
            countsAtRestart = mapOf(SensorType.PPG to 0L),
        )
        assertEquals(WatchdogVerdict.Failed(setOf(SensorType.PPG)), verdict)
    }

    @Test
    fun graceIsShorterThanAKnownHealthyStart() {
        // The recovered run produced thousands of samples within 18 s, so the grace
        // period must sit well below that or the watchdog fires too late to matter.
        assertTrue(StreamWatchdog.GRACE_MS < 18_000)
    }

    @Test
    fun messagesNameTheSensorAndSayWhatToDo() {
        val failed = StreamWatchdog.describe(setOf(SensorType.ACC), recovered = false)
        assertTrue(failed.contains("ACC"))
        assertTrue(failed.contains("restart"))
        assertTrue(StreamWatchdog.describe(setOf(SensorType.PPG), recovered = true)
            .contains("restarted"))
    }
}
