package com.polarppgbp.recorder

/**
 * Calibration session delimiters, written into the recording's `notes.jsonl`.
 *
 * A calibration session is a protocol run (posture sequence with paired cuff
 * readings), which is not the same thing as a recording: a recording is whatever
 * happened between Start and Stop, and may contain setup, a protocol run, and
 * idle time at the end. Analysis needs to know which slice of a recording was
 * the protocol, and under what conditions, otherwise every session has to be
 * reconstructed from a paper log and a wall clock.
 *
 * Deliberately not a new table or endpoint. Notes are already carried by the
 * bundle and ingested into a `notes(session_id, ts, note)` table that has never
 * had a row written to it, so a marker needs a caller rather than new plumbing.
 * A marker also belongs to the recording it delimits -- a standalone timestamped
 * label would have to be interval-matched back onto recordings at analysis time,
 * which adds a join and two new failure modes (a label with no recording, a
 * recording with no label).
 *
 * The note text is JSON so it stays queryable (DuckDB can read JSON out of a
 * VARCHAR) without a schema migration.
 */
object CalibrationMarker {

    const val EVENT_START = "calibration_start"
    const val EVENT_STOP = "calibration_stop"

    /** Guards against a fat-fingered paste becoming a note the size of a session. */
    const val MAX_NAME_LENGTH = 120

    /**
     * Split a comma-separated tag entry into clean tags.
     *
     * Lower-cased and trimmed because these are used to group sessions later, and
     * `Standing`, `standing ` and `STANDING` grouping as three different things is
     * a silent analysis bug rather than a visible one. Duplicates are collapsed and
     * order is preserved so the entry still reads the way it was typed.
     */
    fun parseTags(raw: String?): List<String> =
        raw.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Trimmed name, or null when nothing usable was entered. */
    fun cleanName(raw: String?): String? =
        raw?.trim()?.take(MAX_NAME_LENGTH)?.takeIf { it.isNotEmpty() }

    /**
     * The note payload for a start or stop marker.
     *
     * The name and tags are repeated on the stop marker rather than only on start.
     * A recording that is interrupted (crash, battery, forced stop) may deliver a
     * start with no stop or the reverse, and a marker that cannot be interpreted on
     * its own is worth much less when that happens.
     */
    fun payload(
        event: String,
        name: String?,
        tags: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"event\":").append(jsonStr(event))
        cleanName(name)?.let { sb.append(",\"name\":").append(jsonStr(it)) }
        if (tags.isNotEmpty()) {
            sb.append(",\"tags\":[")
            tags.forEachIndexed { i, t ->
                if (i > 0) sb.append(",")
                sb.append(jsonStr(t))
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    fun startPayload(name: String?, rawTags: String?): String =
        payload(EVENT_START, name, parseTags(rawTags))

    fun stopPayload(name: String?, rawTags: String?): String =
        payload(EVENT_STOP, name, parseTags(rawTags))

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
