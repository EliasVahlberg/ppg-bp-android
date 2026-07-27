/*
 * #19: a stream that reports started and delivers nothing.
 *
 * Seen three times. The signature is a session that looks entirely healthy -- BLE
 * connected, heart-rate notifications arriving, the SDK having accepted every start
 * request without error -- while one or more PMD streams produce zero samples for the
 * whole recording. On the third sighting the connection was up with hr=100 and
 * ppgSamples=0, accSamples=0, gyroSamples=0 for 30 seconds.
 *
 * The lesson that shapes this file: a live heart rate is not evidence a recording is
 * working. HR arrives over a different mechanism than PMD data, so connection state and
 * HR both look fine in exactly the failure we care about. The only trustworthy signal is
 * per-sensor sample counts.
 *
 * The decision is kept pure and separate from the BLE plumbing so the timing rules can be
 * tested without a device -- a bug in this logic would either restart healthy streams or,
 * worse, stay quiet through the failure it exists to catch.
 */

package com.polarppgbp.recorder

import com.polarppgbp.rop.SensorType

/** What the watchdog wants done about the counts it just saw. */
sealed interface WatchdogVerdict {

    /** Too early to judge; the grace period has not elapsed. */
    data object Waiting : WatchdogVerdict

    /** Every expected sensor has produced data. */
    data object Healthy : WatchdogVerdict

    /** These sensors are silent past the grace period. Restart them once. */
    data class Restart(val silent: Set<SensorType>) : WatchdogVerdict

    /**
     * Still silent after a restart. Recording is going to disk with a hole in it, and no
     * further automatic action will help -- this needs to reach the user.
     */
    data class Failed(val silent: Set<SensorType>) : WatchdogVerdict
}

object StreamWatchdog {

    /**
     * How long a stream may stay silent before it counts as broken.
     *
     * A healthy start is emphatic: the recovered run after the third #19 sighting
     * produced 2,254 PPG and 5,482 ACC samples in its first 18 seconds. Nothing arriving
     * after ten seconds is not a slow start, it is the failure. The value is still well
     * clear of connection and PMD-negotiation latency, which land in the low seconds.
     */
    const val GRACE_MS = 10_000L

    /** Second chance after a restart, measured from the restart rather than from start. */
    const val POST_RESTART_GRACE_MS = 10_000L

    /**
     * @param counts samples seen per sensor so far, absolute since stream start.
     * @param expected sensors the active profile asked for. A sensor the profile did not
     *   request is not silent, it is absent, and must never trigger a restart.
     * @param elapsedSinceStartMs time since the streams were started.
     * @param restartedAtElapsedMs when the restart happened, or null if none yet. Counts
     *   are not reset by a restart, so a sensor that produced data before the restart is
     *   judged on what has arrived since.
     * @param countsAtRestart counts captured at the moment of the restart.
     */
    fun evaluate(
        counts: Map<SensorType, Long>,
        expected: Set<SensorType>,
        elapsedSinceStartMs: Long,
        restartedAtElapsedMs: Long? = null,
        countsAtRestart: Map<SensorType, Long> = emptyMap(),
    ): WatchdogVerdict {
        if (expected.isEmpty()) return WatchdogVerdict.Healthy

        if (restartedAtElapsedMs == null) {
            if (elapsedSinceStartMs < GRACE_MS) return WatchdogVerdict.Waiting
            val silent = expected.filter { (counts[it] ?: 0L) <= 0L }.toSet()
            return if (silent.isEmpty()) {
                WatchdogVerdict.Healthy
            } else {
                WatchdogVerdict.Restart(silent)
            }
        }

        // Post-restart: only progress since the restart counts. A stream that delivered
        // nothing before and nothing after is the case worth escalating.
        val sinceRestart = elapsedSinceStartMs - restartedAtElapsedMs
        if (sinceRestart < POST_RESTART_GRACE_MS) return WatchdogVerdict.Waiting
        val stillSilent = expected.filter {
            (counts[it] ?: 0L) <= (countsAtRestart[it] ?: 0L)
        }.toSet()
        return if (stillSilent.isEmpty()) {
            WatchdogVerdict.Healthy
        } else {
            WatchdogVerdict.Failed(stillSilent)
        }
    }

    /** Wording for the UI and the notification. Names the sensors, since that is the fix. */
    fun describe(silent: Set<SensorType>, recovered: Boolean): String {
        val names = silent.sortedBy { it.name }.joinToString(", ") { it.name }
        return if (recovered) {
            "$names produced no data and was restarted."
        } else {
            "$names is producing no data. This recording will have no $names samples — " +
                "stop and restart the recording."
        }
    }
}
