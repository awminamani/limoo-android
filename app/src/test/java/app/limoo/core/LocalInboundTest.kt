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
 * An unauthenticated SOCKS/HTTP inbound is a hole, not a feature:
 *
 *  - any app on the phone can connect to the tunnel and use the user's connection as its own exit,
 *    bypassing the per-app rules the rest of the app is built around;
 *  - any app can probe 10808/10809 and detect that a proxy tool is installed, which is a real risk for
 *    the users this app targets;
 *  - if another proxy app already holds the port, the core refuses to start and nothing connects.
 *
 * In VPN mode the tun IS the tunnel, so those ports serve nothing. Proxy-only mode keeps them, because
 * that mode exists to serve them - with nothing to configure, the app would have no purpose.
 *
 * LAN sharing is deliberately NOT covered here: it is unchanged by this change.
 */
class LocalInboundTest {

    private val server = Server(
        name = "t", protocol = "vless", host = "example.com", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", network = "tcp", security = "tls",
    )

    private fun tags(json: String): Set<String> =
        Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray
            .map { it.jsonObject["tag"]!!.jsonPrimitive.content }.toSet()

    @Test
    fun `vpn mode opens no listening port by default`() {
        val tags = tags(XrayConfigBuilder.build(server, AppSettings(), tun = true))
        assertFalse("socks must not be exposed in VPN mode", "socks" in tags)
        assertFalse("http must not be exposed in VPN mode", "http" in tags)
        assertTrue("the tun itself must still be there", "tun" in tags)
    }

    @Test
    fun `vpn mode opens them when the user explicitly asks`() {
        val st = AppSettings(localProxyPorts = true)
        val tags = tags(XrayConfigBuilder.build(server, st, tun = true))
        assertTrue("socks", "socks" in tags)
        assertTrue("http", "http" in tags)
    }

    @Test
    fun `proxy-only mode always opens them`() {
        // In this mode they ARE the product. Gating them here would leave the app with nothing to do.
        val tags = tags(XrayConfigBuilder.build(server, AppSettings(), tun = false))
        assertTrue("socks", "socks" in tags)
        assertTrue("http", "http" in tags)
        assertFalse("no tun in proxy mode", "tun" in tags)
    }

    @Test
    fun `allowLan does not by itself re-expose the ports in VPN mode`() {
        // LAN sharing is unchanged as a SETTING, but it cannot be what silently reopens a listening
        // port in VPN mode - the gate is the port existing at all, not where it listens.
        val tags = tags(XrayConfigBuilder.build(server, AppSettings(allowLan = true), tun = true))
        assertFalse("socks", "socks" in tags)
        assertFalse("http", "http" in tags)
    }

    @Test
    fun `a probe never binds a port regardless of the setting`() {
        // A probe must not touch the ports the running core owns - that was the original reason for the
        // probe shape, and the gate must not weaken it.
        val probe = Json.parseToJsonElement(XrayConfigBuilder.buildProbe(server, AppSettings())).jsonObject
        assertEquals(0, probe["inbounds"]!!.jsonArray.size)
    }

    @Test
    fun `the new setting defaults to off`() {
        // Persisted settings carry a default, so existing installs must read as OFF without a migration.
        assertFalse(AppSettings().localProxyPorts)
    }
}