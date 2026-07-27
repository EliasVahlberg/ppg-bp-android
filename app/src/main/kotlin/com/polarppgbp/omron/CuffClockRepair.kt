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

/**
 * Why a clock write is being offered. The two cases need different words in front of a
 * user and imply different things about the device, so they are not collapsed into a
 * boolean.
 */
enum class RepairReason {
    /** Clock is running and close enough to the phone. Nothing to do. */
    NONE,

    /** The halted sentinel is present: the RTC has stopped. The device is broken. */
    HALTED,

    /**
     * The clock runs but is wrong by more than [CuffClockRepair.GROSS_OFFSET_S] -- the
     * state a cuff arrives in from the factory, or after its cells are replaced and the
     * RTC restarts from an arbitrary value.
     */
    GROSSLY_WRONG,
}

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
     * Above this, the cuff's clock is not drifting -- it was never set. An hour is the
     * dividing line because this project cares about time of day (morning versus
     * afternoon readings differ systematically), so an error of an hour or more puts a
     * reading in the wrong part of the day, whereas seconds or minutes of genuine drift
     * are a measurement problem that #9 already handles by recording the offset.
     */
    const val GROSS_OFFSET_S = 3600L

    /**
     * Why a write is being offered, if at all. Never speculative, never on a schedule.
     *
     * Ordinary drift is deliberately excluded: the offset is measured on every read and
     * the correction is applied at analysis time, so writing to fix a few minutes would
     * be an EEPROM write that buys nothing. What is included is a clock that was never
     * set -- a factory-fresh cuff, or one whose RTC restarted after a battery change.
     * Correction would still work arithmetically there, but a cuff dated 2020 breaks the
     * ring-buffer reasoning in #10, misleads anyone reading the device, and leaves the
     * join key one unnoticed service visit away from jumping.
     *
     * An undecodable clock yields NONE: if the reading cannot be trusted, neither can a
     * decision to overwrite it.
     */
    fun reason(observation: CuffClockObservation): RepairReason = when {
        observation.halted -> RepairReason.HALTED
        !observation.clockValid || observation.offsetSeconds == null -> RepairReason.NONE
        kotlin.math.abs(observation.offsetSeconds) >= GROSS_OFFSET_S -> RepairReason.GROSSLY_WRONG
        else -> RepairReason.NONE
    }

    fun needed(observation: CuffClockObservation): Boolean =
        reason(observation) != RepairReason.NONE

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
