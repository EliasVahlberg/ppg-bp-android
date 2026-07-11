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
    private val idRegex = Regex("\"id\":\"([^\"]*)\"")

    data class Result(val newCount: Int, val total: Int, val path: String)

    /** Add only readings not already stored. Returns counts. */
    fun ingest(readings: List<OmronProtocol.CuffReading>, deviceAddress: String?): Result {
        dir.mkdirs()
        val ids = existingIds().toMutableSet()
        val sb = StringBuilder()
        var newCount = 0
        for (r in readings) {
            val id = readingId(r)
            if (ids.add(id)) {
                newCount++
                sb.append(toJson(id, r, deviceAddress)).append('\n')
            }
        }
        if (sb.isNotEmpty()) file.appendText(sb.toString())
        return Result(newCount, ids.size, file.absolutePath)
    }

    fun existingIds(): Set<String> =
        if (file.exists()) {
            file.readLines().mapNotNull { line -> idRegex.find(line)?.groupValues?.get(1) }.toSet()
        } else {
            emptySet()
        }

    fun count(): Int = existingIds().size

    /**
     * The whole local store as a request body for POST /api/v1/cuff:
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

    private fun toJson(id: String, r: OmronProtocol.CuffReading, device: String?): String =
        buildString {
            append("{\"id\":\"").append(id).append("\",")
            append("\"ts\":\"").append(r.takenAtIso()).append("\",")
            append("\"sys\":").append(r.sysMmHg).append(',')
            append("\"dia\":").append(r.diaMmHg).append(',')
            append("\"pulse\":").append(r.pulseBpm).append(',')
            append("\"ihb\":").append(r.irregularHeartbeat).append(',')
            append("\"mov\":").append(r.bodyMovement)
            if (device != null) append(",\"device\":\"").append(device).append("\"")
            append('}')
        }
}
