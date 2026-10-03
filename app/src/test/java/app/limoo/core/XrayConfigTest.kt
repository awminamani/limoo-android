package app.limoo.core

import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the generated Xray config against shapes the core rejects outright.
 *
 * The whole config is parsed before anything starts, so one field of the wrong type takes the entire
 * tunnel down and the user sees "config error: infra/conf/serial: failed to parse json" with no hint which
 * field was wrong. That already happened once: `outboundTag` was emitted as a JSON array of tags, but
 * `RoutingRule.OutboundTag` is a single string, and the core refused to build the routing table.
 *
 * These assertions are cheap and catch the class of mistake rather than the one instance.
 */
class XrayConfigTest {
    private val server = Server(
        name = "t", protocol = "vless", host = "example.com", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", network = "tcp", security = "tls",
    )

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `routing rules are structurally valid`() {
        val st = AppSettings(
            routingPreset = "bypassIran", blockAds = true,
            proxyRules = "geosite:google\n1.2.3.0/24",
            directRules = "domain:example.com",
            blockRules = "geosite:category-ads-all",
        )
        val config = parse(XrayConfigBuilder.build(server, st, tun = true))
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue("expected some routing rules", rules.isNotEmpty())
        rules.forEach { rule ->
            val tag = rule.jsonObject["outboundTag"]
            // require() carries a Kotlin contract, so it smart-casts; assertTrue() does not, and would
            // leave `tag` nullable for the next line.
            require(tag is JsonPrimitive) {
                "outboundTag must be a single string, not a list: ${rule.jsonObject}"
            }
            // Every tag must name an outbound that actually exists, or the core rejects the rule.
            assertTrue(
                "unknown outboundTag ${tag.jsonPrimitive}",
                tag.jsonPrimitive.content in setOf("proxy", "direct", "block", "fragment"),
            )
            // Only domain/ip matchers are allowed alongside type/outboundTag.
            rule.jsonObject.keys.forEach { k ->
                assertTrue("unexpected routing key '$k' in $rule", k in setOf("type", "domain", "ip", "outboundTag", "network", "port", "protocol", "inboundTag"))
            }
        }
    }

    @Test
    fun `every enabled rule uses a known outbound tag`() {
        // Fragment and block only exist as outbounds under their own settings; make sure the rules that
        // reference them are only emitted when the outbound exists.
        val st = AppSettings(fragment = false)
        val config = parse(XrayConfigBuilder.build(server, st, tun = true))
        val tags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("proxy outbound missing", tags.contains("proxy"))
        assertTrue("direct outbound missing", tags.contains("direct"))
        assertTrue("block outbound missing", tags.contains("block"))
        assertTrue("fragment must not appear when disabled", !tags.contains("fragment"))
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray
        rules.forEach { r ->
            val t = r.jsonObject["outboundTag"]!!.jsonPrimitive.content
            assertTrue("rule references '$t' but no such outbound exists", t in tags)
        }
    }

    @Test
    fun `tun enables endpoint independent nat and honours the setting`() {
        val on = parse(XrayConfigBuilder.build(server, AppSettings(endpointIndependentNat = true), tun = true))
        val off = parse(XrayConfigBuilder.build(server, AppSettings(endpointIndependentNat = false), tun = true))
        fun nat(c: Map<String, JsonElement>) =
            c["inbounds"]!!.jsonArray.first().jsonObject["settings"]!!.jsonObject["endpointIndependentNat"]!!.jsonPrimitive.content
        assertEquals("true", nat(on))
        assertEquals("false", nat(off))
    }

    @Test
    fun `sockopt omits a zero buffer size and carries fragment as dialerProxy`() {
        val off = parse(XrayConfigBuilder.build(server, AppSettings(fragment = false, bufferSize = 0)))
        val sock = off["outbounds"]!!.jsonArray.first().jsonObject["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertTrue("bufferSize 0 stalls the socket and must be omitted", "bufferSize" !in sock)
        assertTrue("dialerProxy must be absent when fragment is off", "dialerProxy" !in sock)

        val on = parse(XrayConfigBuilder.build(server, AppSettings(fragment = true, bufferSize = 512)))
        val sock2 = on["outbounds"]!!.jsonArray.first().jsonObject["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertEquals(512 * 1024, sock2["bufferSize"]!!.jsonPrimitive.content.toInt())
        assertEquals("fragment", sock2["dialerProxy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `probe config has no inbounds so it cannot collide with the running core`() {
        val probe = parse(XrayConfigBuilder.buildProbe(server, AppSettings()))
        assertEquals(0, probe["inbounds"]!!.jsonArray.size)
        assertEquals("none", probe["log"]!!.jsonObject["loglevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dns never combines positive ttl with a disabled cache`() {
        val st = AppSettings(dnsCache = false, dnsCacheTTL = 300)
        val dns = parse(XrayConfigBuilder.build(server, st))["dns"]!!.jsonObject
        assertEquals("true", dns["disableCache"]!!.jsonPrimitive.content)
        assertTrue("PositiveTTL with disableCache is rejected by the core", "cacheStrategy" !in dns)
    }
}