/*
 * SessionWriter — assembles a conformant session bundle from decoded samples.
 *
 * Produces exactly the bundle layout the desktop converter
 * (polar_ble.converter.convert_session) and the conformance suite (#20)
 * expect:
 *
 *   <sessionDir>/
 *     manifest.json        (written/refreshed on finalize)
 *     segments.jsonl       (connect/disconnect events, append-only)
 *     notes.jsonl          (optional posture/event notes, append-only)
 *     <sensor>_NNN.rop      (one per sensor per rotation window)
 *
 * Responsibilities:
 *   - Capture epoch_offset_ns = wall_ns - device_ns from the FIRST sample of
 *     the session (Option A from android_recorder.md §8 — we never mutate the
 *     device clock). All ROP headers in the session carry this offset.
 *   - Rotate ROP files on a wall-clock window (default 15 min) so a crash
 *     loses at most one window's tail (recoverable via partial-record trim).
 *   - Stay off the BLE callback's critical path: callers pass already-decoded,
 *     per-sample-timestamped records; writes are plain appends.
 *
 * Deliberately has NO Android or Polar-SDK imports so it is unit-testable on
 * the JVM and cannot be coupled to SDK types. The repository adapts SDK frames
 * into these calls.
 *
 * Thread-safety: all mutating methods are synchronized on the instance. The
 * repository funnels every sensor's writes through one SessionWriter.
 */

package com.polarppgbp.recorder

import com.polarppgbp.rop.RopHeader
import com.polarppgbp.rop.RopWriter
import com.polarppgbp.rop.SensorType
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Capture profile: which sensors at which rates. */
data class Profile(
    val name: String,
    val rates: Map<SensorType, Int>,
) {
    companion object {
        /** Full-rate calibration capture (PPG + ACC + GYRO). */
        val CALIBRATION = Profile(
            "calibration",
            mapOf(SensorType.PPG to 176, SensorType.ACC to 416, SensorType.GYRO to 416),
        )

        /** Lighter long-term monitoring (PPG + low-rate ACC for posture). */
        val MONITOR = Profile(
            "monitor",
            mapOf(SensorType.PPG to 176, SensorType.ACC to 52),
        )

        fun byName(name: String): Profile = when (name.lowercase()) {
            "monitor" -> MONITOR
            else -> CALIBRATION
        }
    }
}

class SessionWriter(
    val sessionDir: File,
    val sessionUuid: UUID,
    val profile: Profile,
    private var deviceName: String?,
    private var deviceAddress: String?,
    private val startedAtEpochSec: Double,
    private val rotationPeriodMinutes: Int = 15,
) : AutoCloseable {

    private val rotationPeriodMs: Long = TimeUnit.MINUTES.toMillis(rotationPeriodMinutes.toLong())

    /** Set once, from the first sample seen across the whole session. */
    @Volatile
    var epochOffsetNs: Long = 0L
        private set
    private var epochCaptured = false

    private data class SensorState(
        var writer: RopWriter?,
        var rotationIndex: Int,
        var windowStartMs: Long,
        val emittedFiles: MutableList<String>,
    )

    private val state = HashMap<SensorType, SensorState>()
    private val segmentsFile = File(sessionDir, "segments.jsonl")
    private val notesFile = File(sessionDir, "notes.jsonl")
    private var endedAtEpochSec: Double = startedAtEpochSec

    init {
        sessionDir.mkdirs()
    }

    /** Set device identity once known (on connect); reflected in manifest.json. */
    @Synchronized
    fun setDevice(name: String?, address: String?) {
        if (name != null) deviceName = name
        if (address != null) deviceAddress = address
    }

    /**
     * Capture the wall↔device epoch offset from the first sample.
     * [deviceTsNs] is the SDK frame/sample timestamp (device epoch, ns);
     * [wallNs] is the phone's wall clock in ns at receipt.
     */
    @Synchronized
    fun captureEpochOffset(deviceTsNs: Long, wallNs: Long) {
        if (!epochCaptured) {
            epochOffsetNs = wallNs - deviceTsNs
            epochCaptured = true
        }
    }

    /**
     * Append [count] already-packed, per-sample-timestamped records for [sensor].
     * Rotates the ROP file if the current wall-clock window has elapsed.
     * [nowMs] is the phone wall clock in ms (passed in for testability).
     */
    @Synchronized
    fun appendRecords(sensor: SensorType, records: ByteArray, count: Int, sampleRateHz: Int, nowMs: Long) {
        val st = state.getOrPut(sensor) {
            SensorState(writer = null, rotationIndex = 0, windowStartMs = nowMs, emittedFiles = mutableListOf())
        }
        if (st.writer == null || nowMs - st.windowStartMs >= rotationPeriodMs) {
            rotate(sensor, st, sampleRateHz, nowMs)
        }
        st.writer!!.writeRecords(records, count)
    }

    private fun rotate(sensor: SensorType, st: SensorState, sampleRateHz: Int, nowMs: Long) {
        st.writer?.close()
        val name = "%s_%03d.rop".format(sensor.name.lowercase(), st.rotationIndex)
        val header = RopHeader(
            sensor = sensor,
            sampleRateHz = sampleRateHz,
            sessionUuid = sessionUuid,
            rotationStartMs = nowMs,
            epochOffsetNs = epochOffsetNs,
        )
        st.writer = RopWriter(File(sessionDir, name), header)
        st.emittedFiles += name
        st.rotationIndex += 1
        st.windowStartMs = nowMs
    }

    /** fsync all open writers (call periodically, e.g. on a heartbeat). */
    @Synchronized
    fun sync() {
        for (st in state.values) st.writer?.sync()
    }

    /** Append a connect/disconnect segment event. */
    @Synchronized
    fun appendSegment(event: String, segmentId: Int, tsEpochSec: Double, reason: String?, device: String?) {
        val sb = StringBuilder("{")
        sb.append("\"event\":").append(jsonStr(event)).append(',')
        sb.append("\"segment_id\":").append(segmentId).append(',')
        sb.append("\"ts\":").append(tsEpochSec)
        if (reason != null) sb.append(",\"reason\":").append(jsonStr(reason))
        if (device != null) sb.append(",\"device\":").append(jsonStr(device))
        sb.append("}\n")
        segmentsFile.appendText(sb.toString())
    }

    /** Append a free-text note (e.g. posture tap). */
    @Synchronized
    fun appendNote(tsEpochSec: Double, note: String) {
        notesFile.appendText("{\"ts\":$tsEpochSec,\"note\":${jsonStr(note)}}\n")
    }

    fun markEnded(endedAtEpochSec: Double) {
        this.endedAtEpochSec = endedAtEpochSec
    }

    /** Close writers and write manifest.json listing all emitted ROP files. */
    @Synchronized
    override fun close() {
        for (st in state.values) {
            st.writer?.close()
            st.writer = null
        }
        writeManifest()
    }

    private fun writeManifest() {
        val ropFiles = state.values.flatMap { it.emittedFiles }.sorted()
        val settings = profile.rates.entries
            .joinToString(",") { "${jsonStr(it.key.name.lowercase())}:${it.value}" }
        val sb = StringBuilder("{\n")
        sb.append("  \"session_uuid\": ").append(jsonStr(sessionUuid.toString())).append(",\n")
        sb.append("  \"started_at\": ").append(startedAtEpochSec).append(",\n")
        sb.append("  \"ended_at\": ").append(endedAtEpochSec).append(",\n")
        sb.append("  \"device_name\": ").append(jsonStr(deviceName)).append(",\n")
        sb.append("  \"device_address\": ").append(jsonStr(deviceAddress)).append(",\n")
        sb.append("  \"settings\": {").append(settings)
            .append(",\"profile\":").append(jsonStr(profile.name)).append("},\n")
        sb.append("  \"epoch_offset_ns\": ").append(epochOffsetNs).append(",\n")
        sb.append("  \"rotation_period_minutes\": ").append(rotationPeriodMinutes).append(",\n")
        sb.append("  \"rop_files\": [")
            .append(ropFiles.joinToString(",") { jsonStr(it) })
            .append("]\n")
        sb.append("}\n")
        File(sessionDir, "manifest.json").writeText(sb.toString())
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        val out = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
        return out.toString()
    }
}
