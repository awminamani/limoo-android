package app.limoo.core

import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.serialization.json.*

object XrayConfigBuilder {
    /**
     * Minimal config used only for a delay probe (the shape v2rayNG passes to measureOutboundDelay).
     * Deliberately has no inbounds - a probe must not bind the SOCKS/HTTP ports the running core owns -
     * and no routing rules, so it does not need geo data and cannot be rejected by a routing match.
     */
    fun buildProbe(s: Server, st: AppSettings): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "none") })
        put("inbounds", buildJsonArray { })
        put("outbounds", buildJsonArray {
            add(proxy(s, st))
            add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom") })
        })
    }.toString()

    fun build(s: Server, st: AppSettings, tun: Boolean = false): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", st.logLevel) })
        put("dns", buildJsonObject {
            put("servers", strs(listOf(st.remoteDns, st.directDns, "localhost")))
            put("queryStrategy", if (st.ipv6) "UseIP" else "UseIPv4")
        })
        put("inbounds", inbounds(st, tun))
        put("outbounds", buildJsonArray {
            add(proxy(s, st))
            add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom") })
            add(buildJsonObject { put("tag", "block"); put("protocol", "blackhole") })
            if (st.fragment) add(buildJsonObject {
                put("tag", "fragment"); put("protocol", "freedom")
                put("settings", buildJsonObject { put("fragment", buildJsonObject {
                    put("packets", st.fragmentPackets); put("length", st.fragmentLength); put("interval", st.fragmentInterval) }) })
            })
        })
        // Required for queryAllOutboundTrafficStats() to report anything. Without a declared stats object
        // the core keeps no per-outbound counters, so the live figures silently stay at zero and the
        // notification never updates. SystemStats is what the gRPC stats service exposes.
        put("stats", buildJsonObject {})
        put("policy", buildJsonObject {
            put("levels", buildJsonObject { put("0", buildJsonObject { put("statsUserUplink", true); put("statsUserDownlink", true) }) })
            put("system", buildJsonObject { put("statsInboundUplink", true); put("statsInboundDownlink", true)
                put("statsOutboundUplink", true); put("statsOutboundDownlink", true) })
        })
        put("routing", routing(st))
    }.toString()

    private fun strs(l: List<String>) = buildJsonArray { l.forEach { add(it.trim()) } }

    private fun sniff(st: AppSettings) = buildJsonObject {
        put("enabled", st.sniffing); put("destOverride", strs(listOf("http", "tls", "quic"))); put("routeOnly", true)
    }

    private fun inbounds(st: AppSettings, tun: Boolean) = buildJsonArray {
        if (tun) add(buildJsonObject {
            put("tag", "tun"); put("protocol", "tun")
            put("settings", buildJsonObject { put("name", "xray0"); put("MTU", st.mtu); put("userLevel", 0) })
            put("sniffing", sniff(st))
        })
        val listen = if (st.allowLan) "0.0.0.0" else "127.0.0.1"
        add(buildJsonObject {
            put("tag", "socks"); put("listen", listen); put("port", st.socksPort); put("protocol", "socks")
            put("settings", buildJsonObject { put("udp", true); put("auth", "noauth") }); put("sniffing", sniff(st))
        })
        add(buildJsonObject {
            put("tag", "http"); put("listen", listen); put("port", st.httpPort); put("protocol", "http"); put("sniffing", sniff(st))
        })
    }

    private fun proxy(s: Server, st: AppSettings) = buildJsonObject {
        put("tag", "proxy"); put("protocol", s.protocol)
        put("settings", when (s.protocol) {
            "vless" -> vnext(s, buildJsonObject { put("id", s.uuid); put("encryption", "none"); if (s.flow.isNotEmpty()) put("flow", s.flow) })
            "vmess" -> vnext(s, buildJsonObject { put("id", s.uuid); put("security", s.method.ifEmpty { "auto" }) })
            "trojan" -> servers(s, buildJsonObject { put("password", s.uuid) })
            else -> servers(s, buildJsonObject { put("method", s.method); put("password", s.uuid) })
        })
        put("streamSettings", stream(s, st))
        if (st.mux && s.flow.isEmpty() && s.network != "xhttp" && s.security != "reality")
            put("mux", buildJsonObject { put("enabled", true); put("concurrency", st.muxConcurrency) })
    }

    private fun vnext(s: Server, user: JsonObject) = buildJsonObject {
        put("vnext", buildJsonArray { add(buildJsonObject { put("address", s.host); put("port", s.port); put("users", buildJsonArray { add(user) }) }) })
    }

    private fun servers(s: Server, extra: JsonObject) = buildJsonObject {
        put("servers", buildJsonArray { add(JsonObject(mapOf("address" to JsonPrimitive(s.host), "port" to JsonPrimitive(s.port)) + extra)) })
    }

    private fun stream(s: Server, st: AppSettings) = buildJsonObject {
        put("network", s.network); put("security", s.security)
        if (s.security == "tls") put("tlsSettings", buildJsonObject {
            put("serverName", s.sni.ifEmpty { s.hostHeader.ifEmpty { s.host } }); put("allowInsecure", s.allowInsecure); put("fingerprint", s.fp)
            if (s.alpn.isNotEmpty()) put("alpn", strs(s.alpn.split(",")))
        })
        if (s.security == "reality") put("realitySettings", buildJsonObject {
            put("serverName", s.sni); put("fingerprint", s.fp); put("publicKey", s.pbk); put("shortId", s.sid); put("spiderX", s.spx)
        })
        val path = s.path.ifEmpty { "/" }
        when (s.network) {
            "ws" -> put("wsSettings", buildJsonObject { put("path", path); if (s.hostHeader.isNotEmpty()) put("headers", buildJsonObject { put("Host", s.hostHeader) }) })
            "grpc" -> put("grpcSettings", buildJsonObject { put("serviceName", s.serviceName) })
            "httpupgrade" -> put("httpupgradeSettings", buildJsonObject { put("path", path); put("host", s.hostHeader) })
            "xhttp" -> put("xhttpSettings", buildJsonObject {
                put("path", path); if (s.hostHeader.isNotEmpty()) put("host", s.hostHeader); if (s.xhttpMode.isNotEmpty()) put("mode", s.xhttpMode)
            })
        }
        if (st.fragment && s.security == "tls") put("sockopt", buildJsonObject { put("dialerProxy", "fragment") })
    }

    private fun rule(out: String, domain: List<String> = emptyList(), ip: List<String> = emptyList()) = buildJsonObject {
        put("type", "field"); if (domain.isNotEmpty()) put("domain", strs(domain)); if (ip.isNotEmpty()) put("ip", strs(ip)); put("outboundTag", out)
    }

    private val ipRe = Regex("""^(geoip:.+|\d{1,3}(\.\d{1,3}){3}(/\d+)?|[0-9a-fA-F]*:[0-9a-fA-F:]*:[0-9a-fA-F:]*(/\d+)?)$""")
    private fun JsonArrayBuilder.userRule(out: String, raw: String) {
        val l = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val ips = l.filter { ipRe.matches(it) }; val doms = l - ips.toSet()
        if (doms.isNotEmpty()) add(rule(out, domain = doms)); if (ips.isNotEmpty()) add(rule(out, ip = ips))
    }

    private fun routing(st: AppSettings) = buildJsonObject {
        put("domainStrategy", "IPIfNonMatch")
        put("rules", buildJsonArray {
            runCatching { Json.parseToJsonElement(st.customRules).jsonArray }.getOrNull()?.forEach { add(it) }   // user rules win
            userRule("block", st.blockRules); userRule("proxy", st.proxyRules); userRule("direct", st.directRules)
            if (st.blockAds) add(rule("block", domain = listOf("geosite:category-ads-all")))
            add(rule("direct", ip = listOf("geoip:private")))
            when (st.routingPreset) {
                "bypassIran" -> { add(rule("direct", domain = listOf("geosite:category-ir"))); add(rule("direct", ip = listOf("geoip:ir"))) }
                "bypassChina" -> { add(rule("direct", domain = listOf("geosite:cn"))); add(rule("direct", ip = listOf("geoip:cn"))) }
                "bypassRussia" -> { add(rule("direct", domain = listOf("geosite:category-ru"))); add(rule("direct", ip = listOf("geoip:ru"))) }
            }
        })
    }
}
