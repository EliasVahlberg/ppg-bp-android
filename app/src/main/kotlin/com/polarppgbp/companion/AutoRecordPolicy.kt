package com.polarppgbp.companion

/**
 * Whether a sensor-appeared callback should start a recording, kept as a pure function
 * so the policy is testable without a sensor, a Context, or a companion association.
 *
 * Background (#21). Collection currently depends on the user opening the app and
 * pressing record. The trigger chosen here is "the sensor became visible" rather than a
 * clock schedule, because a recording only produces data while the sensor is worn and
 * powered: an alarm at a fixed time fires just as readily when the sensor is on its
 * charger, which yields empty sessions and trains the user to ignore notifications.
 * Sensor presence is the signal that recording is actually possible.
 */
object AutoRecordPolicy {

    /**
     * Minimum gap between two automatic starts.
     *
     * BLE presence is not a clean edge. A sensor at the edge of range, or one that
     * briefly drops during a connection handover, can produce repeated appeared
     * callbacks. Without a floor here each one would start a fresh session, fragmenting
     * a single wearing period into many short bundles -- the analysis side already has
     * to filter 58 sub-minute dev sessions out of 82, so adding a mechanism that
     * manufactures more of them would be actively harmful.
     */
    const val MIN_GAP_MS = 5 * 60 * 1000L

    sealed interface Decision {
        data object Start : Decision
        data class Skip(val reason: String) : Decision
    }

    /**
     * @param enabled user setting; false means never act on presence.
     * @param alreadyRecording true when a session is in progress, in which case the
     *   callback is redundant -- reconnection is the repository's job, not ours.
     * @param lastAutoStartMs when the last automatic start happened, null if never.
     * @param nowMs current wall clock.
     */
    fun decide(
        enabled: Boolean,
        alreadyRecording: Boolean,
        lastAutoStartMs: Long?,
        nowMs: Long,
    ): Decision {
        // Deliberately a half-open range rather than "< MIN_GAP_MS": a clock that has
        // gone backwards (NTP correction, manual change) gives a negative elapsed time,
        // which would satisfy a naive "under the floor" test and wedge auto-record until
        // the clock caught up. Treat any negative delta as no usable history instead.
        val sinceLast = lastAutoStartMs?.let { nowMs - it }
        return when {
            !enabled -> Decision.Skip("auto-record off")
            alreadyRecording -> Decision.Skip("already recording")
            sinceLast != null && sinceLast in 0 until MIN_GAP_MS ->
                Decision.Skip("last auto start ${sinceLast / 1000}s ago, under the ${MIN_GAP_MS / 1000}s floor")
            else -> Decision.Start
        }
    }
}
