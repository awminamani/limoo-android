package app.limoo.core

import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `routing.domainStrategy` and the freedom outbound's own `domainStrategy` are different settings that
 * accept different values, and both used to be written from the same field (`st.domainStrategy`,
 * default "IPIfNonMatch").
 *
 * Freedom only accepts AsIs / UseIP / UseIPv4 / UseIPv6, so every config built with the default
 * settings was rejected by recent Xray cores with "unsupported domain strategy" while BUILDING the
 * outbound - before a single packet was sent. That is why Home showed a connect error on a server
 * whose TCP latency worked, and it applied to every server and every protocol, not just the one in
 * the report.
 *
 * These tests pin the class of mistake: no value that routing accepts may ever reach the freedom
 * outbound, and no garbage may reach either.
 */
class DomainStrategyTest {
    // Both sets are spelled out here rather than imported, so the test fails if someone widens the
    // production whitelist without meaning to.
    private val ROUTING_OK = setOf("AsIs", "IPIfNonMatch", "IPOnDemand")
    private val FREEDOM_OK = setOf("AsIs", "UseIP", "UseIPv4", "UseIPv6")

    private val server = Server(
        name = "t", protocol = "vless", host = "example.com", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", network = "tcp", security = "reality",
    )

    /** Every strategy a user can pick, plus values no UI can produce. */
    private val strategies = listOf("AsIs", "IPIfNonMatch", "IPOnDemand", "UseIP", "x", "")

    private fun outbounds(json: String) =
        Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }

    private fun assertStrategyRulesHold(json: String, where: String) {
        val obs = outbounds(json)

        val direct = obs.single { it["tag"]!!.jsonPrimitive.content == "direct" }
        require(direct["protocol"]!!.jsonPrimitive.content == "freedom") { "$where: direct is not freedom" }
        // Absent is the correct and always-valid state (= AsIs).
        val directDs = direct["settings"]?.jsonObject?.get("domainStrategy")
        if (directDs != null) {
            require(directDs is JsonPrimitive) { "$where: freedom domainStrategy must be a string" }
            assertTrue(
                "$where: freedom got routing-only domainStrategy '${directDs.jsonPrimitive.content}'",
                directDs.jsonPrimitive.content in FREEDOM_OK,
            )
        }

        val routing = Json.parseToJsonElement(json).jsonObject["routing"]
        if (routing != null) {
            val rds = routing.jsonObject["domainStrategy"]
            require(rds is JsonPrimitive) { "$where: routing domainStrategy must be a string" }
            assertTrue(
                "$where: routing got '${rds.jsonPrimitive.content}'",
                rds.jsonPrimitive.content in ROUTING_OK,
            )
        }
    }

    @Test
    fun `build never puts a routing strategy into the freedom outbound`() {
        strategies.forEach { s ->
            assertStrategyRulesHold(XrayConfigBuilder.build(server, AppSettings(domainStrategy = s)), "build($s)")
        }
    }

    @Test
    fun `buildProbe never puts a routing strategy into the freedom outbound`() {
        strategies.forEach { s ->
            assertStrategyRulesHold(XrayConfigBuilder.buildProbe(server, AppSettings(domainStrategy = s)), "buildProbe($s)")
        }
    }

    @Test
    fun `all three UI strategies connect and are kept for routing`() {
        listOf("AsIs", "IPIfNonMatch", "IPOnDemand").forEach { s ->
            val cfg = Json.parseToJsonElement(XrayConfigBuilder.build(server, AppSettings(domainStrategy = s))).jsonObject
            assertEquals(s, cfg["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
            assertStrategyRulesHold(XrayConfigBuilder.build(server, AppSettings(domainStrategy = s)), "ui($s)")
        }
    }

    @Test
    fun `the default settings produce a config the core can build`() {
        // The shipped default is AsIs: the one value that is valid for BOTH routing and freedom, so a
        // user's stored setting can never be rejected by the core at config-build time.
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.build(server, AppSettings())).jsonObject
        assertEquals("AsIs", cfg["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
        val direct = cfg["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertTrue(
            "freedom must not receive a domain strategy at all",
            !direct["settings"]!!.jsonObject.containsKey("domainStrategy"),
        )
    }

    /**
     * Settings are persisted with encodeDefaults = true, so an app that already stored
     * "IPIfNonMatch" keeps that value after upgrading and never sees the new AsIs default. The fix
     * therefore cannot rely on the default - it has to hold for every stored value. This is that case.
     */
    @Test
    fun `a settings blob written before the default changed still builds`() {
        val stored = AppSettings(domainStrategy = "IPIfNonMatch")   // what v0.6.0 wrote to disk
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.build(server, stored)).jsonObject
        assertEquals("IPIfNonMatch", cfg["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
        val direct = cfg["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertTrue(
            "freedom must not receive the stored routing strategy",
            !direct["settings"]!!.jsonObject.containsKey("domainStrategy"),
        )
        assertStrategyRulesHold(XrayConfigBuilder.build(server, stored), "stored-blob")
        assertStrategyRulesHold(XrayConfigBuilder.buildProbe(server, stored), "stored-blob/probe")
    }

    @Test
    fun `an unknown strategy falls back to AsIs for routing instead of reaching the core`() {
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.build(server, AppSettings(domainStrategy = "UseIPv4v6"))).jsonObject
        assertEquals("AsIs", cfg["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
    }

    /**
     * The freedom value is omitted entirely, which is why this test also proves the direct outbound
     * still exists and still has a settings object - a fix that dropped the key by deleting the
     * outbound would pass a naive "no bad value" check.
     */
    @Test
    fun `direct outbound keeps its shape while omitting the strategy`() {
        val obs = outbounds(XrayConfigBuilder.build(server, AppSettings()))
        val direct = obs.single { it["tag"]!!.jsonPrimitive.content == "direct" }
        // Exactly these three keys. Asserting the key SET rather than a count is what makes this a
        // real guard: the point is that domainStrategy is gone while tag/protocol/settings survive, so
        // a fix cannot quietly pass by deleting the outbound or by dropping its settings object.
        assertEquals(setOf("tag", "protocol", "settings"), direct.keys)
        assertEquals("freedom", direct["protocol"]!!.jsonPrimitive.content)
        assertEquals(0, direct["settings"]!!.jsonObject.size)   // present, and empty
    }
}