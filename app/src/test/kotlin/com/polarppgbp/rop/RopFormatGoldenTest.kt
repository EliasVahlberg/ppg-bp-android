/*
 * Golden-bytes contract test for the Kotlin ROP writer.
 *
 * Reproduces the exact logical input used by
 * `scripts/make_golden_bundle.py` and asserts the Kotlin output is
 * byte-identical to the committed fixture in
 * `tests/fixtures/golden_session/`. If this passes, the Kotlin writer is
 * contract-correct against the canonical Python reference (#20).
 *
 * Pure JVM test (no Android dependencies); runs under testDebugUnitTest.
 */

package com.polarppgbp.rop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class RopFormatGoldenTest {

    // Fixed constants mirroring make_golden_bundle.py.
    private val sessionUuid = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val epochOffsetNs = 1_240_000_000_000_000_000L
    private val rotationStartMs = 1_780_000_000_000L
    private val base = 538_000_000_000_000_000L
    private val segmentId = 1

    private fun tsSeries(rate: Int, n: Int): LongArray {
        val interval = 1_000_000_000L / rate
        return LongArray(n) { i -> base + i * interval }
    }

    /** Locate tests/fixtures/golden_session by walking up from the working dir. */
    private fun goldenDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val cur: File = dir
            val candidate = File(cur, "tests/fixtures/golden_session")
            if (candidate.isDirectory) return candidate
            dir = cur.parentFile
        }
        throw IllegalStateException(
            "golden_session fixture not found from ${System.getProperty("user.dir")}"
        )
    }

    @Test
    fun ppgMatchesGolden() {
        val header = RopHeader(SensorType.PPG, 176, sessionUuid, rotationStartMs, epochOffsetNs)
        val out = ByteArrayOutputStream()
        out.write(header.pack())
        tsSeries(176, 60).forEachIndexed { i, ts ->
            out.write(packPpg(ts, segmentId, 100000 + i, 100000 - i, 100000 + (i % 7), 5000 + i))
        }
        val golden = goldenDir().resolve("ppg_000.rop").readBytes()
        assertArrayEquals("PPG ROP bytes diverge from golden fixture", golden, out.toByteArray())
    }

    @Test
    fun accMatchesGolden() {
        val header = RopHeader(SensorType.ACC, 52, sessionUuid, rotationStartMs, epochOffsetNs)
        val out = ByteArrayOutputStream()
        out.write(header.pack())
        tsSeries(52, 20).forEachIndexed { i, ts ->
            out.write(packAcc(ts, segmentId, 10 + i, -20 + i, 1000 - i))
        }
        val golden = goldenDir().resolve("acc_000.rop").readBytes()
        assertArrayEquals("ACC ROP bytes diverge from golden fixture", golden, out.toByteArray())
    }

    @Test
    fun headerIs64Bytes() {
        val h = RopHeader(SensorType.PPG, 176, sessionUuid, rotationStartMs, epochOffsetNs)
        assertEquals(ROP_HEADER_SIZE, h.pack().size)
    }

    @Test
    fun headerRoundTrips() {
        val h = RopHeader(SensorType.GYRO, 52, sessionUuid, rotationStartMs, epochOffsetNs)
        val parsed = RopHeader.unpack(h.pack())
        assertEquals(h, parsed)
    }

    @Test
    fun recordSizesMatchSpec() {
        assertEquals(32, packPpg(0, 1, 0, 0, 0, 0).size)
        assertEquals(24, packAcc(0, 1, 0, 0, 0).size)
        assertEquals(24, packGyro(0, 1, 0f, 0f, 0f).size)
        assertEquals(32, packMag(0, 1, 0f, 0f, 0f, -1).size)
        assertEquals(24, packPpi(0, 1, 60, 800, 0, blocker = false, skinContact = true, scSupported = true).size)
    }

    @Test
    fun interpolationAnchorsLastSample() {
        val ts = interpolateSampleTimestamps(1_000_000_000L, 4, 176)
        assertEquals(1_000_000_000L, ts.last())
        // Strictly increasing by the per-sample interval.
        val interval = 1_000_000_000L / 176
        for (i in 1 until ts.size) assertEquals(interval, ts[i] - ts[i - 1])
    }
}
