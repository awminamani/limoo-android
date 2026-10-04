package app.limoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A subscription URL is a bearer token. Anyone holding one can read the provider's entire server list,
 * and URLs end up in logs for reasons that have nothing to do with the secret: a config parse error
 * quotes the config back at you, a stack trace carries whatever was on the stack.
 *
 * These tests are the only thing standing between a provider token and `last_crash.txt`.
 */
class RedactTest {

    private val sampleConfig = """
        {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4",
        "port":443,"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},
        "streamSettings":{"realitySettings":{"publicKey":"BRIfLDlGU2BteoeUoa67yNXi7_wJFiMwPUpXZHF-i5g",
        "shortId":"0123456789abcdef","serverName":"www.example.com"}}}]}
    """.trimIndent()

    @Test
    fun `the user uuid is redacted`() {
        val out = Redact.config(sampleConfig)
        assertFalse("uuid leaked", out.contains("11111111-2222-3333-4444-555555555555"))
    }

    @Test
    fun `the REALITY public key and short id are redacted`() {
        val out = Redact.config(sampleConfig)
        assertFalse("public key leaked", out.contains("BRIfLDlGU2BteoeUoa67yNXi7_wJFiMwPUpXZHF-i5g"))
        assertFalse("short id leaked", out.contains("0123456789abcdef"))
    }

    @Test
    fun `addresses are kept by default and masked in private mode`() {
        // Over-redacting costs nothing; under-redacting leaks. So the DEFAULT keeps a bare address (useful
        // for diagnosis) and private mode masks it. The two secrets above are masked either way.
        val normal = Redact.config(sampleConfig, privateMode = false)
        assertTrue("address should survive outside private mode", normal.contains("1.2.3.4"))

        val priv = Redact.config(sampleConfig, privateMode = true)
        assertFalse("address leaked in private mode", priv.contains("1.2.3.4"))
        assertFalse("sni leaked in private mode", priv.contains("www.example.com"))
    }

    @Test
    fun `the tag and protocol survive so the output is still diagnosable`() {
        val out = Redact.config(sampleConfig)
        assertTrue(out.contains("\"tag\":\"proxy\""))
        assertTrue(out.contains("vless"))
        assertTrue(out.contains("443"))
    }

    @Test
    fun `a password inside a nested object is redacted`() {
        val cfg = """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"h","password":"hunter2"}]}}]}"""
        assertFalse(Redact.config(cfg).contains("hunter2"))
    }

    // ---- subscription URLs ----

    @Test
    fun `a subscription token in the path is masked`() {
        val out = Redact.subscriptionUrl("https://provider.example/sub/abcdef123456")
        assertFalse(out.contains("abcdef123456"))
        assertTrue("host must survive so the line is useful", out.contains("provider.example"))
    }

    @Test
    fun `a token in the query is masked`() {
        val out = Redact.subscriptionUrl("https://provider.example/sub?token=SECRETVALUE")
        assertFalse(out.contains("SECRETVALUE"))
    }

    @Test
    fun `an unparseable url is replaced wholesale`() {
        // A malformed string here is more likely to be a pasted secret than a typo, so it is not passed
        // through.
        assertEquals("***", Redact.subscriptionUrl("ht!tp://%%%"))
    }

    @Test
    fun `a short path segment is not treated as a token`() {
        // "/" is one character, not a secret. Masking it would destroy the shape of every plain URL.
        val out = Redact.subscriptionUrl("https://provider.example/")
        assertTrue(out.contains("provider.example"))
    }

    @Test
    fun `a proxy link pasted into a message is removed entirely`() {
        // vless://... carries the uuid in the fragment and the host in the authority; there is no safe
        // partial form, so the whole token goes.
        val msg = Redact.message("failed to import vless://uuid@1.2.3.4:443?type=tcp#Name")
        assertFalse(msg.contains("1.2.3.4"))
        assertFalse(msg.contains("vless://"))
        assertTrue("the surrounding text must survive", msg.startsWith("failed to import"))
    }

    @Test
    fun `an encrypted prefs blob is masked in a message`() {
        val msg = Redact.message("value was enc1:QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY=")
        assertFalse(msg.contains("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY"))
    }

    @Test
    fun `an ordinary message with no secret is untouched`() {
        val msg = "core failed to build outbound config with tag direct"
        assertEquals(msg, Redact.message(msg))
    }

    @Test
    fun `a url inside a longer message is masked but the sentence survives`() {
        val msg = Redact.message("could not reach https://provider.example/sub/SECRETTOKENHERE today")
        assertFalse(msg.contains("SECRETTOKENHERE"))
        assertTrue(msg.contains("could not reach"))
        assertTrue(msg.contains("today"))
    }
}
