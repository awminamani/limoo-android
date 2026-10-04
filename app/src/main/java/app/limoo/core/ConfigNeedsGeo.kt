package app.limoo.core

import app.limoo.model.AppSettings

/**
 * Whether the generated config actually needs the geoip/geosite data files.
 *
 * `GeoManager.ensure` downloads from GitHub and jsDelivr. `startVpn` used to call it unconditionally and
 * refuse to connect when it failed, which meant a fresh install with no routing rules - and the
 * "global" preset, which has none - could not tunnel at all on exactly the constrained networks where
 * those hosts are unreachable. Failing closed turned "no routing data" into "no VPN".
 *
 * So the decision is made from what the config will actually reference. `geoip:private` is compiled into
 * the core and resolves without any file, so it does not count as a dependency.
 *
 * Pure, so it is unit-tested rather than trusted - this is the difference between a fresh user being able
 * to connect and not.
 */
object ConfigNeedsGeo {

    private val PRESET_GEO = listOf("geosite:", "geoip:")

    /** Rules the user typed themselves, which may contain geo references. */
    private fun userRefs(raw: String): Boolean {
        val l = raw.lowercase()
        // geoip:private is built in, so it is the one reference that needs no file.
        return l.contains("geosite:") ||
            (l.contains("geoip:") && !l.contains("geoip:private"))
    }

    /** True when the config will reference geosite:/geoip: and therefore needs the data files. */
    fun check(st: AppSettings): Boolean {
        // The block-ads rule is geosite:category-ads-all.
        if (st.blockAds) return true

        // Any non-global preset emits geosite: and geoip: pairs.
        if (st.routingPreset in listOf("bypassIran", "bypassChina", "bypassRussia")) return true

        if (userRefs(st.customRules) || userRefs(st.proxyRules) ||
            userRefs(st.directRules) || userRefs(st.blockRules)
        ) return true

        return false
    }

    /** Human-readable note for the log or a banner, naming what was dropped. */
    fun describe(st: AppSettings): String = when {
        st.blockAds -> "ad-blocking rule (geosite:category-ads-all)"
        st.routingPreset != "global" -> "routing preset '${st.routingPreset}'"
        else -> "custom rules referencing geosite:/geoip:"
    }
}
