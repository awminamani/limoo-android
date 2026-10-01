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

@Serializable
data class AppSettings(
    // connection
    val mode: String = "vpn",                        // vpn | proxy (local SOCKS/HTTP only)
    val mtu: Int = 1500, val ipv6: Boolean = false, val vpnDns: String = "1.1.1.1",
    val socksPort: Int = 10808, val httpPort: Int = 10809, val allowLan: Boolean = false,
    // dns
    val remoteDns: String = "https://1.1.1.1/dns-query", val directDns: String = "8.8.8.8",
    // routing
    val routingPreset: String = "global",            // global | bypassIran | bypassChina | bypassRussia
    val blockAds: Boolean = false, val customRules: String = "",   // JSON array of Xray rules
    // advanced
    val sniffing: Boolean = true, val mux: Boolean = false, val muxConcurrency: Int = 8,
    val fragment: Boolean = false, val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "100-200", val fragmentInterval: String = "10-20",
    val logLevel: String = "warning",
    // per-app
    val perAppMode: String = "off",                  // off | allow | deny
    val perApp: List<String> = emptyList(),
    // appearance / behaviour
    val theme: String = "system", val dynamicColor: Boolean = false,
    // domain / IP lists (one per line: domain:x.com, geosite:x, geoip:ir, 1.2.3.0/24, full:x.com, keyword:x)
    val directRules: String = "", val proxyRules: String = "", val blockRules: String = "",
    // routing data + testing
    val geoSource: String = "chocolate4u", val geoAutoUpdate: Boolean = true,
    // ui / behaviour
    val accent: String = "red", val haptics: Boolean = true, val privacyMode: Boolean = false,
    val autoConnect: Boolean = false, val clipboardWatch: Boolean = true, val sortBy: String = "manual",
    val testUrl: String = "https://www.gstatic.com/generate_204", val autoSelect: Boolean = false,
    // true = use the core's own delay probe instead of a plain TCP connect (slower, but real RTT)
    val realPing: Boolean = true,
)
