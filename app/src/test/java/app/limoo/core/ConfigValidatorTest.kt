package app.limoo.core

import app.limoo.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests encode the rules the CORE enforced, captured from running `xray run -test` over the
 * generated matrix. Each comment quotes the core's own message, because the message is the evidence -
 * a validator written from documentation or memory would look identical and be wrong in the same way.
 *
 * The cross-check that matters is at the bottom: every combination this file calls valid is one that
 * the matrix confirmed the core accepts.
 */
class ConfigValidatorTest {

    private fun srv(
        protocol: String = "vless", network: String = "tcp", security: String = "tls",
        uuid: String = "11111111-1111-1111-1111-111111111111",
        method: String = "", flow: String = "",
        host: String = "example.com", port: Int = 443,
        sni: String = "example.com",
        pbk: String = "BRIfLDlGU2BteoeUoa67yNXi7_wJFiMwPUpXZHF-i5g",
        sid: String = "0123456789abcdef",
        path: String = "/ws", serviceName: String = "svc",
    ) = Server(
        name = "t", protocol = protocol, host = host, port = port, uuid = uuid, method = method,
        flow = flow, network = network, security = security, sni = sni, pbk = pbk, sid = sid,
        path = path, serviceName = serviceName,
    )

    // ---- the four rules the core itself taught us ----

    @Test
    fun `vless without encryption is rejected, per the core's prohibition`() {
        assertTrue(ConfigValidator.validate(srv(security = "none")).any { it.contains("needs TLS or REALITY") })
        assertTrue(ConfigValidator.isValid(srv(security = "tls")))
        assertTrue(ConfigValidator.isValid(srv(security = "reality")))
    }

    @Test
    fun `trojan without TLS is rejected, per the core's prohibition`() {
        assertTrue(ConfigValidator.validate(srv(protocol = "trojan", security = "none")).any { it.contains("needs TLS or REALITY") })
        assertTrue(ConfigValidator.isValid(srv(protocol = "trojan", security = "tls")))
    }

    @Test
    fun `the encryption rule does not apply to a literal address`() {
        // The core's own exception: "...unless the server address is a private IP or domain".
        assertTrue(ConfigValidator.isValid(srv(security = "none", host = "192.168.1.10")))
        assertTrue(ConfigValidator.isValid(srv(security = "none", host = "[::1]")))
    }

    @Test
    fun `REALITY over an unsupported transport is rejected, per the core`() {
        // This exact combination was rejected by xray v26.9.30 during the matrix run.
        for (network in listOf("ws", "httpupgrade")) {
            val problems = ConfigValidator.validate(srv(network = network, security = "reality"))
            assertTrue("$network should be rejected", problems.any { it.contains("only works over TCP, XHTTP or gRPC") })
        }
        for (network in listOf("tcp", "xhttp", "grpc")) {
            assertTrue("$network should be accepted", ConfigValidator.isValid(srv(network = network, security = "reality")))
        }
    }

    @Test
    fun `a malformed REALITY public key is rejected, per the core`() {
        // REALITY's `password` field IS the public key: 43 base64url chars = 32 bytes.
        assertTrue(ConfigValidator.validate(srv(pbk = "")).any { it.contains("needs a public key") })
        assertTrue(ConfigValidator.validate(srv(pbk = "tooshort")).any { it.contains("43 base64url") })
        // 44 chars, decodes, but not 43 - the shape the matrix run rejected.
        assertTrue(ConfigValidator.validate(srv(pbk = "gI0GAqCZFpT4r9nP0oS3hVvXyZaBcDeFgHiJkLmNoPqR")).any { it.contains("43 base64url") })
        assertTrue(ConfigValidator.isValid(srv(pbk = "BRIfLDlGU2BteoeUoa67yNXi7_wJFiMwPUpXZHF-i5g")))
    }

    @Test
    fun `a bad REALITY short ID is rejected`() {
        assertTrue(ConfigValidator.validate(srv(sid = "xyz")).any { it.contains("short ID") })
        assertTrue(ConfigValidator.validate(srv(sid = "012")).any { it.contains("even length") })
        assertTrue(ConfigValidator.validate(srv(sid = "0123456789abcdef01")).any { it.contains("at most 16") })
        assertTrue(ConfigValidator.isValid(srv(sid = "")))          // empty is allowed
        assertTrue(ConfigValidator.isValid(srv(sid = "0123456789abcdef")))
    }

    @Test
    fun `shadowsocks without a cipher is rejected`() {
        assertTrue(ConfigValidator.validate(srv(protocol = "shadowsocks", method = "")).any { it.contains("encryption method") })
        assertTrue(ConfigValidator.isValid(srv(protocol = "shadowsocks", method = "aes-256-gcm")))
        assertTrue(ConfigValidator.validate(srv(protocol = "shadowsocks", method = "rot13")).any { it.contains("not supported") })
    }

    // ---- ordinary validation ----

    @Test
    fun `a blank host or an impossible port is caught`() {
        assertTrue(ConfigValidator.validate(srv(host = " ")).any { it.contains("address is empty") })
        assertTrue(ConfigValidator.validate(srv(port = 0)).any { it.contains("between 1 and 65535") })
        assertTrue(ConfigValidator.validate(srv(port = 70000)).any { it.contains("between 1 and 65535") })
    }

    @Test
    fun `a malformed UUID is caught for vless and vmess`() {
        assertTrue(ConfigValidator.validate(srv(uuid = "not-a-uuid")).any { it.contains("not a valid UUID") })
        assertTrue(ConfigValidator.isValid(srv(uuid = "11111111-1111-1111-1111-111111111111")))
        assertTrue(ConfigValidator.validate(srv(protocol = "vmess", uuid = "")).any { it.contains("not a valid UUID") })
    }

    @Test
    fun `trojan and shadowsocks want a password, not a UUID`() {
        assertTrue(ConfigValidator.validate(srv(protocol = "trojan", uuid = "")).any { it.contains("password") })
        assertTrue(ConfigValidator.isValid(srv(protocol = "trojan", uuid = "hunter2")))
        assertTrue(ConfigValidator.isValid(srv(protocol = "trojan", uuid = "11111111-1111-1111-1111-111111111111")))
    }

    @Test
    fun `an unknown protocol is reported and stops further checks`() {
        val problems = ConfigValidator.validate(srv(protocol = "wireguard"))
        assertTrue(problems.any { it.contains("Unknown protocol") })
        // Only one problem: nothing below a protocol check is meaningful.
        assertEquals(1, problems.size)
    }

    @Test
    fun `a path that does not start with a slash is caught`() {
        assertTrue(ConfigValidator.validate(srv(network = "ws", path = "ws")).any { it.contains("must start with") })
        assertTrue(ConfigValidator.isValid(srv(network = "ws", path = "/ws")))
        assertTrue(ConfigValidator.isValid(srv(network = "ws", path = "")))    // empty falls back to "/"
    }

    @Test
    fun `flow is only valid on vless over tcp or xhttp with encryption`() {
        assertTrue(ConfigValidator.isValid(srv(flow = "xtls-rprx-vision")))
        assertTrue(ConfigValidator.validate(srv(protocol = "trojan", security = "tls", flow = "x")).any { it.contains("Only VLESS") })
        assertTrue(ConfigValidator.validate(srv(network = "ws", flow = "x")).any { it.contains("not supported over ws") })
        assertTrue(ConfigValidator.validate(srv(security = "none", host = "1.2.3.4", flow = "x")).any { it.contains("needs TLS") })
    }

    @Test
    fun `every problem is reported, not just the first`() {
        // An import preview that reveals one problem at a time makes the user fix a link that is broken
        // in four ways, so validate() returns them all.
        val problems = ConfigValidator.validate(
            srv(protocol = "shadowsocks", network = "ws", security = "reality", method = "", pbk = "", path = "bad")
        )
        assertTrue("expected several problems, got $problems", problems.size >= 3)
    }

    /**
     * The cross-check. Every combination called valid here is one the matrix run confirmed Xray v26.9.30
     * accepts (run 37236902034: 84 configs, 0 rejected). If this test and the matrix ever disagree, the
     * matrix is right - it asked the core, this file only encodes what the core said earlier.
     */
    @Test
    fun `the combinations the matrix accepted are the ones the validator accepts`() {
        val combos = listOf(
            Triple("vless", "tcp", "tls"), Triple("vless", "tcp", "reality"),
            Triple("vless", "ws", "tls"), Triple("vless", "grpc", "reality"),
            Triple("vless", "xhttp", "reality"), Triple("vless", "httpupgrade", "tls"),
            Triple("vmess", "tcp", "none"), Triple("vmess", "ws", "tls"),
            Triple("trojan", "ws", "tls"), Triple("trojan", "tcp", "reality"),
            Triple("shadowsocks", "tcp", "none"), Triple("shadowsocks", "grpc", "tls"),
        )
        for ((p, n, sec) in combos) {
            val s = srv(
                protocol = p, network = n, security = sec,
                method = if (p == "shadowsocks") "aes-256-gcm" else "",
            )
            val problems = ConfigValidator.validate(s)
            assertFalse(
                "matrix accepted $p/$n/$sec but the validator rejects it: $problems",
                problems.isNotEmpty(),
            )
        }
    }

    @Test
    fun `an ordinary imported vless reality link is valid`() {
        // The exact shape of the server in the original bug report.
        assertTrue(ConfigValidator.isValid(srv(protocol = "vless", network = "tcp", security = "reality")))
    }
}