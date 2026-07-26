package com.polarppgbp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #15: server details must be validated at entry, because the alternative is a
 * WorkManager job failing silently hours later.
 */
class ServerConfigTest {

    private val realToken = "a".repeat(64)

    @Test
    fun acceptsFullUrlUnchanged() {
        assertEquals("http://192.168.1.5:8000", ServerConfig.normaliseUrl("http://192.168.1.5:8000"))
    }

    @Test
    fun addsMissingScheme() {
        // Typing host:port is the natural thing to do; rejecting it teaches nothing.
        assertEquals("http://192.168.1.5:8000", ServerConfig.normaliseUrl("192.168.1.5:8000"))
    }

    @Test
    fun stripsTrailingSlashes() {
        // The sync workers also trimEnd('/'), so this only has to agree with them.
        assertEquals("http://host:8000", ServerConfig.normaliseUrl("http://host:8000/"))
        assertEquals("http://host:8000", ServerConfig.normaliseUrl("http://host:8000///"))
    }

    @Test
    fun keepsHttpsAndTailscaleStyleHostnames() {
        assertEquals("https://pi.example.ts.net", ServerConfig.normaliseUrl("https://pi.example.ts.net"))
        // Tailscale hostnames work today with plain manual entry, no discovery needed.
        assertEquals("http://raspberrypi", ServerConfig.normaliseUrl("raspberrypi"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("http://host:8000", ServerConfig.normaliseUrl("  http://host:8000  "))
    }

    @Test
    fun rejectsInternalWhitespace() {
        assertNull(ServerConfig.normaliseUrl("http://host :8000"))
    }

    @Test
    fun rejectsEmptyHost() {
        assertNull(ServerConfig.normaliseUrl("http://:8000"))
        assertNull(ServerConfig.normaliseUrl(""))
    }

    @Test
    fun rejectsUnknownScheme() {
        // Guessing at a wrong scheme would silently point sync somewhere unreachable.
        assertNull(ServerConfig.normaliseUrl("ftp://host:8000"))
    }

    @Test
    fun rejectsNonNumericAndOutOfRangePorts() {
        assertNull(ServerConfig.normaliseUrl("host:port"))
        assertNull(ServerConfig.normaliseUrl("host:0"))
        assertNull(ServerConfig.normaliseUrl("host:70000"))
    }

    @Test
    fun validateAcceptsGoodInput() {
        val r = ServerConfig.validate("192.168.1.5:8000", realToken)
        assertTrue(r is ServerConfigResult.Valid)
        r as ServerConfigResult.Valid
        assertEquals("http://192.168.1.5:8000", r.url)
        assertEquals(realToken, r.token)
    }

    @Test
    fun validateReportsBothFieldsIndependently() {
        val r = ServerConfig.validate("", "")
        assertTrue(r is ServerConfigResult.Invalid)
        r as ServerConfigResult.Invalid
        assertTrue(r.urlError!!.isNotBlank())
        assertTrue(r.tokenError!!.isNotBlank())
    }

    @Test
    fun validateRejectsTokenWithSpaces() {
        // A token pasted with a stray newline or space is a realistic mistake.
        val r = ServerConfig.validate("host:8000", "abc def")
        assertTrue(r is ServerConfigResult.Invalid)
        assertNull((r as ServerConfigResult.Invalid).urlError)
        assertTrue(r.tokenError!!.contains("spaces"))
    }

    @Test
    fun tokenIsTrimmedNotRejectedForOuterWhitespace() {
        val r = ServerConfig.validate("host:8000", "  $realToken\n")
        assertTrue(r is ServerConfigResult.Valid)
        assertEquals(realToken, (r as ServerConfigResult.Valid).token)
    }

    @Test
    fun unusualTokenShapeIsAcceptedButFlagged() {
        // The token format is the server's business: hint, never reject.
        assertTrue(ServerConfig.validate("host:8000", "short-token") is ServerConfigResult.Valid)
        assertFalse(ServerConfig.looksLikeServerToken("short-token"))
        assertTrue(ServerConfig.looksLikeServerToken(realToken))
    }

    @Test
    fun maskTokenNeverRevealsTheSecret() {
        val masked = ServerConfig.maskToken(realToken)
        assertEquals(64, masked.length)
        assertTrue("should keep a short tail for telling tokens apart", masked.endsWith("aaaa"))
        assertFalse("must not contain the full token", masked.contains(realToken))
        assertEquals("not set", ServerConfig.maskToken(null))
        assertEquals("not set", ServerConfig.maskToken("   "))
    }
}
