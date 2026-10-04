package app.limoo.ui

import android.net.TrafficStats
import android.os.Process
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limoo.Store
import app.limoo.core.ErrorHints
import app.limoo.core.GeoManager
import app.limoo.core.Latency
import app.limoo.core.LimooVpnService
import app.limoo.core.LimooVpnService.State
import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

fun fmtBytes(b: Long): String {
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.coerceAtLeast(0).toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, if (i == 0) "%.0f" else "%.1f", v) + " " + u[i]
}

fun fmtUptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
}

fun mask(s: String, on: Boolean) = if (!on) s else if (s.length <= 4) "****" else s.take(2) + "***" + s.takeLast(2)

class Traffic(val down: Long = 0, val up: Long = 0, val totalDown: Long = 0, val totalUp: Long = 0)

/**
 * Speed and session totals, straight from LimooVpnService. The service is the only reader of the core's
 * counters (they reset on read) and computes the rates, so Home, widget and notification always agree.
 */
@Composable
fun rememberTraffic(active: Boolean): Pair<Traffic, Boolean> {
    val live by LimooVpnService.traffic.collectAsState()
    val exact by LimooVpnService.trafficExact.collectAsState()
    val c = if (active) live else null
    val t = if (c == null) Traffic() else Traffic(down = c.downRate, up = c.upRate, totalDown = c.down, totalUp = c.up)
    return t to (c != null && exact)
}

/**
 * The hero: a dot-matrix ring. Every dot sits on one square lattice centred in the canvas, so the ring is
 * perfectly symmetric - deriving the two bands from unrelated radii is what made an earlier version look
 * lopsided. Dim = off, comet = connecting, full = connected, signal = error.
 */
@Composable
private fun ConnectRing(state: State, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val n = LocalN.current
    val tick = rememberTick(strong = true)
    val connecting = state == State.Connecting
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(connecting) {
        if (!connecting) { spin = 0f; return@LaunchedEffect }
        while (true) withFrameMillis { spin = (it % 1600L) / 1600f }
    }
    val lit by animateColorAsState(
        when (state) { State.Connected -> n.text; State.Error -> n.accent; else -> n.muted },
        label = "ring",
    )
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(Radius.pill))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val pitch = 10.dp.toPx()
            val rDot = pitch * 0.28f
            val rOuter = size.minDimension / 2f - pitch * 1.4f
            val rInner = rOuter * 0.60f
            val cells = ceil((rOuter + pitch) / pitch).toInt()
            for (gy in -cells..cells) for (gx in -cells..cells) {
                val x = gx * pitch; val y = gy * pitch
                val r = sqrt(x * x + y * y)
                val outer = r <= rOuter && r >= rInner - pitch * 0.5f
                val inner = !outer && r >= rInner - pitch * 1.5f
                if (!outer && !inner) continue
                val f = ((atan2(y, x) + PI.toFloat() / 2f) / (2f * PI.toFloat()) + 1f) % 1f
                val base = when (state) { State.Connected -> 1f; State.Error -> 0.9f; else -> if (outer) 0.3f else 0.16f }
                var alpha = base
                if (connecting) {
                    val d = ((spin - f) % 1f + 1f) % 1f
                    alpha = if (d < 0.45f) 1f - d / 0.45f * 0.92f else base
                }
                drawCircle(lit.copy(alpha = alpha), rDot * if (outer) 1f else 0.8f, Offset(c.x + x, c.y + y))
            }
        }
        // State is a word, not a dot rendering: the ring is the instrument, the label is plain language.
        Text(
            when (state) { State.Idle -> "Off"; State.Connecting -> "Connecting"; State.Connected -> "On"; State.Error -> "Error" },
            // The state word is the ring's readout, so it is set in the body scale rather than a micro label -
            // it was too small to read at a glance.
            style = NType.label.copy(letterSpacing = 1.4.sp),
            color = if (state == State.Error) n.accent else n.text,
        )
    }
}

/** Quick toggle. Reads as a switch row, not a card - the label carries the meaning, the dot the state. */
@Composable
private fun Tile(label: String, value: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val n = LocalN.current
    NCard(modifier, onClick = onClick, radius = Radius.card) {
        Column(Modifier.padding(horizontal = Space.standard, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalDot(active, Modifier.size(5.dp))
                Spacer(Modifier.width(8.dp))
                NLabel(label, color = n.muted)
            }
            Spacer(Modifier.height(10.dp))
            Text(value, style = NType.title, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HomeScreen(store: Store, state: State, error: String?, a: Actions, onAdd: () -> Unit, busy: String? = null) {
    val ctx = LocalContext.current
    val n = LocalN.current
    val servers by store.servers.collectAsState()
    val selId by store.selectedId.collectAsState()
    val st by store.settings.collectAsState()
    val subs by store.subs.collectAsState()
    val since by LimooVpnService.connectedAt.collectAsState()
    val sel = servers.firstOrNull { it.id == selId } ?: servers.firstOrNull()
    val on = state == State.Connected
    val scope = rememberCoroutineScope()
    var pickOpen by remember { mutableStateOf(false) }
    var realMs by remember { mutableStateOf<Long?>(null) }
    var testing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val (traffic, exactTraffic) = rememberTraffic(on)

    // Measured through the running core, not through the local SOCKS port. The SOCKS inbound is now
    // omitted in VPN mode unless the user asks for it, so depending on st.socksPort here would make
    // the real-delay test fail on every default install. The core's own probe needs no listening port.
    suspend fun runTest() {
        val s = sel ?: return
        testing = true
        realMs = Latency.real(ctx, s, st, st.testUrl).takeIf { it > 0 }
            ?: Latency.tcp(s, st.pingTimeoutMs).takeIf { it > 0 }
        testing = false
    }
    LaunchedEffect(on, sel?.id) { realMs = null; if (on) { delay(1500); runTest() } }
    LaunchedEffect(on) { if (!on) return@LaunchedEffect; while (true) { now = System.currentTimeMillis(); delay(1000) } }

    fun tweak(msg: String, f: (AppSettings) -> AppSettings) {
        store.update(f)
        if (on) Ui.say("$msg - takes effect on reconnect", "Reconnect") { a.reconnect() }
    }

    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.card),
    ) {
        // ---- header: identity left, live state right, on one baseline ----
        Row(
            Modifier.fillMaxWidth().padding(top = Space.standard, bottom = Space.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Limoo", style = NType.display, color = n.text)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.privacyMode) NLabel("Private")
                SignalDot(on || state == State.Connecting, Modifier.size(5.dp))
            }
        }
        Ticks()

        // ---- hero ----
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectRing(state, enabled = sel != null, onClick = a.toggle, modifier = Modifier.fillMaxWidth(0.62f).padding(top = Space.section))
        }
        Spacer(Modifier.height(Space.standard))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (state) {
                State.Connected -> Text(fmtUptime(now - since), style = NType.mono.copy(fontSize = 20.sp), color = n.text)
                State.Connecting -> Text("Negotiating", style = NType.body, color = n.dim)
                State.Error -> {
                    // The old code did Text((error ?: "Failed").take(120)), which cut the message at
                    // exactly the character where the cause begins - the reported error ended
                    // "...with tag dire" and the actual reason ("unsupported domain strategy:
                    // IPIfNonMatch") sat just past the cut. Nothing is truncated now: a short hint the
                    // user can act on, then the FULL raw text, selectable and copyable.
                    val hint = ErrorHints.forError(error)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            hint?.title ?: (error ?: "Failed").lineSequence().firstOrNull { it.isNotBlank() } ?: "Failed",
                            style = NType.body, color = n.accent,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (hint != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                hint.detail, style = NType.bodySmall, color = n.muted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        NRow(
                            "COPY ERROR",
                            "SELECTABLE DETAILS BELOW",
                            {
                                clipboard.setText(AnnotatedString(error ?: "Failed"))
                                Ui.say("COPIED")
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                error ?: "Failed",
                                style = NType.mono.copy(fontSize = 10.sp), color = n.muted,
                                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
                State.Idle -> Text(
                    if (sel == null) "No server selected" else "Tap the ring to connect",
                    style = NType.body, color = n.muted,
                )
            }
        }

        if (sel == null) {
            Spacer(Modifier.height(Space.section))
            NCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.card)) {
                    Text("Add a server", style = NType.title, color = n.text)
                    Spacer(Modifier.height(Space.compact))
                    Text(
                        "Paste a link, scan a QR code, open a .limoo file, or add a subscription.",
                        style = NType.body, color = n.dim,
                    )
                    Spacer(Modifier.height(Space.standard))
                    NButton("Add server", onAdd, primary = true)
                }
            }
        } else {
            Spacer(Modifier.height(Space.section))

            // ---- current server ----
            NCard(Modifier.fillMaxWidth(), onClick = { pickOpen = true }) {
                Row(Modifier.padding(Space.standard), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NLabel(sel.group.ifEmpty { "Server" } + " - tap to switch")
                        Spacer(Modifier.height(6.dp))
                        Text(sel.name, style = NType.title, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${sel.protocol} / ${sel.network} / ${sel.security}".uppercase() + "  " + mask("${sel.host}:${sel.port}", st.privacyMode),
                            style = NType.micro, color = n.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(Space.small))
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clickable(enabled = on && !testing) { scope.launch { runTest() } },
                    ) {
                        val ms = if (on) (realMs ?: -1L) else sel.pingMs
                        DotMeter(ms, testing)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when { testing -> "..."; ms > 0 -> "$ms ms"; ms == 0L -> "Timeout"; else -> "- ms" },
                            style = NType.micro,
                            color = if (ms == 0L) n.accent else n.muted,
                        )
                    }
                }
            }

            // ---- live throughput: the number is the hero, the graphic supports it ----
            if (on) {
                Spacer(Modifier.height(Space.compact))
                NCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.standard)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                NLabel("Download")
                                Spacer(Modifier.height(Space.compact))
                                SpeedValue(fmtBytes(traffic.down) + "/s")
                            }
                            Column(Modifier.weight(1f)) {
                                NLabel("Upload")
                                Spacer(Modifier.height(Space.compact))
                                SpeedValue(fmtBytes(traffic.up) + "/s")
                            }
                        }
                        Spacer(Modifier.height(Space.standard))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(n.line))
                        Spacer(Modifier.height(Space.small))
                        Row(Modifier.fillMaxWidth()) {
                            NStat("Session down", fmtBytes(traffic.totalDown), Modifier.weight(1f))
                            NStat("Session up", fmtBytes(traffic.totalUp), Modifier.weight(1f))
                        }
                        if (exactTraffic) {
                            Spacer(Modifier.height(Space.small))
                            NLabel("Core counters", color = n.muted)
                        }
                    }
                }
            }

            Spacer(Modifier.height(Space.compact))
            UsageCard(sel.id)
            
            // ---- subscription allowance ----
            subs.firstOrNull { it.id == sel.subId }?.takeIf { it.total > 0 || it.expire > 0 }?.let { sub ->
                Spacer(Modifier.height(Space.compact))
                NCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.standard)) {
                        SubAllowance(
                            "${sub.name}  -  ${servers.count { it.subId == sub.id }} servers",
                            sub.upload, sub.download, sub.total, sub.expire,
                        )
                    }
                }
            }

            // ---- quick toggles: a 2x2 block on the grid, not four floating cards ----
            Spacer(Modifier.height(Space.section))
            NRule("Quick controls")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                Tile("Auto best", if (st.autoSelect) "On" else "Off", st.autoSelect, Modifier.weight(1f)) {
                    tweak("Auto best") { it.copy(autoSelect = !it.autoSelect) }
                }
                Tile("Route", st.routingPreset.removePrefix("bypass").replaceFirstChar { it.uppercase() }, st.routingPreset != "global", Modifier.weight(1f)) {
                    val order = listOf("global", "bypassIran", "bypassChina", "bypassRussia")
                    tweak("Route") { it.copy(routingPreset = order[(order.indexOf(it.routingPreset) + 1) % order.size]) }
                }
            }
            Spacer(Modifier.height(Space.small))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                Tile("Ad block", if (st.blockAds) "On" else "Off", st.blockAds, Modifier.weight(1f)) {
                    tweak("Ad block") { it.copy(blockAds = !it.blockAds) }
                }
                Tile("Fragment", if (st.fragment) "On" else "Off", st.fragment, Modifier.weight(1f)) {
                    tweak("Fragment") { it.copy(fragment = !it.fragment) }
                }
            }
        }

        if (busy != null) {
            Spacer(Modifier.height(Space.section))
            NCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Space.standard), verticalAlignment = Alignment.CenterVertically) {
                    SignalLoader(true)
                    Spacer(Modifier.width(Space.standard))
                    Text(busy, style = NType.body, color = n.text)
                }
            }
        }

        CrashNotice()
        GeoNotice(store)
        Spacer(Modifier.height(Space.hero))
    }

    if (pickOpen) ServerPickerSheet(store, { pickOpen = false }) { s ->
        pickOpen = false
        // store.select() already bounces the tunnel when connected, so no explicit reconnect here.
        if (s.id != sel?.id) store.select(s.id)
    }
}

/** If the last run ended in a crash, name it here instead of making the user reproduce it blind. */
@Composable
private fun CrashNotice() {
    val n = LocalN.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = app.limoo.core.Crash.last() }
    val t = text ?: return
    val ex = t.lineSequence().drop(1).firstOrNull { it.contains(':') }.orEmpty()
    Spacer(Modifier.height(Space.section))
    NCard(Modifier.fillMaxWidth(), highlight = true) {
        Column(Modifier.padding(Space.standard)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalDot(true, Modifier.size(5.dp))
                Spacer(Modifier.width(8.dp))
                NLabel("Last run crashed", color = n.accent)
            }
            Spacer(Modifier.height(Space.compact))
            Text(ex.take(180), style = NType.mono.copy(fontSize = 12.sp), color = n.text)
            Spacer(Modifier.height(Space.standard))
            NButton("Dismiss", { app.limoo.core.Crash.clear(); text = null }, compact = true)
        }
    }
}

/** Routing data status - only when it needs attention. */
@Composable
private fun GeoNotice(store: Store) {
    val ctx = LocalContext.current
    val n = LocalN.current
    val scope = rememberCoroutineScope()
    val p by GeoManager.progress.collectAsState()
    val st by store.settings.collectAsState()
    val ready = remember(p.running) { GeoManager.ready(ctx) }
    if (ready && !p.running && p.error == null) return
    Spacer(Modifier.height(Space.section))
    NCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.standard)) {
            NLabel("Routing data")
            Spacer(Modifier.height(Space.compact))
            Text(
                when {
                    p.running -> "Downloading ${p.file}"
                    p.error != null -> p.error!!
                    else -> "Not downloaded yet"
                },
                style = NType.body, color = if (p.error != null) n.accent else n.text,
            )
            if (p.running) {
                Spacer(Modifier.height(Space.small))
                SegmentedBar(p.percent / 100f)
            } else {
                Spacer(Modifier.height(Space.standard))
                NButton(if (ready) "Update" else "Download", { scope.launch { GeoManager.forceUpdate(ctx, st.geoSource) } }, compact = true)
            }
        }
    }
}

/** Quick server switch. */
@Composable
fun ServerPickerSheet(store: Store, onDismiss: () -> Unit, onPick: (Server) -> Unit) {
    val n = LocalN.current
    val servers by store.servers.collectAsState()
    val selId by store.selectedId.collectAsState()
    val st by store.settings.collectAsState()
    var q by remember { mutableStateOf("") }
    val list = remember(servers, q) {
        servers.filter { q.isBlank() || it.name.contains(q, true) || it.group.contains(q, true) }
            .sortedWith(compareByDescending<Server> { it.fav }.thenBy { if (it.pingMs > 0) it.pingMs else Long.MAX_VALUE })
    }
    NSheet(onDismiss) {
        NLabel("Switch server")
        Spacer(Modifier.height(Space.small))
        NSearch(q, { q = it })
        Spacer(Modifier.height(Space.compact))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(list, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.control)).clickable { onPick(s) }
                        .padding(vertical = 12.dp, horizontal = Space.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NRadio(s.id == (selId ?: servers.firstOrNull()?.id))
                    Spacer(Modifier.width(Space.small))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NLabel(s.group.ifEmpty { s.protocol } + "  " + mask(s.host, st.privacyMode))
                    }
                    DotMeter(s.pingMs, false)
                }
            }
        }
    }
}
