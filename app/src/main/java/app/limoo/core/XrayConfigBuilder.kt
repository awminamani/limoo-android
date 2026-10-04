package app.limoo.core

import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.serialization.json.*

object XrayConfigBuilder {
    /**
     * `routing.domainStrategy` and the freedom outbound's own `domainStrategy` are DIFFERENT settings
     * with different allowed values, and they used to be fed from the same field. Routing accepts
     * AsIs / IPIfNonMatch / IPOnDemand; freedom accepts AsIs / UseIP / UseIPv4 / UseIPv6. Writing the
     * routing value into freedom made recent Xray cores reject the whole config with
     * "unsupported domain strategy" - before any packet was sent - so with default settings NO server
     * on NO protocol could connect. The two are now derived separately and never share a field.
     */
    private val ROUTING_STRATEGIES = setOf("AsIs", "IPIfNonMatch", "IPOnDemand")
    private val FREEDOM_STRATEGIES = setOf("AsIs", "UseIP", "UseIPv4", "UseIPv6")

    private fun routingStrategy(st: AppSettings) = st.domainStrategy.takeIf { it in ROUTING_STRATEGIES } ?: "AsIs"

    /**
     * Freedom's `settings.domainStrategy` is deliberately OMITTED (= AsIs). It is not the same
     * setting as routing's, and omitting it is always valid. FREEDOM_STRATEGIES exists only so a test
     * can assert that if a dedicated freedom setting is ever added, its value is whitelisted.
     */
    private fun directOutbound() = buildJsonObject {
        put("tag", "direct"); put("protocol", "freedom")
        put("settings", buildJsonObject { })
    }

    /**
     * Fragment rides on `sockopt.dialerProxy`, so the `fragment` outbound must EXIST whenever that
     * reference is written. The old code set dialerProxy whenever `st.fragment && security == "tls"`
     * but only emitted the outbound when `st.fragment`, and `buildProbe` never emitted it at all -
     * so with Fragment on, the core rejected the probe config (missing tag) and real delay could never
     * work. One function now decides both sides of that relationship.
     */
    private fun outbounds(s: Server, st: AppSettings, forProbe: Boolean) = buildJsonArray {
        val useFragment = st.fragment && s.security == "tls"   // dialerProxy is only set for TLS today
        add(proxy(s, st, dialerProxy = if (useFragment) "fragment" else null))
        add(directOutbound())
        if (!forProbe) add(buildJsonObject { put("tag", "block"); put("protocol", "blackhole") })
        if (useFragment) add(buildJsonObject {
            put("tag", "fragment"); put("protocol", "freedom")
            put("settings", buildJsonObject { put("fragment", buildJsonObject {
                put("packets", packets(st.fragmentPackets))
                put("length", range(st.fragmentLength, "100-200"))
                put("interval", range(st.fragmentInterval, "10-20"))
            }) })
        })
    }

    // The core errors on malformed ranges rather than ignoring them, so anything unparseable falls
    // back to a known-good default instead of being written out.
    private val RANGE = Regex("""^\d{1,5}(-\d{1,5})?$""")
    private fun ordered(r: String) = r.split('-').map { it.toInt() }.let { it.size == 1 || it[0] <= it[1] }
    private fun range(v: String, def: String) = v.trim().takeIf { RANGE.matches(it) && ordered(it) } ?: def
    private fun packets(v: String) = v.trim().takeIf { it == "tlshello" || RANGE.matches(it) } ?: "tlshello"

    /**
     * Minimal config used only for a delay probe (the shape v2rayNG passes to measureOutboundDelay).
     * Deliberately has no inbounds - a probe must not bind the SOCKS/HTTP ports the running core owns -
     * and no routing rules, so it does not need geo data and cannot be rejected by a routing match.
     */
    fun buildProbe(s: Server, st: AppSettings): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "none") })
        put("inbounds", buildJsonArray { })
        put("outbounds", outbounds(s, st, forProbe = true))
    }.toString()

    fun build(s: Server, st: AppSettings, tun: Boolean = false, withGeoRules: Boolean = true): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", st.logLevel) })
        put("dns", dns(st))
        put("inbounds", inbounds(st, tun))
        put("outbounds", outbounds(s, st, forProbe = false))
        // Required for queryAllOutboundTrafficStats() to report anything. Without a declared stats object
        // the core keeps no per-outbound counters, so the live figures silently stay at zero and the
        // notification never updates. SystemStats is what the gRPC stats service exposes.
        put("stats", buildJsonObject { })
        put("policy", buildJsonObject {
            put("levels", buildJsonObject { put("0", buildJsonObject { put("statsUserUplink", true); put("statsUserDownlink", true) }) })
            put("system", buildJsonObject { put("statsInboundUplink", true); put("statsInboundDownlink", true)
                put("statsOutboundUplink", true); put("statsOutboundDownlink", true) })
        })
        put("routing", routing(st, withGeoRules))
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
        // With IPv6 off, a resolver that returns AAAA records can still hand the core an IPv6 address to
        // connect to, which the block rule then drops - a connection that looks like a timeout. Ask for
        // IPv4 answers explicitly so a blocked IPv6 never becomes a mystery failure.
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
        // A SOCKS/HTTP inbound with no authentication is a hole: any app on the phone can connect to
        // the tunnel and use the user's connection as its own exit, bypassing per-app rules, and any
        // app can probe 10808/10809 to detect that a proxy tool is installed at all. It is also a
        // startup failure waiting to happen - another proxy app already holding the port makes the
        // core refuse to start at all.
        //
        // In VPN mode the tun IS the tunnel and nothing needs those ports, so they are omitted unless
        // the user explicitly asks for them. Proxy-only mode keeps them: that mode exists to serve
        // them, and with nothing to configure the app would be useless.
        //
        // LAN sharing (allowLan -> 0.0.0.0) is deliberately NOT changed here.
        val wantLocal = !tun || st.localProxyPorts
        if (wantLocal) {
            val listen = if (st.allowLan) "0.0.0.0" else "127.0.0.1"
            add(buildJsonObject {
                put("tag", "socks"); put("listen", listen); put("port", st.socksPort); put("protocol", "socks")
                put("settings", buildJsonObject { put("udp", true); put("auth", "noauth") }); put("sniffing", sniff(st))
            })
            add(buildJsonObject {
                put("tag", "http"); put("listen", listen); put("port", st.httpPort); put("protocol", "http"); put("sniffing", sniff(st))
            })
        }
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

    private fun proxy(s: Server, st: AppSettings, dialerProxy: String? = null) = buildJsonObject {
        put("tag", "proxy"); put("protocol", s.protocol)
        put("settings", when (s.protocol) {
            "vless" -> vnext(s, buildJsonObject { put("id", s.uuid); put("encryption", "none"); if (s.flow.isNotEmpty()) put("flow", s.flow) })
            "vmess" -> vnext(s, buildJsonObject { put("id", s.uuid); put("security", s.method.ifEmpty { "auto" }) })
            "trojan" -> servers(s, buildJsonObject { put("password", s.uuid) })
            else -> servers(s, buildJsonObject { put("method", s.method); put("password", s.uuid) })
        })
        put("streamSettings", stream(s, st, dialerProxy))
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

    private fun stream(s: Server, st: AppSettings, dialerProxy: String? = null) = buildJsonObject {
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
        put("sockopt", sockopt(st, dialerProxy))
    }

    private fun rule(out: String, domain: List<String> = emptyList(), ip: List<String> = emptyList()) = buildJsonObject {
        put("type", "field")
        if (domain.isNotEmpty()) put("domain", strs(domain))
        if (ip.isNotEmpty()) put("ip", strs(ip))
        put("outboundTag", out)
    }

    private val ipRe = Regex("""^(geoip:.+|\d{1,3}(\.\d{1,3}){3}(/\d+)?|[0-9a-fA-F]*:[0-9a-fA-F:]*:[0-9a-fA-F:]*(/\d+)?)$""")
    private fun JsonArrayBuilder.userRule(out: String, raw: String) {
        val l = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val ips = l.filter { ipRe.matches(it) }; val doms = l - ips.toSet()
        if (doms.isNotEmpty()) add(rule(out, domain = doms)); if (ips.isNotEmpty()) add(rule(out, ip = ips))
    }

    /**
     * `withGeoRules = false` builds a config that references NO geo data, for the case where the data files
     * could not be downloaded. Every rule that would need them is dropped rather than emitted and rejected:
     * a config containing geosite:category-ads-all when the .dat file is missing is refused by the core,
     * so keeping them would mean not connecting at all. `geoip:private` is always kept - the core ships it.
     */
    private fun routing(st: AppSettings, withGeoRules: Boolean = true) = buildJsonObject {
        put("domainStrategy", routingStrategy(st))
        put("rules", buildJsonArray {
            // IPv6 off must mean BLOCKED, not absent. The tun always carries ::/0 now (see buildTun), so
            // without this rule every IPv6 destination is captured by the tunnel and then fails in
            // whatever way the protocol happens to - which is not the same as being blocked. It is FIRST
            // among the built-ins so no user or preset rule can capture it earlier and send IPv6 straight
            // out of the tunnel. Needs no data file, so it survives withGeoRules = false.
            if (!st.ipv6) add(rule("block", ip = listOf("::/0")))
            runCatching { Json.parseToJsonElement(st.customRules).jsonArray }.getOrNull()?.forEach { add(it) }   // user rules win
            userRule("block", st.blockRules); userRule("proxy", st.proxyRules); userRule("direct", st.directRules)
            if (withGeoRules && st.blockAds) add(rule("block", domain = listOf("geosite:category-ads-all")))
            // Built into the core, so it never needs a .dat file.
            add(rule("direct", ip = listOf("geoip:private")))
            if (withGeoRules) when (st.routingPreset) {
                "bypassIran" -> { add(rule("direct", domain = listOf("geosite:category-ir"))); add(rule("direct", ip = listOf("geoip:ir"))) }
                "bypassChina" -> { add(rule("direct", domain = listOf("geosite:cn"))); add(rule("direct", ip = listOf("geoip:cn"))) }
                "bypassRussia" -> { add(rule("direct", domain = listOf("geosite:category-ru"))); add(rule("direct", ip = listOf("geoip:ru"))) }
            }
            // No rule is needed to keep Xray's own proxy traffic out of the routing table. Routing only
            // applies to traffic arriving on an inbound, so the core's own outbound never matches these
            // rules. An earlier version tried to force that with a multi-tag rule and wrote
            // `"outboundTag":["proxy","fragment","block"]`, but RoutingRule.OutboundTag is a single STRING:
            // the core rejected the whole config with "failed to build routing" and nothing would connect.
        })
    }
}