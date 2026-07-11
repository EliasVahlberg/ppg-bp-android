/*
 * Unit test for SessionWriter: builds a small session and verifies the bundle
 * is structurally conformant (ROP header carries the captured epoch offset,
 * rotation files are listed in manifest.json, segments are appended).
 *
 * Pure JVM (no Android deps); runs under testDebugUnitTest.
 */

package com.polarppgbp.recorder

import com.polarppgbp.rop.RopHeader
import com.polarppgbp.rop.SensorType
import com.polarppgbp.rop.packPpg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.UUID

class SessionWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesConformantBundle() {
        val dir = tmp.newFolder("session")
        val uuid = UUID.fromString("00000000-0000-4000-8000-000000000099")
        val writer = SessionWriter(
            sessionDir = dir,
            sessionUuid = uuid,
            profile = Profile.CALIBRATION,
            deviceName = "Polar Verity Sense",
            deviceAddress = "24:AC:AC:15:6A:C5",
            startedAtEpochSec = 1_780_000_000.0,
            rotationPeriodMinutes = 15,
        )

        // First sample captures epoch offset.
        val deviceTs = 538_000_000_000_000_000L
        val wallNs = 1_240_538_000_000_000_000L
        writer.captureEpochOffset(deviceTs, wallNs)

        writer.appendSegment("connect", 1, 1_780_000_000.0, "initial", "24:AC:AC:15:6A:C5")

        val out = ByteArrayOutputStream()
        repeat(10) { i -> out.write(packPpg(deviceTs + i, 1, 100 + i, 100 - i, 100, 5)) }
        writer.appendRecords(SensorType.PPG, out.toByteArray(), 10, 176, nowMs = 1_000_000L)

        writer.appendSegment("disconnect", 1, 1_780_000_001.0, "stop", "24:AC:AC:15:6A:C5")
        writer.markEnded(1_780_000_001.0)
        writer.close()

        // ROP file exists and its header carries the captured epoch offset.
        val ropFile = dir.resolve("ppg_000.rop")
        assertTrue("ppg_000.rop should exist", ropFile.isFile)
        val header = RopHeader.unpack(ropFile.readBytes())
        assertEquals(SensorType.PPG, header.sensor)
        assertEquals(176, header.sampleRateHz)
        assertEquals(uuid, header.sessionUuid)
        assertEquals(wallNs - deviceTs, header.epochOffsetNs)
        // 64-byte header + 10 * 32-byte records.
        assertEquals(64L + 10 * 32, ropFile.length())

        // manifest lists the rop file and required keys.
        val manifest = dir.resolve("manifest.json").readText()
        assertTrue(manifest.contains("\"ppg_000.rop\""))
        assertTrue(manifest.contains("\"session_uuid\""))
        assertTrue(manifest.contains("\"epoch_offset_ns\""))
        assertTrue(manifest.contains("\"profile\":\"calibration\""))

        // segments.jsonl has both events.
        val segments = dir.resolve("segments.jsonl").readText().trim().lines()
        assertEquals(2, segments.size)
        assertTrue(segments[0].contains("\"event\":\"connect\""))
        assertTrue(segments[1].contains("\"event\":\"disconnect\""))
    }

    @Test
    fun epochOffsetCapturedOnlyOnce() {
        val dir = tmp.newFolder("session2")
        val writer = SessionWriter(
            sessionDir = dir,
            sessionUuid = UUID.randomUUID(),
            profile = Profile.MONITOR,
            deviceName = null,
            deviceAddress = null,
            startedAtEpochSec = 0.0,
        )
        writer.captureEpochOffset(100, 1000)
        writer.captureEpochOffset(200, 5000) // ignored
        assertEquals(900, writer.epochOffsetNs)
        writer.close()
    }
}
