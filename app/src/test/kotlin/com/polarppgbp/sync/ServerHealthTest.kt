package com.polarppgbp.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16: a wrong address and a wrong token look identical to a user, so the check has to
 * name the stage that failed. These cover the interpretation rather than the sockets.
 */
class ServerHealthTest {

    private val okBody = """{"status":"ok","version":"0.2.0"}"""

    private fun probe(
        url: String? = "http://192.168.1.5:8000",
        tokenPresent: Boolean = true,
        transportError: String? = null,
        healthCode: Int? = null,
        healthBody: String? = null,
        authCode: Int? = null,
        authError: String? = null,
    ) = HealthProbe(url, tokenPresent, transportError, healthCode, healthBody, authCode, authError)

    @Test
    fun noServerConfigured() {
        val r = ServerHealth.interpret(probe(url = null, tokenPresent = false))
        assertEquals(HealthStage.NOT_CONFIGURED, r.stage)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("stay on this phone"))
    }

    @Test
    fun urlWithoutTokenCountsAsUnconfigured() {
        // Half-configured is not usable, and saying "unreachable" would misdirect.
        val r = ServerHealth.interpret(probe(tokenPresent = false))
        assertEquals(HealthStage.NOT_CONFIGURED, r.stage)
    }

    @Test
    fun transportFailureIsUnreachableAndSuggestsTheNetwork() {
        val r = ServerHealth.interpret(probe(transportError = "Failed to connect to /192.168.1.5:8000"))
        assertEquals(HealthStage.UNREACHABLE, r.stage)
        assertFalse(r.ok)
        assertTrue(r.detail.contains("Could not reach"))
        assertTrue("should hint at the network, not the token", r.detail.contains("Tailnet"))
    }

    @Test
    fun somethingElseAnsweringOnThatPortIsNotTheServer() {
        // A router admin page or another service on the port: reachable, wrong thing.
        val r = ServerHealth.interpret(probe(healthCode = 404))
        assertEquals(HealthStage.UNREACHABLE, r.stage)
        assertTrue(r.detail.contains("does not look like this server"))
    }

    @Test
    fun rejectedTokenIsReportedAsATokenProblemNotAnAddressProblem() {
        val r = ServerHealth.interpret(probe(healthCode = 200, healthBody = okBody, authCode = 401))
        assertEquals(HealthStage.REACHABLE, r.stage)
        assertFalse(r.ok)
        assertTrue("must say the address is fine", r.detail.contains("address is right"))
        assertTrue(r.detail.contains("token"))
        assertEquals("0.2.0", r.serverVersion)
    }

    @Test
    fun forbiddenIsTreatedLikeRejected() {
        val r = ServerHealth.interpret(probe(healthCode = 200, healthBody = okBody, authCode = 403))
        assertEquals(HealthStage.REACHABLE, r.stage)
        assertTrue(r.detail.contains("token was rejected"))
    }

    @Test
    fun serverUpButFailingAuthenticatedRequests() {
        val r = ServerHealth.interpret(probe(healthCode = 200, healthBody = okBody, authCode = 500))
        assertEquals(HealthStage.REACHABLE, r.stage)
        assertTrue(r.detail.contains("not accepting requests"))
    }

    @Test
    fun fullyHealthy() {
        val r = ServerHealth.interpret(probe(healthCode = 200, healthBody = okBody, authCode = 200))
        assertEquals(HealthStage.AUTHENTICATED, r.stage)
        assertTrue(r.ok)
        assertEquals("0.2.0", r.serverVersion)
        assertEquals(true, r.versionCompatible)
        assertTrue(r.detail.contains("token accepted"))
    }

    @Test
    fun versionMismatchIsSurfacedButDoesNotClaimUnreachable() {
        val r = ServerHealth.interpret(
            probe(healthCode = 200, healthBody = """{"status":"ok","version":"0.9.1"}""", authCode = 200),
        )
        assertEquals(HealthStage.AUTHENTICATED, r.stage)
        assertEquals(false, r.versionCompatible)
        assertFalse("a mismatch should not read as fully ok", r.ok)
        assertTrue(r.detail.contains("0.9.1"))
        assertTrue(r.detail.contains("may be rejected"))
    }

    @Test
    fun malformedHealthBodyDoesNotCrashOrBlockTheTokenCheck() {
        val r = ServerHealth.interpret(probe(healthCode = 200, healthBody = "not json", authCode = 200))
        assertEquals(HealthStage.AUTHENTICATED, r.stage)
        assertNull(r.serverVersion)
        assertNull(r.versionCompatible)
        assertTrue(r.detail.contains("version not reported"))
    }

    @Test
    fun patchVersionDifferencesAreCompatible() {
        assertEquals(true, ServerHealth.versionsCompatible("0.2.7", "0.2.0"))
        assertEquals(false, ServerHealth.versionsCompatible("0.3.0", "0.2.0"))
        assertNull(ServerHealth.versionsCompatible(null))
        assertNull(ServerHealth.versionsCompatible("  "))
    }

    @Test
    fun detailNeverContainsATokenValue() {
        // Guard: the report is meant to be pasteable into a debug report (#13), so it
        // must not carry the bearer token.
        val secret = "a".repeat(64)
        val reports = listOf(
            ServerHealth.interpret(probe(transportError = "connect failed")),
            ServerHealth.interpret(probe(healthCode = 200, healthBody = okBody, authCode = 401)),
            ServerHealth.interpret(probe(healthCode = 200, healthBody = okBody, authCode = 200)),
        )
        reports.forEach { assertFalse(it.detail.contains(secret)) }
    }
}
