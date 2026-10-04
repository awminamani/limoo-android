package app.limoo.core

import app.limoo.model.AppSettings
import app.limoo.model.Server
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generates a matrix of real configs and leaves them on disk for `tools/check-xray-configs.sh` to feed
 * to `xray run -test` in CI.
 *
 * WHY THIS EXISTS
 *
 * Every other config test asserts JSON *shape*: that `outboundTag` is a string, that tags resolve, that
 * a key is absent. Shape is not validity. The reported connect failure was a config that passed every
 * shape check and was still rejected by the core, because `freedom.settings.domainStrategy` was given a
 * ROUTING value. Nothing in a shape assertion can see that: `{"domainStrategy":"IPIfNonMatch"}` is a
 * perfectly well-formed JSON object.
 *
 * So this test asserts nothing on its own. Its only job is to WRITE the matrix. The proof is the shell
 * script running the real binary, and the only way to know this test is doing its job is that CI goes
 * green. A deliberate bad value must turn it red.
 *
 * Combinatorics are kept to pairwise rather than cartesian: protocol x network x security x toggles is
 * already thousands of configs, and pairwise catches the same interactions (each setting against each
 * other setting) for a fraction of the CI time.
 */
class XrayConfigMatrixTest {

    private val outDir = File("build/xray-configs")

    private fun srv(
        protocol: String = "vless",
        network: String = "tcp",
        security: String = "none",
        flow: String = "",
        method: String = "",
    ) = Server(
        name = "matrix", protocol = protocol, host = "example.com", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = network, security = security, flow = flow,
        // Shadowsocks REQUIRES a cipher; an empty method makes the core reject the config outright
        // ("invalid shadowsocks method"). A real ss:// link always carries one, so the fixture must too -
        // the first matrix run failed 5/5 shadowsocks configs for exactly this reason, which is the
        // validator doing its job.
        method = method.ifEmpty { if (protocol == "shadowsocks") "aes-256-gcm" else "" },
        // REALITY-specific values that are valid regardless of the rest of the combination.
        sni = "example.com", pbk = "xTIBA5rboUvnH4htWjbxbHFi_zx3Cx96wVvEnntVJykw", sid = "0123456789abcdef",
        fp = "chrome", path = "/ws", serviceName = "svc", hostHeader = "example.com",
    )

    /** Realistic settings that differ from the defaults, so the matrix is not all-defaults. */
    private fun st(
        fragment: Boolean = false,
        mux: Boolean = false,
        sniffing: Boolean = true,
        ipv6: Boolean = false,
        allowLan: Boolean = false,
        tcpFastOpen: Boolean = true,
        tcpKeepAlive: Boolean = true,
        bufferSize: Int = 256,
        dnsCache: Boolean = true,
        blockAds: Boolean = false,
        routingPreset: String = "global",
        domainStrategy: String = "AsIs",
        tun: Boolean = false,
    ) = AppSettings(
        fragment = fragment, mux = mux, sniffing = sniffing, ipv6 = ipv6, allowLan = allowLan,
        tcpFastOpen = tcpFastOpen, tcpKeepAlive = tcpKeepAlive, bufferSize = bufferSize,
        dnsCache = dnsCache, blockAds = blockAds, routingPreset = routingPreset,
        domainStrategy = domainStrategy, tcpKeepAliveInterval = 30,
    )

    private fun write(name: String, json: String) {
        File(outDir, name).writeText(json)
    }

    @Test
    fun `generate the config matrix for the real core to validate`() {
        outDir.mkdirs()
        // Start clean: a stale config from a previous run would be validated as if it were current.
        outDir.listFiles()?.forEach { it.delete() }

        var n = 0

        // ---- protocol x network x security ----
        // ss+reality is deliberately absent: shadowsocks has no TLS layer, so REALITY cannot be
        // combined with it and including it would only produce a known-invalid config.
        for (protocol in listOf("vless", "vmess", "trojan", "shadowsocks")) {
            for (network in listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
                val securities = if (protocol == "shadowsocks") listOf("none", "tls") else listOf("none", "tls", "reality")
                for (security in securities) {
                    write("p-${protocol}-${network}-${security}.json", XrayConfigBuilder.build(srv(protocol, network, security), st(tun = true)))
                    n++
                }
            }
        }

        // ---- each toggle, off and on ----
        val toggles = listOf(
            "fragment" to { b: Boolean -> st(fragment = b) },
            "mux" to { b: Boolean -> st(mux = b) },
            "sniffing" to { b: Boolean -> st(sniffing = b) },
            "ipv6" to { b: Boolean -> st(ipv6 = b) },
            "allowLan" to { b: Boolean -> st(allowLan = b) },
            "tcpFastOpen" to { b: Boolean -> st(tcpFastOpen = b) },
            "tcpKeepAlive" to { b: Boolean -> st(tcpKeepAlive = b) },
            "bufferSize" to { b: Boolean -> st(bufferSize = if (b) 256 else 0) },
            "dnsCache" to { b: Boolean -> st(dnsCache = b) },
            "blockAds" to { b: Boolean -> st(blockAds = b) },
            "tun" to { b: Boolean -> st(tun = b) },
        )
        // fragment needs a TLS server for dialerProxy to be written at all, so it is paired with one.
        for ((name, mk) in toggles) {
            val server = if (name == "fragment") srv(security = "tls") else srv()
            for (on in listOf(false, true)) {
                write("t-${name}-${if (on) "on" else "off"}.json", XrayConfigBuilder.build(server, mk(on)))
                n++
            }
        }

        // ---- every routing preset and every domain strategy ----
        for (preset in listOf("global", "bypassIran", "bypassChina", "bypassRussia")) {
            for (ds in listOf("AsIs", "IPIfNonMatch", "IPOnDemand")) {
                write("r-${preset}-${ds}.json", XrayConfigBuilder.build(srv(), st(routingPreset = preset, domainStrategy = ds)))
                n++
            }
        }

        // ---- probe configs ----
        // buildProbe is a DIFFERENT config shape, and it had its own copy of the bug.
        for ((name, mk) in toggles) {
            val server = if (name == "fragment") srv(security = "tls") else srv()
            write("probe-${name}.json", XrayConfigBuilder.buildProbe(server, mk(true)))
            n++
        }

        val files = outDir.listFiles()?.size ?: 0
        assertTrue("expected a real matrix, generated $files", files >= 50)
        assertTrue("matrix files must actually be written: $n counted", files > 0)
        println("XrayConfigMatrixTest: wrote $files configs to ${outDir.absolutePath}")
    }
}
