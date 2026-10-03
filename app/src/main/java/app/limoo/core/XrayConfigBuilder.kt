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
            add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom"); put("settings", buildJsonObject { put("domainStrategy", st.domainStrategy) }) })
        })
    }.toString()

    fun build(s: Server, st: AppSettings, tun: Boolean = false): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", st.logLevel) })
        put("dns", dns(st))
        put("inbounds", inbounds(st, tun))
        put("outbounds", buildJsonArray {
            add(proxy(s, st))
            add(buildJsonObject {
                put("tag", "direct"); put("protocol", "freedom")
                put("settings", buildJsonObject { put("domainStrategy", st.domainStrategy) })
            })
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
        put("stats", buildJsonObject { })
        put("policy", buildJsonObject {
            put("levels", buildJsonObject { put("0", buildJsonObject { put("statsUserUplink", true); put("statsUserDownlink", true) }) })
            put("system", buildJsonObject { put("statsInboundUplink", true); put("statsInboundDownlink", true)
                put("statsOutboundUplink", true); put("statsOutboundDownlink", true) })
        })
        put("routing", routing(st))
    }.toString()

    private fun strs(l: List<String>) = buildJsonArray { l.forEach { add(it.trim()) } }

    /**
     * DNS block. The remote resolver (usually DoH) is answered by the core, and the plain resolver is
     * restricted to private names so a failed DoH bootstrap cannot take the tunnel down with it.
     * `queryStrategy` follows the user's choice, and `disableCache` honours it instead of leaving caching
     * on implicitly.
     */
    private fun dns(st: AppSettings) = buildJsonObject {
        put("servers", buildJsonArray {
            add(buildJsonObject { put("address", st.remoteDns); put("skipFallback", false) })
            add(buildJsonObject {
                put("address", st.directDns); put("domains", strs(listOf("geosite:private")))
                put("expectIPs", strs(listOf("geoip:private")))
            })
            add(buildJsonObject { put("address", "localhost") })
        })
        put("queryStrategy", st.dnsStrategy.ifEmpty { if (st.ipv6) "UseIP" else "UseIPv4" })
        put("disableCache", !st.dnsCache)
        // PositiveTTL only takes effect with caching on; sending it with disableCache is rejected by the core.
        if (st.dnsCache && st.dnsCacheTTL > 0) put("cacheStrategy", "PositiveTTL")
    }

    private fun sniff(st: AppSettings) = buildJsonObject {
        put("enabled", st.sniffing); put("destOverride", strs(listOf("http", "tls", "quic"))); put("routeOnly", true)
    }

    private fun inbounds(st: AppSettings, tun: Boolean) = buildJsonArray {
        if (tun) add(buildJsonObject {
            put("tag", "tun"); put("protocol", "tun")
            put("settings", buildJsonObject {
                put("name", "xray0"); put("MTU", st.mtu); put("userLevel", 0)
                // Without endpoint-independent NAT, QUIC/UDP traffic cannot leave the tunnel because the
                // internal source port is not what the outside world expects to reply to. This is the
                // single setting that decides whether UDP through the VPN works at all.
                put("endpointIndependentNat", st.endpointIndependentNat)
            })
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

    /**
     * Socket options shared by every outbound. These are the connection-level knobs that decide latency and
     * throughput, so they are user-visible rather than hard-coded.
     */
    private fun sockopt(st: AppSettings, dialerProxy: String? = null) = buildJsonObject {
        put("tcpFastOpen", st.tcpFastOpen)
        put("tcpNoDelay", st.tcpNoDelay)
        if (st.tcpKeepAlive) {
            put("tcpKeepAliveInterval", st.tcpKeepAliveInterval)
            // Keep-alive idle is conventionally a few multiples of the interval; derive it rather than
            // storing a second value that could disagree with the first.
            put("tcpKeepAliveIdle", (st.tcpKeepAliveInterval * 3).coerceAtLeast(10))
        }
        // 0 means "system default" and must be omitted: bufferSize 0 stalls the connection outright.
        if (st.bufferSize > 0) put("bufferSize", st.bufferSize * 1024)
        if (dialerProxy != null) put("dialerProxy", dialerProxy)
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
            put("mux", buildJsonObject {
                put("enabled", true); put("concurrency", st.muxConcurrency)
                // Padding defeats traffic analysis that fingerprints multiplexed streams by length.
                put("padding", st.muxPadding)
            })
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
        // Fragment rides on sockopt, so it has to be merged with the user's socket options rather than
        // replacing them - a second `put("sockopt")` in the same builder would silently drop the first.
        put("sockopt", sockopt(st, if (st.fragment && s.security == "tls") "fragment" else null))
    }

    private fun rule(out: String, domain: List<String> = emptyList(), ip: List<String> = emptyList(), skipOut: List<String> = emptyList()) = buildJsonObject {
        put("type", "field")
        if (domain.isNotEmpty()) put("domain", strs(domain))
        if (ip.isNotEmpty()) put("ip", strs(ip))
        if (skipOut.isNotEmpty()) put("outboundTag", strs(skipOut))
        else put("outboundTag", out)
    }

    private val ipRe = Regex("""^(geoip:.+|\d{1,3}(\.\d{1,3}){3}(/\d+)?|[0-9a-fA-F]*:[0-9a-fA-F:]*:[0-9a-fA-F:]*(/\d+)?)$""")
    private fun JsonArrayBuilder.userRule(out: String, raw: String) {
        val l = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val ips = l.filter { ipRe.matches(it) }; val doms = l - ips.toSet()
        if (doms.isNotEmpty()) add(rule(out, domain = doms)); if (ips.isNotEmpty()) add(rule(out, ip = ips))
    }

    private fun routing(st: AppSettings) = buildJsonObject {
        put("domainStrategy", st.domainStrategy)
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
            // Last: Xray's own proxy/fragment/block traffic must bypass routing, or the core's outbound would
            // be matched by a rule above and routed back into its own inbound. This is a direct outboundTag
            // rule (a LIST of tags), not a domain/ip match, so it uses skipOut rather than `out`.
            add(rule("direct", skipOut = listOf("proxy", "fragment", "block")))
        })
    }
}