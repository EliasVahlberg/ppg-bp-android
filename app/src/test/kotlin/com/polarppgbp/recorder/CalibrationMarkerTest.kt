package com.polarppgbp.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMarkerTest {

    @Test
    fun `tags are split trimmed and lowercased`() {
        assertEquals(
            listOf("supine", "morning", "pump-low"),
            CalibrationMarker.parseTags(" Supine , MORNING ,pump-low"),
        )
    }

    /**
     * Case and whitespace variants grouping as separate tags would be a silent
     * analysis bug, so normalisation is part of the contract, not cosmetic.
     */
    @Test
    fun `duplicate tags collapse after normalisation`() {
        assertEquals(listOf("standing"), CalibrationMarker.parseTags("Standing, standing , STANDING"))
    }

    @Test
    fun `empty and separator-only tag entries yield nothing`() {
        assertEquals(emptyList<String>(), CalibrationMarker.parseTags(null))
        assertEquals(emptyList<String>(), CalibrationMarker.parseTags("   "))
        assertEquals(emptyList<String>(), CalibrationMarker.parseTags(",,, ,"))
    }

    @Test
    fun `name is trimmed and blank becomes null`() {
        assertEquals("Morning run", CalibrationMarker.cleanName("  Morning run "))
        assertNull(CalibrationMarker.cleanName(""))
        assertNull(CalibrationMarker.cleanName("   "))
        assertNull(CalibrationMarker.cleanName(null))
    }

    @Test
    fun `an overlong name is truncated rather than rejected`() {
        val long = "x".repeat(500)
        val cleaned = CalibrationMarker.cleanName(long)
        assertEquals(CalibrationMarker.MAX_NAME_LENGTH, cleaned?.length)
    }

    @Test
    fun `start payload carries event name and tags`() {
        assertEquals(
            """{"event":"calibration_start","name":"Session 1","tags":["supine","morning"]}""",
            CalibrationMarker.startPayload("Session 1", "Supine, morning"),
        )
    }

    @Test
    fun `stop payload repeats name and tags so it stands alone`() {
        assertEquals(
            """{"event":"calibration_stop","name":"Session 1","tags":["supine"]}""",
            CalibrationMarker.stopPayload("Session 1", "supine"),
        )
    }

    @Test
    fun `absent name and tags are omitted rather than written as empty`() {
        assertEquals(
            """{"event":"calibration_start"}""",
            CalibrationMarker.startPayload("", "  "),
        )
    }

    /**
     * The payload is embedded in a JSONL line, so an unescaped quote or newline in a
     * typed name would corrupt the notes file for the whole session, not just the note.
     */
    @Test
    fun `quotes backslashes and newlines are escaped`() {
        val payload = CalibrationMarker.startPayload("say \"hi\"\\ \n here", null)
        assertTrue(payload.contains("""say \"hi\"\\ \n here"""))
        assertTrue(payload.lines().size == 1)
    }

    @Test
    fun `control characters are escaped as unicode`() {
        val payload = CalibrationMarker.startPayload("a\u0001b", null)
        assertTrue(payload.contains("a\\u0001b"))
    }
}
