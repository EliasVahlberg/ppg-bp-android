/*
 * #18: recovering a halted cuff RTC.
 *
 * The Evolv has no clock on its display and no button path to set the time, so once
 * its RTC halts (battery change, cells drained) the user cannot fix it at all. A
 * settings-region write is the only recovery route, and it is the only write this
 * project performs.
 *
 * The classification lives here, apart from the BLE work, because "did the repair
 * work" is a question with a subtle wrong answer: a write that sets the value without
 * starting the clock looks identical to success at the moment of writing.
 */

package com.polarppgbp.omron

/** Outcome of a clock repair attempt, in the order the stages are attempted. */
enum class RepairOutcome {
    /** Clock was not halted, so nothing was written. */
    NOT_NEEDED,

    /** Written, and a later read shows the clock keeping pace with the phone. */
    WRITTEN_AND_ADVANCING,

    /**
     * Written, but a later read shows the clock frozen at (or near) the written value.
     * The device accepted the bytes without starting the RTC -- the failure mode that
     * would otherwise be reported as success.
     */
    WRITTEN_NOT_ADVANCING,

    /** Written, but the verification read could not be completed. */
    WRITTEN_UNVERIFIED,

    /** The write itself failed. The cuff is no worse than before. */
    FAILED,
}

data class RepairReport(
    val outcome: RepairOutcome,
    /** What was written, ISO local time. */
    val wroteIso: String? = null,
    /** What the verification read saw. */
    val verifiedIso: String? = null,
    /** Cuff-vs-phone offset at verification, when measurable. */
    val verifiedOffsetSeconds: Long? = null,
    val detail: String,
) {
    val succeeded: Boolean get() = outcome == RepairOutcome.WRITTEN_AND_ADVANCING
}

object CuffClockRepair {

    /**
     * How long to wait between writing and verifying. Must exceed the tolerance below,
     * otherwise a frozen clock and a running clock are indistinguishable.
     */
    const val SETTLE_MS = 20_000L

    /** A running clock should track the phone this closely after a fresh write. */
    const val ADVANCING_TOLERANCE_S = 5L

    /**
     * Repair is offered only when the halted sentinel is present -- never
     * speculatively, never on a schedule, and never for ordinary drift. Drift is a
     * measurement problem (#9); a halted clock is a broken device.
     */
    fun needed(observation: CuffClockObservation): Boolean = observation.halted

    /**
     * Classify the verification read taken [SETTLE_MS] after the write.
     *
     * A single read is enough to prove advancement, because a clock frozen at the
     * written value would by now be behind the phone by the elapsed settle time. So
     * "still agrees with the phone" is only possible if the RTC is running.
     */
    fun classify(
        wroteIso: String?,
        verification: CuffClockObservation?,
        elapsedMs: Long,
        writeError: String? = null,
    ): RepairReport {
        if (writeError != null) {
            return RepairReport(
                outcome = RepairOutcome.FAILED,
                detail = "Clock write failed: $writeError. The cuff is unchanged.",
            )
        }
        if (verification == null) {
            return RepairReport(
                outcome = RepairOutcome.WRITTEN_UNVERIFIED,
                wroteIso = wroteIso,
                detail = "Wrote $wroteIso but could not reconnect to verify. Press the cuff's " +
                    "transfer button and read again to confirm the clock is running.",
            )
        }
        if (verification.halted) {
            return RepairReport(
                outcome = RepairOutcome.WRITTEN_NOT_ADVANCING,
                wroteIso = wroteIso,
                verifiedIso = verification.cuffIso,
                detail = "Wrote $wroteIso but the cuff still reports a halted clock. The RTC is " +
                    "not running; the battery may need replacing before the clock will hold.",
            )
        }
        if (!verification.clockValid || verification.offsetSeconds == null) {
            return RepairReport(
                outcome = RepairOutcome.WRITTEN_UNVERIFIED,
                wroteIso = wroteIso,
                verifiedIso = verification.cuffIso,
                detail = "Wrote $wroteIso but the verification read did not decode cleanly " +
                    "(${verification.detail}).",
            )
        }

        val offset = verification.offsetSeconds
        val elapsedS = elapsedMs / 1000
        return if (kotlin.math.abs(offset) <= ADVANCING_TOLERANCE_S) {
            RepairReport(
                outcome = RepairOutcome.WRITTEN_AND_ADVANCING,
                wroteIso = wroteIso,
                verifiedIso = verification.cuffIso,
                verifiedOffsetSeconds = offset,
                detail = "Cuff clock set and running: ${verification.cuffIso}, ${offset}s from the " +
                    "phone after ${elapsedS}s. A frozen clock would be ${elapsedS}s behind by now.",
            )
        } else if (offset < 0 && kotlin.math.abs(offset) >= elapsedS - ADVANCING_TOLERANCE_S) {
            // Behind by roughly the settle time: the value took, but the RTC is not ticking.
            RepairReport(
                outcome = RepairOutcome.WRITTEN_NOT_ADVANCING,
                wroteIso = wroteIso,
                verifiedIso = verification.cuffIso,
                verifiedOffsetSeconds = offset,
                detail = "Wrote $wroteIso and the value took, but the clock is ${-offset}s behind " +
                    "after ${elapsedS}s — it is holding the written value without advancing.",
            )
        } else {
            RepairReport(
                outcome = RepairOutcome.WRITTEN_UNVERIFIED,
                wroteIso = wroteIso,
                verifiedIso = verification.cuffIso,
                verifiedOffsetSeconds = offset,
                detail = "Wrote $wroteIso; the cuff now reads ${verification.cuffIso}, ${offset}s " +
                    "from the phone. Not the expected result — read again to check.",
            )
        }
    }
}
