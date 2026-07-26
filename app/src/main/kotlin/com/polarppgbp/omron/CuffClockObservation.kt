/*
 * #9: make the cuff clock measurable.
 *
 * The cuff record timestamp is the only join key between an Omron reading and a PPG
 * window, and the cuff clock is neither set nor checked by anything. Drift silently
 * mispairs calibration data: the dataset still looks fine, the model just gets worse.
 *
 * This is the read-only half. Every cuff read records what the cuff thought the time
 * was and what the phone thought at the same moment, so drift becomes a measured
 * series rather than an assumption, and old readings stay retroactively correctable.
 *
 * Pure JVM so the arithmetic -- which is the part that can be wrong in a way nobody
 * notices -- is unit-tested rather than inferred from device logs.
 */

package com.polarppgbp.omron

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One clock comparison, taken during a single cuff read.
 *
 * [offsetSeconds] is `cuff - phone`: positive means the cuff runs ahead. It is a
 * single sample in a series, one per sync. Consumers must treat a large jump between
 * consecutive observations as a clock event and never interpolate across one.
 */
data class CuffClockObservation(
    /** Cuff-reported local time, as read. Never rewritten. */
    val cuffIso: String?,
    /** Phone wall clock at the midpoint of the clock read. */
    val phoneIso: String,
    /** cuff - phone, in seconds. Null when the cuff clock could not be trusted. */
    val offsetSeconds: Long?,
    /**
     * Half the duration of the read, in seconds. The offset cannot be resolved finer
     * than this: BLE latency lands directly in the measurement, which is exactly how
     * a ~8 s error was baked into the first clock write attempt.
     */
    val uncertaintySeconds: Long,
    /** False when the checksum failed, a field was out of range, or the RTC is halted. */
    val clockValid: Boolean,
    /** True when the seconds field carries the halted-RTC sentinel (raw > 59). */
    val halted: Boolean,
    /** Plain-language summary, suitable for a log line or status text. */
    val detail: String,
) {
    /**
     * Drift worth acting on. Deliberately not a "should I write the clock" decision --
     * that guard lives with the write path (#18) -- just a reporting threshold.
     */
    fun exceeds(thresholdSeconds: Long): Boolean =
        offsetSeconds?.let { kotlin.math.abs(it) > thresholdSeconds } ?: true

    fun toJson(): String = buildString {
        append("{\"phone_read_at\":\"").append(phoneIso).append("\",")
        append("\"cuff_clock_at_read\":").append(cuffIso?.let { "\"$it\"" } ?: "null").append(',')
        append("\"clock_offset_s\":").append(offsetSeconds ?: "null").append(',')
        append("\"clock_offset_uncertainty_s\":").append(uncertaintySeconds).append(',')
        append("\"clock_valid\":").append(clockValid).append(',')
        append("\"clock_halted\":").append(halted)
        append('}')
    }

    companion object {
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** Report anything beyond this in the status line rather than only the log. */
        const val NOTABLE_DRIFT_SECONDS = 60L

        /**
         * Build an observation from a parsed cuff clock and the phone clock sampled
         * either side of the read.
         *
         * The phone timestamps must bracket the actual BLE read, not the surrounding
         * work: the midpoint is what the offset is computed against, and the spread
         * becomes the stated uncertainty.
         *
         * @param clock null when the block could not be read at all.
         */
        fun of(
            clock: OmronProtocol.CuffClock?,
            phoneBeforeEpochMs: Long,
            phoneAfterEpochMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): CuffClockObservation {
            val midMs = (phoneBeforeEpochMs + phoneAfterEpochMs) / 2
            val phoneLocal = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(midMs), zone)
            val phoneIso = phoneLocal.format(ISO)
            val uncertainty = ((phoneAfterEpochMs - phoneBeforeEpochMs) / 2 + 999) / 1000

            if (clock == null) {
                return CuffClockObservation(
                    cuffIso = null,
                    phoneIso = phoneIso,
                    offsetSeconds = null,
                    uncertaintySeconds = uncertainty,
                    clockValid = false,
                    halted = false,
                    detail = "Cuff clock could not be read; drift unknown for this sync.",
                )
            }

            // The cuff stores naive local time, so it is interpreted in the phone's zone.
            // This is an assumption, not a fact from the device: the cuff has no zone
            // concept, and a reading taken in another zone will be off by that offset.
            val cuffEpoch = runCatching {
                LocalDateTime.of(
                    clock.year, clock.month, clock.day, clock.hour, clock.minute, clock.second,
                ).atZone(zone).toEpochSecond()
            }.getOrNull()

            if (clock.halted) {
                return CuffClockObservation(
                    cuffIso = clock.iso(),
                    phoneIso = phoneIso,
                    // Deliberately null: the cuff clock stopped, so the difference is
                    // elapsed downtime, not drift, and averaging it into a drift series
                    // would corrupt the series.
                    offsetSeconds = null,
                    uncertaintySeconds = uncertainty,
                    clockValid = false,
                    halted = true,
                    detail = "Cuff RTC is halted (frozen at ${clock.iso()}). Readings taken " +
                        "since then all carry that timestamp and cannot be used as a join key.",
                )
            }

            if (!clock.valid || cuffEpoch == null) {
                return CuffClockObservation(
                    cuffIso = clock.iso(),
                    phoneIso = phoneIso,
                    offsetSeconds = null,
                    uncertaintySeconds = uncertainty,
                    clockValid = false,
                    halted = false,
                    detail = "Cuff clock did not decode cleanly (checksum " +
                        "${if (clock.checksumOk) "ok" else "bad"}, raw ${clock.iso()}).",
                )
            }

            val phoneEpoch = phoneLocal.atZone(zone).toEpochSecond()
            val offset = cuffEpoch - phoneEpoch
            val magnitude = kotlin.math.abs(offset)
            val detail = when {
                magnitude <= 2 -> "Cuff clock matches the phone (${offset}s)."
                magnitude < NOTABLE_DRIFT_SECONDS ->
                    "Cuff clock is ${describe(offset)} (±${uncertainty}s)."
                else ->
                    "Cuff clock is ${describe(offset)} (±${uncertainty}s) — enough to mispair " +
                        "a reading with the wrong PPG window."
            }
            return CuffClockObservation(
                cuffIso = clock.iso(),
                phoneIso = phoneIso,
                offsetSeconds = offset,
                uncertaintySeconds = uncertainty,
                clockValid = true,
                halted = false,
                detail = detail,
            )
        }

        private fun describe(offsetSeconds: Long): String {
            val magnitude = kotlin.math.abs(offsetSeconds)
            val direction = if (offsetSeconds > 0) "ahead of" else "behind"
            val amount = when {
                magnitude < 120 -> "${magnitude}s"
                magnitude < 7200 -> "${magnitude / 60}min"
                else -> "${magnitude / 3600}h ${(magnitude % 3600) / 60}min"
            }
            return "$amount $direction the phone"
        }
    }
}
