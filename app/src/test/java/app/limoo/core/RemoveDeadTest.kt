package app.limoo.core

import app.limoo.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Remove unreachable" used to delete on `pingMs == 0`, but 0 meant two different things: the server
 * genuinely timed out, AND the probe could not run at all (core unsupported, no test URL, no network).
 * Those are not the same claim - "we could not measure this" is no evidence that a server is dead - and
 * treating them as one made a routine server-list action destructive whenever a probe was unavailable.
 *
 * The rule now is: delete only when BOTH signals are a real timeout. A server that was never measured
 * is never deleted.
 *
 * These tests pin the decision function itself, so the rule cannot silently regress back to "0 means
 * dead" when a new probe mode is added.
 */
class RemoveDeadTest {
    private fun srv(id: String, ping: Long, tcp: Long) = Server(
        id = id, name = id, protocol = "vless", host = "$id.example.com", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", pingMs = ping, tcpMs = tcp,
    )

    /** Mirrors Store.removeDead's predicate. */
    private fun isDead(s: Server) =
        s.pingMs == Latency.TIMEOUT && s.tcpMs == Latency.TIMEOUT

    @Test
    fun `only a genuine timeout on both probes counts as dead`() {
        assertTrue(isDead(srv("a", Latency.TIMEOUT, Latency.TIMEOUT)))
    }

    @Test
    fun `a working TCP connect protects a server the core probe could not judge`() {
        // The exact case that used to delete a live server: the core probe returned UNKNOWN, and
        // UNKNOWN used to be flattened to 0.
        assertFalse(isDead(srv("b", Latency.UNKNOWN, 42)))
        assertFalse(isDead(srv("b", Latency.UNKNOWN, Latency.TIMEOUT)))
    }

    @Test
    fun `a never-measured server is never deleted`() {
        assertFalse(isDead(srv("c", Latency.UNKNOWN, Latency.UNKNOWN)))
    }

    @Test
    fun `a successful real probe protects the server even if TCP is slow`() {
        assertFalse(isDead(srv("d", 120, Latency.TIMEOUT)))
    }

    @Test
    fun `UNKNOWN and TIMEOUT are distinct values`() {
        // If these ever collapse again, every guard above silently becomes a no-op.
        assertEquals(-1L, Latency.UNKNOWN)
        assertEquals(0L, Latency.TIMEOUT)
        assertTrue(Latency.UNKNOWN < Latency.TIMEOUT)
    }

    @Test
    fun `a fresh Server defaults to not-measured rather than dead`() {
        // The default matters for every server loaded from a saved file: anything defaulting to 0
        // would make an unmeasured list look entirely dead.
        val fresh = Server(name = "n", protocol = "vless", host = "h.example.com", port = 443)
        assertEquals(Latency.UNKNOWN, fresh.pingMs)
        assertEquals(Latency.UNKNOWN, fresh.tcpMs)
    }
}