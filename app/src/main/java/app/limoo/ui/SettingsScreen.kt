package app.limoo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.limoo.Store
import app.limoo.core.GeoManager
import app.limoo.model.AppSettings

@Composable
fun SettingsScreen(store: Store) {
    val st by store.settings.collectAsState()
    var picking by remember { mutableStateOf(false) }
    fun upd(f: (AppSettings) -> AppSettings) = store.update(f)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section("Connection")
        ChoiceRow("Mode", listOf("vpn", "proxy"), st.mode) { v -> upd { it.copy(mode = v) } }
        SwitchRow("Auto-select lowest-latency server on connect", st.autoSelect) { v -> upd { it.copy(autoSelect = v) } }
        TextRow("Test URL", st.testUrl) { v -> upd { it.copy(testUrl = v) } }
        TextRow("MTU", st.mtu.toString()) { v -> v.toIntOrNull()?.let { n -> upd { it.copy(mtu = n) } } }
        TextRow("VPN DNS", st.vpnDns) { v -> upd { it.copy(vpnDns = v) } }
        SwitchRow("IPv6", st.ipv6) { v -> upd { it.copy(ipv6 = v) } }
        TextRow("SOCKS port", st.socksPort.toString()) { v -> v.toIntOrNull()?.let { n -> upd { it.copy(socksPort = n) } } }
        TextRow("HTTP port", st.httpPort.toString()) { v -> v.toIntOrNull()?.let { n -> upd { it.copy(httpPort = n) } } }
        SwitchRow("Allow LAN connections", st.allowLan) { v -> upd { it.copy(allowLan = v) } }

        Section("Routing")
        ChoiceRow("Preset", listOf("global", "bypassIran", "bypassChina", "bypassRussia"), st.routingPreset) { v -> upd { it.copy(routingPreset = v) } }
        SwitchRow("Block ads", st.blockAds) { v -> upd { it.copy(blockAds = v) } }
        Text("Domain / IP lists - one per line: domain:x.com, full:x.com, keyword:x, geosite:google, geoip:ir, 1.2.3.0/24",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        TextRow("Always proxy", st.proxyRules, multiline = true) { v -> upd { it.copy(proxyRules = v) } }
        TextRow("Always direct", st.directRules, multiline = true) { v -> upd { it.copy(directRules = v) } }
        TextRow("Always block", st.blockRules, multiline = true) { v -> upd { it.copy(blockRules = v) } }
        TextRow("Advanced: raw Xray rules (JSON array)", st.customRules, multiline = true) { v -> upd { it.copy(customRules = v) } }

        Section("Routing data (geoip / geosite)")
        ChoiceRow("Source", GeoManager.SOURCES.keys.toList(), st.geoSource) { v -> upd { it.copy(geoSource = v) } }
        SwitchRow("Auto-update weekly", st.geoAutoUpdate) { v -> upd { it.copy(geoAutoUpdate = v) } }
        GeoStatus(store)

        Section("DNS")
        TextRow("Remote DNS", st.remoteDns) { v -> upd { it.copy(remoteDns = v) } }
        TextRow("Direct DNS", st.directDns) { v -> upd { it.copy(directDns = v) } }

        Section("Anti-censorship / advanced")
        SwitchRow("TLS fragment", st.fragment) { v -> upd { it.copy(fragment = v) } }
        if (st.fragment) {
            TextRow("Fragment packets", st.fragmentPackets) { v -> upd { it.copy(fragmentPackets = v) } }
            TextRow("Fragment length", st.fragmentLength) { v -> upd { it.copy(fragmentLength = v) } }
            TextRow("Fragment interval", st.fragmentInterval) { v -> upd { it.copy(fragmentInterval = v) } }
        }
        SwitchRow("Mux", st.mux) { v -> upd { it.copy(mux = v) } }
        SwitchRow("Sniffing", st.sniffing) { v -> upd { it.copy(sniffing = v) } }
        ChoiceRow("Log level", listOf("none", "error", "warning", "info", "debug"), st.logLevel) { v -> upd { it.copy(logLevel = v) } }

        Section("Per-app proxy")
        ChoiceRow("Mode", listOf("off", "allow", "deny"), st.perAppMode) { v -> upd { it.copy(perAppMode = v) } }
        if (st.perAppMode != "off") OutlinedButton({ picking = true }) { Text("Choose apps (${st.perApp.size} selected)") }

        Section("Appearance")
        ChoiceRow("Theme", listOf("system", "light", "dark"), st.theme) { v -> upd { it.copy(theme = v) } }
        SwitchRow("Dynamic color (Android 12+)", st.dynamicColor) { v -> upd { it.copy(dynamicColor = v) } }
    }
    if (picking) AppPickerDialog(st.perApp, { l -> upd { it.copy(perApp = l) }; picking = false }, { picking = false })
}
