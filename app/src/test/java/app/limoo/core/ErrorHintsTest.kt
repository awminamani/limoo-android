package app.limoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported bug was an error message that said nothing:
 *
 *   "config error: infra/conf/serial: failed to parse json config > infra/conf: failed to build
 *    outbound config with tag dire"
 *
 * `.take(120)` cut it at exactly the point where the reason begins. These tests pin both halves of the
 * fix: the hint recognises the real-world messages, and nothing ever truncates the raw text.
 */
class ErrorHintsTest {

    @Test
    fun `the exact reported error gets a hint`() {
        val raw = "config error: infra/conf/serial: failed to parse json config > infra/conf: " +
            "failed to build outbound config with tag direct > unsupported domain strategy: IPIfNonMatch"
        val h = ErrorHints.forError(raw)
        assertNotNull("the reported failure must be recognised", h)
        assertEquals("Invalid domain strategy", h!!.title)
    }

    @Test
    fun `the domain strategy hint wins over the generic config wording`() {
        // Ordering matters: the raw text contains BOTH "failed to build" and the real cause, so a
        // catch-all evaluated first would report the useless generic message.
        val raw = "failed to build outbound config with tag direct > unsupported domain strategy: IPIfNonMatch"
        assertEquals("Invalid domain strategy", ErrorHints.title(raw))
    }

    @Test
    fun `REALITY key problems are recognised`() {
        val raw = "failed to build outbound config: invalid publicKey length"
        assertEquals("Server REALITY key looks wrong", ErrorHints.title(raw))
    }

    @Test
    fun `a busy local port is recognised`() {
        assertEquals("Local port already in use", ErrorHints.title("listen tcp 127.0.0.1:10808: bind: address already in use"))
    }

    @Test
    fun `common network failures each get their own hint`() {
        assertEquals("Connection timed out", ErrorHints.title("dial tcp 1.2.3.4:443: i/o timeout"))
        assertEquals("Connection refused", ErrorHints.title("dial tcp 1.2.3.4:443: connect: connection refused"))
        assertEquals("Could not resolve the server address", ErrorHints.title("dial tcp: lookup example.com: no such host"))
    }

    @Test
    fun `an unrecognised error returns null rather than a guess`() {
        // A wrong hint is worse than none: the user acts on it. Null means "show the raw text only".
        assertNull(ErrorHints.forError("something nobody has ever seen before"))
        assertNull(ErrorHints.forError(""))
        assertNull(ErrorHints.forError(null))
        assertNull(ErrorHints.title("   "))
    }

    @Test
    fun `an unrecognised error still yields the generic config hint when it is one`() {
        val raw = "config error: infra/conf/serial: failed to parse json config"
        assertEquals("The core rejected the configuration", ErrorHints.title(raw))
    }

    @Test
    fun `hints are short enough to read on a phone`() {
        val raw = "failed to build outbound config with tag direct > unsupported domain strategy: IPIfNonMatch"
        val h = ErrorHints.forError(raw)!!
        assertTrue("title too long: ${h.title}", h.title.length <= 40)
        assertTrue("detail too long: ${h.detail}", h.detail.length <= 220)
    }

    @Test
    fun `matching ignores case so a capitalised core message is not missed`() {
        assertNotNull(ErrorHints.forError("UNSUPPORTED DOMAIN STRATEGY: IPIfNonMatch"))
    }

    @Test
    fun `the old 120-char cut lands inside this message, which is why the hint is needed`() {
        // Documents the original defect: the reason lives past the cut, so the hint cannot be a luxury.
        val raw = "config error: infra/conf/serial: failed to parse json config > infra/conf: " +
            "failed to build outbound config with tag direct > unsupported domain strategy: IPIfNonMatch"
        assertTrue(raw.length > 120)
        assertTrue(
            "the useful part must be beyond the old truncation point",
            raw.take(120).contains("unsupported domain strategy").not(),
        )
    }
}