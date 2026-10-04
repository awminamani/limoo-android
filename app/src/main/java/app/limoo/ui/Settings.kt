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
import app.limoo.core.SubUpdateScheduler
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

/** The `›` chevron every navigable row ends with. One place, so it cannot drift. */
@Composable
private fun Chevron() = Text("›", style = NType.body, color = LocalN.current.muted)

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
    var interval by remember { mutableStateOf(false) }
    fun upd(f: (AppSettings) -> AppSettings) = store.update(f)
    BackHandler(page.isNotEmpty()) { page = "" }

    val n = LocalN.current
    // Hoisted: a @Composable read inside a plain (Boolean) -> Unit callback does not compile.
    val ctx = LocalContext.current
    when (page) {
        "" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.card)) {
            Text("Settings", style = NType.display, color = n.text, modifier = Modifier.padding(top = Space.standard, bottom = Space.compact))
            Group {
                NRow("Connection", "${st.mode} · ${if (st.autoConnect) "auto-connect" else "manual"}", { page = "conn" }) { Chevron() }; NDivider()
                NRow("Routing", st.routingPreset.removePrefix("bypass").ifEmpty { "all" }, { page = "route" }) { Chevron() }; NDivider()
                NRow("DNS", st.dnsStrategy, { page = "dns" }) { Chevron() }; NDivider()
                NRow("Advanced", "fragment · mux · buffers · logs", { page = "adv" }) { Chevron() }; NDivider()
                NRow("Battery", if (st.batterySaver) "saver on" else "saver off", { page = "power" }) { Chevron() }
            }
            Group {
                NRow("Subscriptions", subSummary(st), { page = "subs" }) { Chevron() }; NDivider()
                NRow("Per-app proxy", if (st.perAppMode == "off") "off" else "${st.perAppMode} · ${st.perApp.size} apps", { page = "apps" }) { Chevron() }; NDivider()
                NRow("Routing data", "geoip · geosite", { page = "geo" }) { Chevron() }
            }
            Group {
                NRow("Appearance", "${st.theme} · ${st.accent}", { page = "look" }) { Chevron() }; NDivider()
                NRow("Background", if (st.hasCustomBackground()) "custom image" else "default", { page = "bg" }) { Chevron() }; NDivider()
                NRow("Backup and about", "", { page = "about" }) { Chevron() }
            }
            Spacer(Modifier.height(24.dp))
        }
        "conn" -> Page("CONNECTION", { page = "" }) {
            Group {
                val sysCtx = LocalContext.current
                ToggleRow("Kill switch", st.killSwitch, "BLOCK TRAFFIC IF THE CONNECTION DROPS") { v -> upd { it.copy(killSwitch = v) } }; NDivider()
                NRow("System kill switch", "ALWAYS-ON VPN + BLOCK CONNECTIONS WITHOUT VPN", { runCatching { sysCtx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Chevron() }; NDivider()
                ChoiceRow("Mode", listOf("vpn", "proxy"), st.mode) { v -> upd { it.copy(mode = v) } }; NDivider()
                ToggleRow("Connect on app start", st.autoConnect) { v -> upd { it.copy(autoConnect = v) } }; NDivider()
                ToggleRow("Auto-select fastest server", st.autoSelect, "BEFORE EVERY CONNECT") { v -> upd { it.copy(autoSelect = v) } }; NDivider()
                ToggleRow("Real delay test", st.realPing, "CORE PROBE - OFF MEANS A PLAIN TCP CONNECT") { v -> upd { it.copy(realPing = v) } }; NDivider()
                ToggleRow("IPv6", st.ipv6) { v -> upd { it.copy(ipv6 = v) } }; NDivider()
                // Without this, QUIC/UDP cannot leave the tunnel at all, which breaks voice/video calls and
                // some game traffic rather than merely slowing it down.
                ToggleRow("UDP through tunnel", st.endpointIndependentNat, "REQUIRED FOR QUIC - TURN OFF ONLY TO TROUBLESHOOT") { v -> upd { it.copy(endpointIndependentNat = v) } }; NDivider()
                ToggleRow("Allow LAN connections", st.allowLan, "SHARE THE PROXY ON YOUR NETWORK") { v -> upd { it.copy(allowLan = v) } }
            }
            Group {
                FieldRow("Test URL", st.testUrl) { v -> upd { it.copy(testUrl = v) } }
                NumberRow("Ping timeout (ms)", st.pingTimeoutMs) { v -> upd { it.copy(pingTimeoutMs = v.coerceIn(500, 20000)) } }
                NumberRow("MTU", st.mtu) { v -> upd { it.copy(mtu = v.coerceIn(576, 9000)) } }
                FieldRow("VPN DNS", st.vpnDns) { v -> upd { it.copy(vpnDns = v) } }
                NumberRow("SOCKS port", st.socksPort) { v -> upd { it.copy(socksPort = v.coerceIn(1, 65535)) } }
                NumberRow("HTTP port", st.httpPort) { v -> upd { it.copy(httpPort = v.coerceIn(1, 65535)) } }
            }
        }
        "route" -> Page("ROUTING", { page = "" }) {
            Group {
                ChoiceRow("Preset", listOf("global", "bypassIran", "bypassChina", "bypassRussia"), st.routingPreset) { v -> upd { it.copy(routingPreset = v) } }; NDivider()
                ChoiceRow("Domain strategy", listOf("AsIs", "IPIfNonMatch", "IPOnDemand"), st.domainStrategy, sub = "HOW DOMAINS ARE RESOLVED FOR RULE MATCHING") { v -> upd { it.copy(domainStrategy = v) } }; NDivider()
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
            Group {
                ChoiceRow("Query strategy", listOf("UseIP", "UseIPv4", "UseIPv6"), st.dnsStrategy) { v -> upd { it.copy(dnsStrategy = v) } }; NDivider()
                ToggleRow("Cache results", st.dnsCache, "OFF RECONNECTS EVERY RESOLUTION") { v -> upd { it.copy(dnsCache = v) } }; NDivider()
                if (st.dnsCache) NumberRow("Cache lifetime (s, 0 = forever)", st.dnsCacheTTL) { v -> upd { it.copy(dnsCacheTTL = v.coerceIn(0, 86400)) } }
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
                if (st.mux) {
                    NumberRow("Mux concurrency", st.muxConcurrency) { v -> upd { it.copy(muxConcurrency = v.coerceIn(1, 128)) } }
                    ToggleRow("Mux padding", st.muxPadding, "HIDES STREAM LENGTHS FROM ANALYSIS") { v -> upd { it.copy(muxPadding = v) } }
                }
            }
            Group {
                NLabel("TRANSPORT", Modifier.padding(horizontal = Space.card, vertical = Space.compact))
                // Nagle batches small writes, which adds up to ~40 ms to every interactive exchange.
                ToggleRow("No Nagle delay", st.tcpNoDelay, "LOWER LATENCY, TINY BANDWIDTH COST") { v -> upd { it.copy(tcpNoDelay = v) } }; NDivider()
                ToggleRow("TCP fast open", st.tcpFastOpen, "FEWER ROUND TRIPS, NOT SUPPORTED EVERYWHERE") { v -> upd { it.copy(tcpFastOpen = v) } }; NDivider()
                ToggleRow("TCP keep-alive", st.tcpKeepAlive, "HOLDS IDLE CONNECTIONS OPEN") { v -> upd { it.copy(tcpKeepAlive = v) } }
                if (st.tcpKeepAlive) NumberRow("Keep-alive interval (s)", st.tcpKeepAliveInterval) { v -> upd { it.copy(tcpKeepAliveInterval = v.coerceIn(0, 300)) } }
                Spacer(Modifier.height(Space.small))
                NumberRow("Socket buffer (kB, 0 = system)", st.bufferSize) { v -> upd { it.copy(bufferSize = v.coerceIn(0, 8192)) } }
                NLabel("LARGER BUFFERS HELP ON HIGH-LATENCY OR HIGH-BANDWIDTH LINKS", Modifier.padding(horizontal = Space.card, vertical = Space.micro))
            }
            Group {
                ToggleRow("Sniffing", st.sniffing, "READS THE DESTINATION FROM HTTP, TLS AND QUIC") { v -> upd { it.copy(sniffing = v) } }; NDivider()
                ChoiceRow("Log level", listOf("none", "error", "warning", "info", "debug"), st.logLevel) { v -> upd { it.copy(logLevel = v) } }
            }
        }
        "power" -> Page("BATTERY", { page = "" }) {
            Group {
                ToggleRow("Battery saver", st.batterySaver, "HALVES THE LIVE-STAT AND NOTIFICATION RATE, SKIPS THE PING SWEEP ON CONNECT") { v -> upd { it.copy(batterySaver = v) } }; NDivider()
                NumberRow("Stat interval (ms)", st.statIntervalMs) { v -> upd { it.copy(statIntervalMs = v.coerceIn(500, 5000)) } }
            }
            Text(
                "The live figures, the notification speed and the connect-time latency sweep are what keep the app awake. Lower the interval or turn the saver on if the tunnel shows up in your battery usage.",
                style = NType.bodySmall, color = n.muted, modifier = Modifier.padding(vertical = Space.compact),
            )
        }
        "subs" -> Page("SUBSCRIPTIONS", { page = "" }) {
            Group {
                // On by default, as asked. Both this AND each subscription's own toggle must be on.
                ToggleRow("Auto-update", st.subAutoUpdate, "REFRESHES THE LIST ON A SCHEDULE, EVEN WHEN THE APP IS CLOSED") { v ->
                    upd { it.copy(subAutoUpdate = v) }
                    SubUpdateScheduler.apply(ctx, store.settings.value)
                }; NDivider()
                if (st.subAutoUpdate) {
                    NRow("Every", intervalLabel(st.subUpdateIntervalMin), { interval = true }) { Chevron() }
                }
            }
            Text(
                "Android batches background work to save battery, so the actual refresh lands near the interval rather than exactly on it. Opening the app also refreshes anything that is overdue.",
                style = NType.bodySmall, color = n.muted, modifier = Modifier.padding(vertical = Space.compact),
            )
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
        "bg" -> Page("BACKGROUND", { page = "" }) {
            Group {
                // The default is always one tap away, which is the whole point: a custom image is a
                // preference, never a trap the user has to undo.
                NRow("Default background", "THE LIMOO ARTWORK", { upd { it.copy(bgSource = "", bgDim = 0.45f, bgBlur = 0f) }; Ui.say("DEFAULT BACKGROUND") }, highlight = !st.hasCustomBackground()); NDivider()
                NRow("Choose from gallery", if (st.hasCustomBackground()) "CUSTOM IMAGE SET" else "PICK A PHOTO", a.pickBackground, highlight = st.hasCustomBackground())
            }
            if (st.hasCustomBackground()) {
                NLabel("DIM", Modifier.padding(start = Space.card, end = Space.card, top = Space.compact))
                NSlider("Background dim", st.bgDim, 0f, 0.95f, valueLabel = "${(st.bgDim * 100).toInt()}%") { v ->
                    upd { it.copy(bgDim = v) }
                }
                NSlider("Background blur", st.bgBlur, 0f, 1f, valueLabel = "${(st.bgBlur * 100).toInt()}%") { v ->
                    upd { it.copy(bgBlur = v) }
                }
                NLabel("BLUR SOFTENS THE IMAGE - ONLY THE DIM SLIDER IS FREE", Modifier.padding(horizontal = Space.card))
            }
            Text(
                "The photo stays on your device. Limoo stores only the reference, never a copy, and the gallery picker asks for no storage permission. A shared .limoo file carries the dim and blur but never your image.",
                style = NType.bodySmall, color = n.muted, modifier = Modifier.padding(vertical = Space.compact),
            )
        }
        else -> Page("BACKUP", { page = "" }) {
            Group {
                NRow("Export backup", "servers · subscriptions · settings · look", a.exportBackup) { Chevron() }; NDivider()
                NRow("Restore from file", "opens the import preview", a.pickFile) { Chevron() }; NDivider()
                NRow("Reset settings", "servers are kept", { confirmReset = true }) { Chevron() }
            }
            Group {
                NRow("Limoo", "version " + LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?" }) {}; NDivider()
                NRow("Core", "Xray via libv2ray") {}; NDivider()
                NRow("File format", ".limoo v2 - reads v1") {}
            }
        }
    }

    if (picking) AppPickerDialog(st.perApp, { l -> upd { it.copy(perApp = l) }; picking = false }, { picking = false })

    if (interval) {
        val ctx = LocalContext.current
        NSheet({ interval = false }) {
            NLabel("Update every")
            Spacer(Modifier.height(Space.compact))
            listOf(60 to "1 HOUR", 360 to "6 HOURS", 720 to "12 HOURS", 1440 to "24 HOURS").forEach { (m, l) ->
                SheetRow(l, highlight = st.subUpdateIntervalMin == m) {
                    upd { it.copy(subUpdateIntervalMin = m) }
                    SubUpdateScheduler.apply(ctx, store.settings.value)
                    interval = false
                }
            }
            NDivider()
            Spacer(Modifier.height(Space.small))
            NField("CUSTOM (MINUTES)", st.subUpdateIntervalMin.toString(), { v ->
                val m = v.toIntOrNull()
                if (m != null && m in 15..10080) {
                    upd { it.copy(subUpdateIntervalMin = m) }
                    SubUpdateScheduler.apply(ctx, store.settings.value)
                }
            }, keyboard = KeyboardType.Number)
            NLabel("BETWEEN 15 MINUTES AND 7 DAYS", Modifier.padding(top = Space.small))
        }
    }

    if (confirmReset) NSheet({ confirmReset = false }) {
        NLabel("Reset all settings?")
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton("Cancel", { confirmReset = false }, Modifier.weight(1f))
            NButton("Reset", { upd { AppSettings() }; confirmReset = false; Ui.say("Settings reset") }, Modifier.weight(1f), danger = true)
        }
    }
}

/** "ON · EVERY 6 HOURS" / "OFF", shown as the row's subtitle on the Settings index. */
private fun subSummary(st: AppSettings): String =
    if (!st.subAutoUpdate) "off" else "on · ${intervalLabel(st.subUpdateIntervalMin)}"

private fun intervalLabel(min: Int): String = when (min) {
    60 -> "1 HOUR"; 360 -> "6 HOURS"; 720 -> "12 HOURS"; 1440 -> "24 HOURS"
    else -> if (min < 60) "$min MIN" else "${min / 60}H ${min % 60}M".trim()
}