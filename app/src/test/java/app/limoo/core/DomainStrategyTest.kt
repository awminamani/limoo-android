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
        // This is the regression in one assertion: the shipped default is IPIfNonMatch.
        val cfg = Json.parseToJsonElement(XrayConfigBuilder.build(server, AppSettings())).jsonObject
        assertEquals("IPIfNonMatch", cfg["routing"]!!.jsonObject["domainStrategy"]!!.jsonPrimitive.content)
        val direct = cfg["outbounds"]!!.jsonArray.map { it.jsonObject }
            .single { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertTrue(
            "freedom must not receive IPIfNonMatch",
            !direct["settings"]!!.jsonObject.containsKey("domainStrategy"),
        )
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
        assertEquals(2, direct.size)
        assertTrue(direct.containsKey("settings"))
        assertEquals("freedom", direct["protocol"]!!.jsonPrimitive.content)
    }
}