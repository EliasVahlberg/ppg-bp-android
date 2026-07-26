/*
 * #10: the cuff keeps only its last 100 measurements and overwrites silently.
 *
 * At the observed ~2 readings/day that is ~47 days of headroom; at the 4-6/day the
 * measurement protocol asks for it is ~17-25 days. Sync less often than that and
 * readings are gone with no error anywhere -- the overwritten data simply is not there
 * to notice, which is why this needs an explicit check rather than an error path.
 *
 * Pure JVM. The gap arithmetic is the part that has to be right, so it is tested
 * directly rather than inferred from a device that would take three weeks to reproduce
 * the failure.
 */

package com.polarppgbp.omron

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BufferHealth(
    val slotsUsed: Int,
    val capacity: Int,
    /** The cuff's own unread counter, when it could be read. */
    val unreadOnDevice: Int?,
    val oldestOnDeviceIso: String?,
    val newestOnDeviceIso: String?,
    /** True when readings existed between the last sync and the oldest record still present. */
    val gapDetected: Boolean,
    val gapFromIso: String? = null,
    val gapToIso: String? = null,
    /** Observed cadence from the records on the device, when there are enough to tell. */
    val readingsPerDay: Double? = null,
    /** Days until the oldest unsynced reading would be overwritten, at that cadence. */
    val daysOfHeadroom: Double? = null,
    /** Non-null when the user needs to be told something. */
    val warning: String? = null,
    val detail: String,
) {
    val full: Boolean get() = slotsUsed >= capacity
}

object CuffBufferHealth {

    /** Warn below this much headroom, so there is time to act before data is lost. */
    const val LOW_HEADROOM_DAYS = 7.0

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun parse(iso: String?): LocalDateTime? =
        iso?.let { runCatching { LocalDateTime.parse(it, ISO) }.getOrNull() }

    /**
     * Assess the ring buffer after a read.
     *
     * @param readings everything the device returned this read.
     * @param newestStoredIso the newest reading already in the local store, from *before*
     *   this read was ingested. Null on a first sync, when no gap can be inferred.
     * @param unreadOnDevice the cuff's own counter, or null when unreadable.
     */
    fun assess(
        readings: List<OmronProtocol.CuffReading>,
        newestStoredIso: String?,
        unreadOnDevice: Int?,
        capacity: Int = OmronProtocol.USER_RECORDS_COUNT,
    ): BufferHealth {
        val timestamps = readings.mapNotNull { parse(it.takenAtIso()) }.sorted()
        val oldest = timestamps.firstOrNull()
        val newest = timestamps.lastOrNull()
        val slotsUsed = readings.size

        if (oldest == null || newest == null) {
            return BufferHealth(
                slotsUsed = slotsUsed,
                capacity = capacity,
                unreadOnDevice = unreadOnDevice,
                oldestOnDeviceIso = null,
                newestOnDeviceIso = null,
                gapDetected = false,
                detail = "No readings on the cuff.",
            )
        }

        // Cadence from the device's own span. Needs at least two readings and a non-zero
        // span, otherwise it is not a rate.
        val spanDays = Duration.between(oldest, newest).toMinutes() / 1440.0
        val perDay = if (timestamps.size >= 2 && spanDays > 0.5) {
            (timestamps.size - 1) / spanDays
        } else {
            null
        }

        // A gap exists when the oldest record still on the device is newer than the newest
        // reading already stored: everything between the two was overwritten before it was
        // ever synced. Only inferable once something has been stored.
        //
        // Requires a full buffer. A cuff with spare slots cannot have overwritten anything,
        // so the same comparison on a partly-filled buffer means something else entirely
        // (device memory cleared, records deleted, a different cuff) and must not be
        // reported as silent data loss.
        val stored = parse(newestStoredIso)
        val gap = stored != null && slotsUsed >= capacity && oldest.isAfter(stored)
        val lostDays = if (gap && stored != null) {
            Duration.between(stored, oldest).toMinutes() / 1440.0
        } else {
            null
        }
        val estimatedLost = if (lostDays != null && perDay != null) {
            kotlin.math.round(lostDays * perDay).toInt()
        } else {
            null
        }

        // Headroom: how long until the buffer turns over completely, at the observed rate.
        // Once full, every new reading overwrites the oldest, so this is the sync interval
        // that must not be exceeded rather than a countdown to a one-off event.
        val headroom = perDay?.takeIf { it > 0 }?.let { capacity / it }

        val warning = when {
            gap && estimatedLost != null && estimatedLost > 0 ->
                "Readings were overwritten before they were synced: roughly $estimatedLost " +
                    "reading(s) between ${newestStoredIso} and ${oldest.format(ISO)} are gone " +
                    "from the cuff for good. Sync more often."
            gap ->
                "Readings were overwritten before they were synced: the gap between " +
                    "${newestStoredIso} and ${oldest.format(ISO)} is lost. Sync more often."
            headroom != null && headroom < LOW_HEADROOM_DAYS ->
                "The cuff only holds $capacity readings — about " +
                    "${"%.0f".format(headroom)} day(s) at the current rate. Sync at least " +
                    "that often or measurements will be overwritten."
            else -> null
        }

        val detail = buildString {
            append("$slotsUsed/$capacity slots used")
            if (unreadOnDevice != null) append(", $unreadOnDevice unread on device")
            if (perDay != null) append(", ${"%.1f".format(perDay)} readings/day")
            if (headroom != null) append(", ~${"%.0f".format(headroom)}d of buffer")
        }

        return BufferHealth(
            slotsUsed = slotsUsed,
            capacity = capacity,
            unreadOnDevice = unreadOnDevice,
            oldestOnDeviceIso = oldest.format(ISO),
            newestOnDeviceIso = newest.format(ISO),
            gapDetected = gap,
            gapFromIso = if (gap) newestStoredIso else null,
            gapToIso = if (gap) oldest.format(ISO) else null,
            readingsPerDay = perDay,
            daysOfHeadroom = headroom,
            warning = warning,
            detail = detail,
        )
    }
}
