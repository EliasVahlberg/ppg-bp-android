/*
 * #3: the session-file rotation period, which was a hardcoded 15 in
 * PolarRepository.startSession.
 *
 * Pure and Android-free so the bounds are unit-tested. The bounds matter more than they
 * look: rotation controls how much data a single truncated file can cost if the process
 * dies mid-write, and at 176 Hz PPG plus 416 Hz ACC and GYRO a long period is a lot of
 * samples in one file.
 */

package com.polarppgbp.settings

object RotationPeriod {

    const val DEFAULT_MINUTES = 15

    /**
     * One minute is the floor. Anything shorter produces more files than samples worth
     * keeping and multiplies sync overhead per session.
     */
    const val MIN_MINUTES = 1

    /**
     * Two hours is the ceiling. A 176 Hz PPG plus two 416 Hz streams for two hours is
     * roughly 7 million samples in a single file, and the point of rotating at all is to
     * bound what one truncated file costs.
     */
    const val MAX_MINUTES = 120

    /** Suggested values, offered in the UI so the common cases need no typing. */
    val PRESETS = listOf(5, 15, 30, 60)

    /** Returns the value if usable, or null. Null is a rejection, not a fallback. */
    fun normalise(minutes: Int?): Int? =
        minutes?.takeIf { it in MIN_MINUTES..MAX_MINUTES }

    /** Parse user input. Blank, non-numeric and out-of-range all return null. */
    fun parse(raw: String?): Int? = normalise(raw?.trim()?.toIntOrNull())

    fun describe(minutes: Int): String = when {
        minutes < 60 -> "$minutes min per file"
        minutes % 60 == 0 -> "${minutes / 60} h per file"
        else -> "${minutes / 60} h ${minutes % 60} min per file"
    }

    /** Message for a rejected value, naming the bounds rather than just failing. */
    fun errorFor(raw: String?): String = when {
        raw.isNullOrBlank() -> "Enter a rotation period in minutes."
        raw.trim().toIntOrNull() == null -> "Rotation period must be a whole number of minutes."
        else -> "Rotation period must be between $MIN_MINUTES and $MAX_MINUTES minutes."
    }
}
