/*
 * Local dedup store for cuff readings. The Evolv exposes a rolling buffer of
 * the last 100 measurements; each read returns all of them, so we keep a
 * persistent set of what we've already seen and only add genuinely new
 * readings. Identity = taken-at timestamp + sys/dia/pulse (the cuff never
 * records two measurements in the same second).
 *
 * Append-only JSONL at <dir>/cuff_readings.jsonl, ready to upload as-is.
 * Pure JVM (manual JSON) so it is unit-testable without Android.
 */

package com.polarppgbp.omron

import java.io.File

class CuffStore(private val dir: File) {

    private val file = File(dir, "cuff_readings.jsonl")

    /**
     * Readings whose timestamp cannot be trusted (#9). Kept out of the canonical store
     * because identity there is timestamp-derived: while the RTC is halted every reading
     * gets the *same* timestamp, so two readings with equal values would collide and the
     * second would be silently dropped. They are preserved here instead of discarded --
     * the measurement itself is still real, it just cannot be used as a calibration join.
     */
    private val quarantineFile = File(dir, "cuff_readings_quarantine.jsonl")

    /**
     * Append-only clock-offset series, one line per sync (#9). A series, not a single
     * value: drift is only meaningful as a trend, and a jump between consecutive entries
     * is a clock event, so consumers must never interpolate across one.
     */
    private val clockLogFile = File(dir, "cuff_clock_log.jsonl")

    private val idRegex = Regex("\"id\":\"([^\"]*)\"")
    private val tsRegex = Regex("\"ts\":\"([^\"]*)\"")

    data class Result(
        val newCount: Int,
        val total: Int,
        val path: String,
        val quarantinedCount: Int = 0,
    )

    /**
     * Add only readings not already stored. Returns counts.
     *
     * [clock] is the clock comparison from the same read. When supplied, each new reading
     * records the phone's read time and the measured cuff offset, so a timestamp can be
     * corrected later at analysis time. The raw cuff timestamp is never rewritten: the
     * dedup identity is built from it, and every sync re-reads the whole ring buffer, so
     * correcting it in place would re-insert all 100 readings as new.
     */
    fun ingest(
        readings: List<OmronProtocol.CuffReading>,
        deviceAddress: String?,
        clock: CuffClockObservation? = null,
    ): Result {
        dir.mkdirs()
        if (clock != null) clockLogFile.appendText(clock.toJson() + "\n")

        val ids = existingIds().toMutableSet()
        val quarantinedIds = existingIds(quarantineFile).toMutableSet()
        val sb = StringBuilder()
        val qb = StringBuilder()
        var newCount = 0
        var quarantined = 0

        // A halted cuff clock makes every timestamp in this read untrustworthy, not just
        // the ones carrying the sentinel.
        val clockUntrustworthy = clock != null && !clock.clockValid

        for (r in readings) {
            if (r.clockSuspect || clockUntrustworthy) {
                val qid = quarantineId(r)
                if (quarantinedIds.add(qid)) {
                    quarantined++
                    qb.append(toJson(qid, r, deviceAddress, clock, suspect = true)).append('\n')
                }
                continue
            }
            val id = readingId(r)
            if (ids.add(id)) {
                newCount++
                sb.append(toJson(id, r, deviceAddress, clock)).append('\n')
            }
        }
        if (sb.isNotEmpty()) file.appendText(sb.toString())
        if (qb.isNotEmpty()) quarantineFile.appendText(qb.toString())
        return Result(newCount, ids.size, file.absolutePath, quarantined)
    }

    fun quarantinedCount(): Int = existingIds(quarantineFile).size

    fun existingIds(): Set<String> = existingIds(file)

    private fun existingIds(f: File): Set<String> =
        if (f.exists()) {
            f.readLines().mapNotNull { line -> idRegex.find(line)?.groupValues?.get(1) }.toSet()
        } else {
            emptySet()
        }

    fun count(): Int = existingIds().size

    /**
     * Newest reading timestamp already stored, or null when the store is empty.
     *
     * Must be sampled *before* ingesting a fresh read: it is the reference point for
     * detecting that the cuff overwrote readings between syncs (#10).
     */
    fun newestStoredIso(): String? {
        if (!file.exists()) return null
        return file.readLines()
            .mapNotNull { tsRegex.find(it)?.groupValues?.get(1) }
            .maxOrNull()
    }

    /**
     * The canonical store as a request body for POST /api/v1/cuff. Quarantined readings
     * are deliberately excluded: the server keys on reading id, and shipping rows whose
     * timestamp is known-wrong would put unusable joins into the canonical dataset. They
     * stay on the phone until ppg-bp-server#2 carries clock_valid.
     *
     * Body shape: 
     * {"readings":[ {...}, ... ]}. Each stored line is already a JSON object.
     * Returns null when there is nothing to upload. Upload is idempotent
     * (server dedups by reading id), so sending the full store is fine.
     */
    fun uploadBody(): String? {
        if (!file.exists()) return null
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.joinToString(prefix = "{\"readings\":[", separator = ",", postfix = "]}")
    }

    private fun readingId(r: OmronProtocol.CuffReading): String =
        "${r.takenAtIso()}|${r.sysMmHg}|${r.diaMmHg}|${r.pulseBpm}"

    /**
     * Identity for a reading with an untrustworthy timestamp: slot plus values plus the
     * raw (bogus) timestamp, so re-syncing the same halted record does not duplicate it.
     * Slots are reused on wrap, so two identical readings in the same slot across a wrap
     * could still collide -- accepted, since this bucket exists to be looked at by hand.
     */
    private fun quarantineId(r: OmronProtocol.CuffReading): String =
        "slot${r.slotIndex}|${r.takenAtIso()}|${r.sysMmHg}|${r.diaMmHg}|${r.pulseBpm}"

    private fun toJson(
        id: String,
        r: OmronProtocol.CuffReading,
        device: String?,
        clock: CuffClockObservation? = null,
        suspect: Boolean = false,
    ): String =
        buildString {
            append("{\"id\":\"").append(id).append("\",")
            append("\"ts\":\"").append(r.takenAtIso()).append("\",")
            append("\"sys\":").append(r.sysMmHg).append(',')
            append("\"dia\":").append(r.diaMmHg).append(',')
            append("\"pulse\":").append(r.pulseBpm).append(',')
            append("\"ihb\":").append(r.irregularHeartbeat).append(',')
            append("\"mov\":").append(r.bodyMovement)
            if (device != null) append(",\"device\":\"").append(device).append("\"")
            if (clock != null) {
                append(",\"phone_read_at\":\"").append(clock.phoneIso).append("\"")
                append(",\"clock_offset_s\":").append(clock.offsetSeconds ?: "null")
                append(",\"clock_offset_uncertainty_s\":").append(clock.uncertaintySeconds)
                append(",\"clock_valid\":").append(clock.clockValid)
            }
            if (suspect) {
                append(",\"clock_suspect\":true")
                append(",\"slot\":").append(r.slotIndex)
            }
            append('}')
        }
}
