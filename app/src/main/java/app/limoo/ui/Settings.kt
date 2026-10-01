package app.limoo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.limoo.Store
import app.limoo.core.GeoManager
import app.limoo.model.AppSettings
import kotlinx.coroutines.launch

/** Settings groups: a hairline-bordered block, 8dp radius. Rows inside are separated by hairlines. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) =
    NCard(Modifier.fillMaxWidth().padding(bottom = Space.compact), content = content)

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.card)) {
        Row(Modifier.fillMaxWidth().padding(top = Space.standard, bottom = Space.compact), verticalAlignment = Alignment.CenterVertically) {
            NButton("Back", onBack, compact = true)
            Spacer(Modifier.width(Space.standard))
            Text(title, style = NType.title, color = LocalN.current.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        content(); Spacer(Modifier.height(Space.section))
    }
}

@Composable
fun GeoCard(store: Store) {
    val ctx = LocalContext.current; val n = LocalN.current; val scope = rememberCoroutineScope()
    val p by GeoManager.progress.collectAsState(); val st by store.settings.collectAsState()
    val ready = remember(p.running) { GeoManager.ready(ctx) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        NLabel("STATUS")
        Text(
            when { p.running -> "Downloading ${p.file}"; ready -> "Ready · ${GeoManager.ageDays(ctx)} days old"; else -> "Not downloaded" },
            style = NType.body, color = n.text, modifier = Modifier.padding(top = Space.micro),
        )
        if (p.running) SegmentedBar(p.percent / 100f, Modifier.padding(top = Space.small))
        p.error?.let { Text(it, style = NType.mono, color = n.accent, modifier = Modifier.padding(top = 6.dp)) }
        if (!p.running) NButton(if (ready) "Update now" else "Download", { scope.launch { GeoManager.forceUpdate(ctx, st.geoSource) } }, Modifier.padding(top = Space.standard), compact = true)
    }
}

@Composable
fun SettingsScreen(store: Store, a: Actions) {
    var page by rememberSaveable { mutableStateOf("") }
    val st by store.settings.collectAsState()
    var picking by remember { mutableStateOf(false) }; var confirmReset by remember { mutableStateOf(false) }
    fun upd(f: (AppSettings) -> AppSettings) = store.update(f)
    BackHandler(page.isNotEmpty()) { page = "" }

    val n = LocalN.current
    when (page) {
        "" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.card)) {
            Text("Settings", style = NType.display, color = n.text, modifier = Modifier.padding(top = Space.standard, bottom = Space.compact))
            Group {
                NRow("Connection", "${st.mode} · ${if (st.autoConnect) "auto-connect" else "manual"}", { page = "conn" }) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("Routing", st.routingPreset.removePrefix("bypass").ifEmpty { "all" }, { page = "route" }) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("DNS", "", { page = "dns" }) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("Advanced", "fragment · mux · logs", { page = "adv" }) { Text("›", style = NType.body, color = n.muted) }
            }
            Group {
                NRow("Per-app proxy", if (st.perAppMode == "off") "off" else "${st.perAppMode} · ${st.perApp.size} apps", { page = "apps" }) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("Routing data", "geoip · geosite", { page = "geo" }) { Text("›", style = NType.body, color = n.muted) }
            }
            Group {
                NRow("Appearance", "${st.theme} · ${st.accent}", { page = "look" }) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("Backup and about", "", { page = "about" }) { Text("›", style = NType.body, color = n.muted) }
            }
            Spacer(Modifier.height(24.dp))
        }
        "conn" -> Page("CONNECTION", { page = "" }) {
            Group {
                ChoiceRow("Mode", listOf("vpn", "proxy"), st.mode) { v -> upd { it.copy(mode = v) } }; NDivider()
                ToggleRow("Connect on app start", st.autoConnect) { v -> upd { it.copy(autoConnect = v) } }; NDivider()
                ToggleRow("Auto-select fastest server", st.autoSelect, "BEFORE EVERY CONNECT") { v -> upd { it.copy(autoSelect = v) } }; NDivider()
                ToggleRow("Real delay test", st.realPing, "CORE PROBE - OFF MEANS A PLAIN TCP CONNECT") { v -> upd { it.copy(realPing = v) } }; NDivider()
                ToggleRow("IPv6", st.ipv6) { v -> upd { it.copy(ipv6 = v) } }; NDivider()
                ToggleRow("Allow LAN connections", st.allowLan, "SHARE THE PROXY ON YOUR NETWORK") { v -> upd { it.copy(allowLan = v) } }
            }
            Group {
                FieldRow("Test URL", st.testUrl) { v -> upd { it.copy(testUrl = v) } }
                NumberRow("MTU", st.mtu) { v -> upd { it.copy(mtu = v) } }
                FieldRow("VPN DNS", st.vpnDns) { v -> upd { it.copy(vpnDns = v) } }
                NumberRow("SOCKS port", st.socksPort) { v -> upd { it.copy(socksPort = v) } }
                NumberRow("HTTP port", st.httpPort) { v -> upd { it.copy(httpPort = v) } }
            }
        }
        "route" -> Page("ROUTING", { page = "" }) {
            Group {
                ChoiceRow("Preset", listOf("global", "bypassIran", "bypassChina", "bypassRussia"), st.routingPreset) { v -> upd { it.copy(routingPreset = v) } }; NDivider()
                ToggleRow("Block ads", st.blockAds) { v -> upd { it.copy(blockAds = v) } }
            }
            Text("One per line: domain:x.com · full:x.com · keyword:x · geosite:google · geoip:ir · 1.2.3.0/24", style = NType.bodySmall, color = n.muted, modifier = Modifier.padding(vertical = Space.compact))
            Group {
                FieldRow("Always proxy", st.proxyRules, multiline = true) { v -> upd { it.copy(proxyRules = v) } }
                FieldRow("Always direct", st.directRules, multiline = true) { v -> upd { it.copy(directRules = v) } }
                FieldRow("Always block", st.blockRules, multiline = true) { v -> upd { it.copy(blockRules = v) } }
                FieldRow("Raw Xray rules (JSON array)", st.customRules, multiline = true) { v -> upd { it.copy(customRules = v) } }
            }
        }
        "dns" -> Page("DNS", { page = "" }) {
            Group {
                FieldRow("Remote DNS", st.remoteDns) { v -> upd { it.copy(remoteDns = v) } }
                FieldRow("Direct DNS", st.directDns) { v -> upd { it.copy(directDns = v) } }
            }
        }
        "adv" -> Page("ADVANCED", { page = "" }) {
            Group {
                ToggleRow("TLS fragment", st.fragment, "HELPS AGAINST SNI FILTERING") { v -> upd { it.copy(fragment = v) } }
                if (st.fragment) {
                    FieldRow("Fragment packets", st.fragmentPackets) { v -> upd { it.copy(fragmentPackets = v) } }
                    FieldRow("Fragment length", st.fragmentLength) { v -> upd { it.copy(fragmentLength = v) } }
                    FieldRow("Fragment interval", st.fragmentInterval) { v -> upd { it.copy(fragmentInterval = v) } }
                }
            }
            Group {
                ToggleRow("Mux", st.mux) { v -> upd { it.copy(mux = v) } }
                if (st.mux) NumberRow("Mux concurrency", st.muxConcurrency) { v -> upd { it.copy(muxConcurrency = v) } }
                NDivider(); ToggleRow("Sniffing", st.sniffing) { v -> upd { it.copy(sniffing = v) } }; NDivider()
                ChoiceRow("Log level", listOf("none", "error", "warning", "info", "debug"), st.logLevel) { v -> upd { it.copy(logLevel = v) } }
            }
        }
        "apps" -> Page("APPS", { page = "" }) {
            Group {
                ChoiceRow("Mode", listOf("off", "allow", "deny"), st.perAppMode) { v -> upd { it.copy(perAppMode = v) } }
                if (st.perAppMode != "off") { NDivider(); NRow("Choose apps", "${st.perApp.size} selected", { picking = true }) }
            }
            NLabel(when (st.perAppMode) { "allow" -> "ONLY SELECTED APPS USE THE VPN"; "deny" -> "SELECTED APPS BYPASS THE VPN"; else -> "ALL APPS USE THE VPN" }, Modifier.padding(6.dp))
        }
        "geo" -> Page("ROUTING DATA", { page = "" }) {
            Group {
                ChoiceRow("Source", GeoManager.SOURCES.keys.toList(), st.geoSource) { v -> upd { it.copy(geoSource = v) } }; NDivider()
                ToggleRow("Auto-update weekly", st.geoAutoUpdate) { v -> upd { it.copy(geoAutoUpdate = v) } }; NDivider()
                GeoCard(store)
            }
        }
        "look" -> Page("LOOK AND FEEL", { page = "" }) {
            Group {
                ChoiceRow("Theme", listOf("system", "light", "dark"), st.theme) { v -> upd { it.copy(theme = v) } }; NDivider()
                ChoiceRow("Accent", listOf("mono", "red", "lime", "amber", "blue"), st.accent) { v -> upd { it.copy(accent = v) } }; NDivider()
                ToggleRow("Haptics", st.haptics) { v -> upd { it.copy(haptics = v) } }
            }
            Group {
                ToggleRow("Privacy mode", st.privacyMode, "HIDE SERVER ADDRESSES ON SCREEN") { v -> upd { it.copy(privacyMode = v) } }; NDivider()
                ToggleRow("Detect configs in clipboard", st.clipboardWatch, "OFFERS TO IMPORT WHEN YOU OPEN LIMOO") { v -> upd { it.copy(clipboardWatch = v) } }
            }
        }
        else -> Page("BACKUP", { page = "" }) {
            Group {
                NRow("Export backup", "servers · subscriptions · settings", a.exportBackup) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("Restore from file", "opens the import preview", a.pickFile) { Text("›", style = NType.body, color = n.muted) }; NDivider()
                NRow("Reset settings", "servers are kept", { confirmReset = true }) { Text("›", style = NType.body, color = n.muted) }
            }
            Group {
                NRow("Limoo", "version 0.3.0") {}; NDivider()
                NRow("Core", "Xray via libv2ray") {}
            }
        }
    }

    if (picking) AppPickerDialog(st.perApp, { l -> upd { it.copy(perApp = l) }; picking = false }, { picking = false })
    if (confirmReset) NSheet({ confirmReset = false }) {
        NLabel("Reset all settings?")
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton("Cancel", { confirmReset = false }, Modifier.weight(1f))
            NButton("Reset", { upd { AppSettings() }; confirmReset = false; Ui.say("Settings reset") }, Modifier.weight(1f), danger = true)
        }
    }
}
