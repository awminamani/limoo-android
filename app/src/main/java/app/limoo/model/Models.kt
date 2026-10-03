package app.limoo.model

import kotlinx.serialization.Serializable

@Serializable
data class Server(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String, val protocol: String,          // vless | vmess | trojan | shadowsocks
    val host: String, val port: Int,
    val uuid: String = "",                           // UUID or password
    val method: String = "", val flow: String = "",
    val network: String = "tcp",                     // tcp | ws | grpc | xhttp | httpupgrade
    val security: String = "none",                   // none | tls | reality
    val sni: String = "", val alpn: String = "", val fp: String = "chrome",
    val path: String = "", val hostHeader: String = "", val serviceName: String = "", val xhttpMode: String = "",
    val pbk: String = "", val sid: String = "", val spx: String = "",
    val allowInsecure: Boolean = false,
    val group: String = "", val note: String = "", val pingMs: Long = -1,
    val fav: Boolean = false, val lastUsed: Long = 0, val subId: String = "",
)

@Serializable
data class Subscription(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String, val url: String,
    val updatedAt: Long = 0,                                        // epoch seconds
    val upload: Long = 0, val download: Long = 0, val total: Long = 0, val expire: Long = 0,   // from subscription-userinfo
    val autoUpdate: Boolean = true, val error: String = "",
)

/**
 * How long a subscription fetch may take before it is abandoned. The waiting overlay is dismissible at any
 * moment but the fetch itself is bounded by this, so hiding the animation never leaves a request running
 * forever.
 */
const val SUB_FETCH_TIMEOUT_MS = 45_000

@Serializable
data class AppSettings(
    // connection
    val mode: String = "vpn",                        // vpn | proxy (local SOCKS/HTTP only)
    val mtu: Int = 1500, val ipv6: Boolean = false, val vpnDns: String = "1.1.1.1",
    val socksPort: Int = 10808, val httpPort: Int = 10809, val allowLan: Boolean = false,
    // endpoint-independent NAT: without it, QUIC/UDP dies behind the tunnel. Real, not cosmetic.
    val endpointIndependentNat: Boolean = true,
    // dns
    val remoteDns: String = "https://1.1.1.1/dns-query", val directDns: String = "8.8.8.8",
    val dnsCache: Boolean = true, val dnsCacheTTL: Int = 300,          // seconds, 0 = forever
    val dnsStrategy: String = "UseIP",               // UseIP | UseIPv4 | UseIPv6
    // routing
    val routingPreset: String = "global",            // global | bypassIran | bypassChina | bypassRussia
    // How a domain is resolved for routing. AsIs avoids a second DNS round trip, IPIfNonMatch keeps rules
    // working at the cost of one lookup, IPOnDemand resolves only when a rule actually needs an IP.
    val domainStrategy: String = "IPIfNonMatch",     // AsIs | IPIfNonMatch | IPOnDemand
    val blockAds: Boolean = false, val customRules: String = "",   // JSON array of Xray rules
    // advanced
    val sniffing: Boolean = true, val mux: Boolean = false, val muxConcurrency: Int = 8,
    val muxPadding: Boolean = true,
    val fragment: Boolean = false, val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "100-200", val fragmentInterval: String = "10-20",
    val logLevel: String = "warning",
    // transport tuning (sockopt on every outbound)
    val tcpNoDelay: Boolean = true,                  // Nagle off: lower latency for interactive traffic
    val tcpFastOpen: Boolean = false,
    val tcpKeepAlive: Boolean = false, val tcpKeepAliveInterval: Int = 0,   // seconds, 0 = disabled
    val bufferSize: Int = 0,                         // socket buffer in kB, 0 = system default
    // per-app
    val perAppMode: String = "off",                  // off | allow | deny
    val perApp: List<String> = emptyList(),
    // appearance / behaviour
    val theme: String = "system", val dynamicColor: Boolean = false,
    // background: "" source = the shipped Limoo artwork. Any other value is a content:// image the user picked.
    val bgSource: String = "", val bgDim: Float = 0.45f, val bgBlur: Float = 0f,
    // domain / IP lists (one per line: domain:x.com, geosite:x, geoip:ir, 1.2.3.0/24, full:x.com, keyword:x)
    val directRules: String = "", val proxyRules: String = "", val blockRules: String = "",
    // routing data + testing
    val geoSource: String = "chocolate4u", val geoAutoUpdate: Boolean = true,
    // ui / behaviour
    val accent: String = "mono", val haptics: Boolean = true, val privacyMode: Boolean = false,
    val autoConnect: Boolean = false, val clipboardWatch: Boolean = true, val sortBy: String = "manual",
    val testUrl: String = "https://www.gstatic.com/generate_204", val autoSelect: Boolean = false,
    // true = use the core's own delay probe instead of a plain TCP connect (slower, but real RTT)
    val realPing: Boolean = true,
    // kill switch: keep the tunnel up and drop traffic if the core fails
    val killSwitch: Boolean = false,
    // latency test timeout (TCP connect; the in-tunnel test uses 2x)
    val pingTimeoutMs: Int = 4000,
    // ---- subscription auto-update ----
    // Master switch, on by default. Each subscription also has its own autoUpdate flag; both must be on.
    val subAutoUpdate: Boolean = true,
    // Presets are 1/6/12/24 h (60/360/720/1440 min). Any other value is honoured literally as minutes.
    val subUpdateIntervalMin: Int = 360,
    // How often the live figures are re-read, in ms. Larger = less wake-ups = less battery.
    val statIntervalMs: Int = 1000,
    // Battery saver: 2 s notification refresh and a 2 s stats poll instead of 1 s, and no ping-on-connect.
    val batterySaver: Boolean = false,
)