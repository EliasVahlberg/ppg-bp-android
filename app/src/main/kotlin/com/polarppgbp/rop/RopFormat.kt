/*
 * Raw ROP (Rotation Output Period) file format v1 — Kotlin port.
 *
 * Byte-exact port of the canonical Python reference in
 * `src/polar_ble/rop_format.py`. The on-disk bytes produced here MUST be
 * identical to the Python writer for the same logical input; this is the
 * cross-platform contract verified against `tests/fixtures/golden_session/`
 * (see RopFormatGoldenTest).
 *
 * Layout (little-endian except the 16-byte UUID, which is big-endian /
 * RFC 4122 network order, matching Python's `uuid.bytes`):
 *
 *   Header (64 bytes):
 *      0  4  magic            ASCII "ROP1"
 *      4  1  version          u8 (=1)
 *      5  1  sensor_type      u8 (0=PPG 1=ACC 2=GYRO 3=MAG 4=PPI)
 *      6  2  record_size      u16le
 *      8  2  sample_rate_hz   u16le
 *     10  2  reserved         u16le (=0)
 *     12 16  session_uuid     UUID bytes (big-endian: MSB then LSB)
 *     28  8  rotation_start_ms i64le (UTC unix ms)
 *     36  8  epoch_offset_ns  i64le (wall_ns - device_ns)
 *     44 20  reserved         twenty zero bytes
 *
 *   Every record starts with (ts_ns:i64le, segment_id:i32le).
 */

package com.polarppgbp.rop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

const val ROP_HEADER_SIZE: Int = 64
const val ROP_VERSION: Int = 1
val ROP_MAGIC: ByteArray = byteArrayOf(0x52, 0x4F, 0x50, 0x31) // "ROP1"

/** Numeric sensor identifier used in the ROP header. Stable across versions. */
enum class SensorType(val id: Int, val recordSize: Int) {
    PPG(0, 32),
    ACC(1, 24),
    GYRO(2, 24),
    MAG(3, 32),
    PPI(4, 24);

    companion object {
        fun fromId(id: Int): SensorType =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown sensor_type $id")
    }
}

private fun leBuffer(size: Int): ByteBuffer =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

/** UUID as 16 big-endian bytes (MSB first), matching Python's `uuid.bytes`. */
internal fun uuidBytes(uuid: UUID): ByteArray {
    val out = ByteArray(16)
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    for (i in 0 until 8) out[i] = (msb ushr (8 * (7 - i))).toByte()
    for (i in 0 until 8) out[8 + i] = (lsb ushr (8 * (7 - i))).toByte()
    return out
}

/** Parsed/serialisable ROP file header. */
data class RopHeader(
    val sensor: SensorType,
    val sampleRateHz: Int,
    val sessionUuid: UUID,
    val rotationStartMs: Long,
    val epochOffsetNs: Long,
    val version: Int = ROP_VERSION,
) {
    /** Serialise to exactly [ROP_HEADER_SIZE] bytes. */
    fun pack(): ByteArray {
        val b = leBuffer(ROP_HEADER_SIZE)
        b.put(ROP_MAGIC)                              // 0..3
        b.put(version.toByte())                       // 4
        b.put(sensor.id.toByte())                     // 5
        b.putShort(sensor.recordSize.toShort())       // 6..7
        b.putShort(sampleRateHz.toShort())            // 8..9
        b.putShort(0)                                 // 10..11 reserved
        b.put(uuidBytes(sessionUuid))                 // 12..27 (big-endian)
        b.putLong(rotationStartMs)                    // 28..35
        b.putLong(epochOffsetNs)                      // 36..43
        b.put(ByteArray(20))                          // 44..63 reserved
        return b.array()
    }

    companion object {
        fun unpack(data: ByteArray): RopHeader {
            require(data.size >= ROP_HEADER_SIZE) {
                "ROP header needs $ROP_HEADER_SIZE bytes, got ${data.size}"
            }
            val b = ByteBuffer.wrap(data, 0, ROP_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(magic.contentEquals(ROP_MAGIC)) { "Not a ROP file (bad magic)" }
            val version = b.get().toInt() and 0xFF
            require(version == ROP_VERSION) { "Unsupported ROP version $version" }
            val sensor = SensorType.fromId(b.get().toInt() and 0xFF)
            val recordSize = b.short.toInt() and 0xFFFF
            require(recordSize == sensor.recordSize) {
                "Header record_size $recordSize != expected ${sensor.recordSize} for $sensor"
            }
            val rate = b.short.toInt() and 0xFFFF
            b.short // reserved
            val uuidRaw = ByteArray(16).also { b.get(it) }
            val msb = ByteBuffer.wrap(uuidRaw, 0, 8).order(ByteOrder.BIG_ENDIAN).long
            val lsb = ByteBuffer.wrap(uuidRaw, 8, 8).order(ByteOrder.BIG_ENDIAN).long
            val rotationMs = b.long
            val epochNs = b.long
            return RopHeader(sensor, rate, UUID(msb, lsb), rotationMs, epochNs, version)
        }
    }
}

// ---------------------------------------------------------------------------
// Record packers — one per sensor. All little-endian.
// ---------------------------------------------------------------------------

fun packPpg(tsNs: Long, segmentId: Int, ppg0: Int, ppg1: Int, ppg2: Int, ambient: Int): ByteArray {
    val b = leBuffer(SensorType.PPG.recordSize)
    b.putLong(tsNs); b.putInt(segmentId)
    b.putInt(ppg0); b.putInt(ppg1); b.putInt(ppg2); b.putInt(ambient); b.putInt(0)
    return b.array()
}

fun packAcc(tsNs: Long, segmentId: Int, x: Int, y: Int, z: Int): ByteArray {
    val b = leBuffer(SensorType.ACC.recordSize)
    b.putLong(tsNs); b.putInt(segmentId); b.putInt(x); b.putInt(y); b.putInt(z)
    return b.array()
}

fun packGyro(tsNs: Long, segmentId: Int, x: Float, y: Float, z: Float): ByteArray {
    val b = leBuffer(SensorType.GYRO.recordSize)
    b.putLong(tsNs); b.putInt(segmentId); b.putFloat(x); b.putFloat(y); b.putFloat(z)
    return b.array()
}

fun packMag(tsNs: Long, segmentId: Int, x: Float, y: Float, z: Float, calibration: Int): ByteArray {
    val b = leBuffer(SensorType.MAG.recordSize)
    b.putLong(tsNs); b.putInt(segmentId)
    b.putFloat(x); b.putFloat(y); b.putFloat(z); b.putInt(calibration); b.putInt(0)
    return b.array()
}

fun packPpi(
    tsNs: Long, segmentId: Int, hr: Int, ppiMs: Int, errMs: Int,
    blocker: Boolean, skinContact: Boolean, scSupported: Boolean,
): ByteArray {
    val flags = (if (blocker) 0x01 else 0) or
        (if (skinContact) 0x02 else 0) or
        (if (scSupported) 0x04 else 0)
    val b = leBuffer(SensorType.PPI.recordSize)
    b.putLong(tsNs); b.putInt(segmentId)
    b.putShort((hr and 0xFFFF).toShort())
    b.putShort((ppiMs and 0xFFFF).toShort())
    b.putShort((errMs and 0xFFFF).toShort())
    b.putShort((flags and 0xFFFF).toShort())
    b.putInt(0)
    return b.array()
}

/**
 * Per-sample timestamps for a frame. The PMD frame timestamp is the timestamp
 * of the LAST sample; earlier samples are at preceding 1/sample_rate intervals.
 * Returns [nSamples] timestamps in chronological order; the last equals [frameTsNs].
 */
fun interpolateSampleTimestamps(frameTsNs: Long, nSamples: Int, sampleRateHz: Int): LongArray {
    if (nSamples <= 0) return LongArray(0)
    if (sampleRateHz <= 0) return LongArray(nSamples) { frameTsNs }
    val intervalNs = 1_000_000_000L / sampleRateHz
    return LongArray(nSamples) { i -> frameTsNs - (nSamples - 1 - i) * intervalNs }
}
