package app.limoo.core

import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 12: geo data must not decide whether the tunnel works.
 *
 * `GeoManager.ensure` downloads from GitHub and jsDelivr, and startVpn used to throw when it failed -
 * unconditionally. So a fresh install, or the "global" preset (which references no geo rules at all),
 * could not connect on exactly the constrained networks where those hosts are unreachable. Failing closed
 * turned "no routing data" into "no VPN".
 *
 * And item 7: the "IPv6 off" switch did not prevent IPv6. Without ::/0 in the tun, Android routed IPv6
 * around the tunnel on a dual-stack network, so IPv6-off meant "not tunnelled", which for a privacy
 * setting is the opposite of what it says.
 */
class GeoAndIpv6Test {

    private val server = Server(
        name = "t", protocol = "vless", host = "example.com", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", network = "tcp", security = "tls",
    )

    private fun rules(st: AppSettings, withGeoRules: Boolean = true) =
        Json.parseToJsonElement(XrayConfigBuilder.build(server, st, tun = true, withGeoRules = withGeoRules))
            .jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun text(json: String) = Json.parseToJsonElement(json).toString()

    // ---- item 12: geo is only required when actually referenced ----

    @Test
    fun `the global preset with default settings needs no geo data`() {
        // This is the fresh-install case: nothing references geosite:/geoip:, so a failed download must
        // not stop the tunnel.
        assertFalse(ConfigNeedsGeo.check(AppSettings()))
    }

    @Test
    fun `a geo-dependent preset does need the data files`() {
        for (preset in listOf("bypassIran", "bypassChina", "bypassRussia")) {
            assertTrue(preset, ConfigNeedsGeo.check(AppSettings(routingPreset = preset)))
        }
    }

    @Test
    fun `ad blocking needs the data files`() {
        assertTrue(ConfigNeedsGeo.check(AppSettings(blockAds = true)))
    }

    @Test
    fun `a custom rule referencing geosite needs the data files`() {
        assertTrue(ConfigNeedsGeo.check(AppSettings(proxyRules = "geosite:google")))
        assertTrue(ConfigNeedsGeo.check(AppSettings(directRules = "geosite:ir")))
    }

    @Test
    fun `geoip private alone needs no data file`() {
        // geoip:private is compiled into the core. Treating it as a file dependency would reintroduce the
        // exact bug this fixes, because the app emits it in EVERY config.
        assertFalse(ConfigNeedsGeo.check(AppSettings(proxyRules = "geoip:private")))
        assertFalse(ConfigNeedsGeo.check(AppSettings(directRules = "geoip:private")))
    }

    @Test
    fun `a plain domain or IP rule needs no data file`() {
        assertFalse(ConfigNeedsGeo.check(AppSettings(proxyRules = "domain:example.com\n1.2.3.0/24")))
    }

    @Test
    fun `withGeoRules false drops every geo reference so the core will accept it`() {
        // A config that still names geosite:category-ads-all when the .dat is missing is REJECTED by the
        // core, so keeping the rule would mean not connecting - the opposite of the intent.
        val st = AppSettings(blockAds = true, routingPreset = "bypassIran")
        val json = text(XrayConfigBuilder.build(server, st, tun = true, withGeoRules = false))
        assertFalse("geosite must be gone: $json", json.contains("geosite:category-ads-all"))
        assertFalse("preset geosite must be gone: $json", json.contains("geosite:category-ir"))
        assertFalse("preset geoip must be gone: $json", json.contains("geoip:ir"))
    }

    @Test
    fun `geoip private survives even with geo rules dropped`() {
        // It is built into the core, so dropping it would change behaviour for no reason.
        val kept = rules(AppSettings(), withGeoRules = false)
        assertTrue(
            "geoip:private must remain",
            kept.any { it["ip"]?.toString()?.contains("geoip:private") == true },
        )
    }

    @Test
    fun `the normal path is unchanged - geo rules are emitted by default`() {
        val json = text(XrayConfigBuilder.build(server, AppSettings(blockAds = true), tun = true))
        assertTrue(json.contains("geosite:category-ads-all"))
    }

    // ---- item 7: IPv6 off means blocked ----

    @Test
    fun `ipv6 off installs a rule that blocks it`() {
        val r = rules(AppSettings(ipv6 = false))
        assertTrue(
            "an ::/0 block must exist when ipv6 is off",
            r.any { it["outboundTag"]!!.jsonPrimitive.content == "block" && it["ip"]?.toString()?.contains("::/0") == true },
        )
    }

    @Test
    fun `ipv6 on does not block it`() {
        val r = rules(AppSettings(ipv6 = true))
        assertFalse(
            r.any { it["ip"]?.toString()?.contains("::/0") == true },
        )
    }

    @Test
    fun `the ipv6 block is first so nothing can capture it`() {
        // Routing rules are first-match-wins. If a user rule or a preset rule came first it could send IPv6
        // straight out of the tunnel, and the "IPv6 off" label would be a lie.
        val r = rules(AppSettings(ipv6 = false, routingPreset = "bypassIran"))
        val idx = r.indexOfFirst { it["ip"]?.toString()?.contains("::/0") == true }
        assertTrue("the ::/0 rule is missing entirely", idx >= 0)
        assertEquals("the ::/0 block must be the first rule", 0, idx)
    }

    @Test
    fun `the ipv6 block needs no geo file so it survives a geo-less connect`() {
        val r = rules(AppSettings(ipv6 = false), withGeoRules = false)
        assertTrue(
            "IPv6 must stay blocked even when geo data is unavailable",
            r.any { it["ip"]?.toString()?.contains("::/0") == true },
        )
    }

    @Test
    fun `dns asks for IPv4 answers when ipv6 is off`() {
        // Otherwise a resolver returns AAAA records, the block rule drops them, and the user sees a
        // mysterious timeout instead of a blocked connection.
        val dns = Json.parseToJsonElement(XrayConfigBuilder.build(server, AppSettings(ipv6 = false, dnsStrategy = "")))
            .jsonObject["dns"]!!.jsonObject
        assertEquals("UseIPv4", dns["queryStrategy"]!!.jsonPrimitive.content)
    }
}
