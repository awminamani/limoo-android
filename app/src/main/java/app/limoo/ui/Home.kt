package app.limoo.ui

import android.net.TrafficStats
import android.os.Process
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.limoo.Store
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
import kotlin.math.cos
import kotlin.math.sin

fun fmtBytes(b: Long): String {
    val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = b.coerceAtLeast(0).toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return (if (i == 0) "%.0f %s" else "%.1f %s").format(v, u[i])
}

fun fmtUptime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }

fun mask(s: String, on: Boolean) = if (!on) s else if (s.length <= 4) "****" else s.take(2) + "***" + s.takeLast(2)

class Traffic(val down: Long = 0, val up: Long = 0, val totalDown: Long = 0, val totalUp: Long = 0)

/**
 * Speed and session totals. Prefers the core's own counters (exact, excludes protocol overhead); falls back
 * to Android's per-app counters when the core is not running or does not report stats.
 * [exact] tells the UI which source is in use so it can say so.
 */
@Composable
fun rememberTraffic(active: Boolean): Pair<Traffic, Boolean> {
    var t by remember { mutableStateOf(Traffic()) }
    var exact by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        t = Traffic(); exact = false
        if (!active) return@LaunchedEffect
        val uid = Process.myUid()
        val r0 = TrafficStats.getUidRxBytes(uid); val t0 = TrafficStats.getUidTxBytes(uid); var lr = r0; var lt = t0
        var cr = 0L; var cu = 0L
        while (true) {
            delay(1000)
            val core = LimooVpnService.traffic.value
            if (core != null) {
                exact = true
                t = Traffic(core.down - cr, core.up - cu, core.down, core.up); cr = core.down; cu = core.up
            } else {
                val r = TrafficStats.getUidRxBytes(uid); val x = TrafficStats.getUidTxBytes(uid)
                t = Traffic(r - lr, x - lt, r - r0, x - t0); lr = r; lt = x
            }
        }
    }
    return t to exact
}

/** The hero control: a ring of dots. Dim = off, comet = connecting, full = connected, red = error. */
@Composable
private fun ConnectRing(state: State, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val n = LocalN.current; val tick = rememberTick(strong = true)
    val connecting = state == State.Connecting
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(connecting) {
        if (!connecting) { spin = 0f; return@LaunchedEffect }
        while (true) withFrameMillis { spin = (it % 1400L) / 1400f }
    }
    val lit by animateColorAsState(
        when (state) { State.Connected -> n.text; State.Error -> n.accent; else -> n.dim }, label = "ringColor",
    )
    Box(
        modifier.aspectRatio(1f).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val pitch = 11.dp.toPx()                       // one lattice pitch for every dot on screen
            val rDot = pitch * 0.30f
            val rOuter = size.minDimension / 2f - pitch
            val rInner = rOuter * 0.62f
            // Every dot is placed on a square lattice centred on the canvas, then kept only if its radius
            // falls in the ring band. Deriving positions from one pitch is what keeps the ring symmetric:
            // the previous version used two unrelated radii (outer and outer*0.80), so the inner dots sat
            // off-grid against the outer ones and the ring read as lopsided.
            val reach = rOuter + pitch
            val cells = ceil(reach / pitch).toInt()
            for (gy in -cells..cells) for (gx in -cells..cells) {
                val x = gx * pitch; val y = gy * pitch
                val r = sqrt(x * x + y * y)
                val inOuter = r <= rOuter && r >= rInner - pitch * 0.5f
                val inInner = r < rInner - pitch * 0.5f && r >= rInner - pitch * 1.45f
                if (!inOuter && !inInner) continue
                // Phase runs clockwise from 12 o'clock, in the same 0..1 space as the connect animation.
                val f = ((atan2(y, x) + PI.toFloat() / 2f) / (2f * PI.toFloat()) + 1f) % 1f
                val base = when (state) { State.Connected -> 1f; State.Error -> .92f; else -> if (inOuter) .26f else .13f }
                var alpha = base
                if (connecting) {
                    val d = ((spin - f) % 1f + 1f) % 1f
                    alpha = if (d < .42f) 1f - d / .42f * .9f else base
                }
                // Outer band reads slightly brighter so the ring has depth without extra colour.
                drawCircle(lit.copy(alpha = alpha), rDot * if (inOuter) 1f else .82f, Offset(c.x + x, c.y + y))
            }
        }
        DotText(
            when (state) { State.Idle -> "OFF"; State.Connecting -> "..."; State.Connected -> "ON"; State.Error -> "ERR" },
            dot = 7.dp, gap = 3.dp, spacing = 2, color = if (state == State.Error) n.accent else n.text,
        )
    }
}

@Composable
private fun Tile(label: String, value: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val n = LocalN.current
    NCard(modifier, onClick = onClick, radius = 24.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) n.accent else n.line)); Spacer(Modifier.width(8.dp)); NLabel(label)
            }
            Text(value.uppercase(), style = NType.mono, color = n.text, modifier = Modifier.padding(top = 10.dp), maxLines = 1)
        }
    }
}

@Composable
fun HomeScreen(store: Store, state: State, error: String?, a: Actions, onAdd: () -> Unit, busy: String? = null) {
    val n = LocalN.current
    val servers by store.servers.collectAsState(); val selId by store.selectedId.collectAsState()
    val st by store.settings.collectAsState(); val subs by store.subs.collectAsState()
    val since by LimooVpnService.connectedAt.collectAsState()
    val sel = servers.firstOrNull { it.id == selId } ?: servers.firstOrNull()
    val on = state == State.Connected
    val scope = rememberCoroutineScope(); var pickOpen by remember { mutableStateOf(false) }
    var realMs by remember { mutableStateOf<Long?>(null) }; var testing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val (traffic, exactTraffic) = rememberTraffic(on)

    suspend fun runTest() { testing = true; realMs = Latency.viaProxy(st.socksPort, st.testUrl); testing = false }
    LaunchedEffect(on, sel?.id) { realMs = null; if (on) { delay(1500); runTest() } }
    LaunchedEffect(on) { if (!on) return@LaunchedEffect; while (true) { now = System.currentTimeMillis(); delay(1000) } }

    fun tweak(msg: String, f: (AppSettings) -> AppSettings) {
        store.update(f)
        if (on) Ui.say("$msg - APPLIES ON RECONNECT", "RECONNECT") { a.reconnect() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DotText("LIMOO", dot = 3.dp, gap = 1.5.dp)
            Spacer(Modifier.weight(1f))
            NLabel(st.routingPreset.removePrefix("bypass").ifEmpty { "ALL" }.uppercase() + if (st.privacyMode) " - PRIVATE" else "")
        }

        ConnectRing(state, enabled = sel != null, onClick = a.toggle, modifier = Modifier.fillMaxWidth(.74f).padding(top = 20.dp))

        Spacer(Modifier.height(14.dp))
        when (state) {
            State.Connected -> DotText(fmtUptime(now - since), dot = 3.dp, gap = 1.5.dp)
            State.Connecting -> NLabel("CONNECTING")
            State.Error -> Text((error ?: "Failed").take(140), style = NType.mono, color = n.accent)
            State.Idle -> NLabel(if (sel == null) "NO SERVER YET" else "TAP THE RING TO CONNECT")
        }
        Spacer(Modifier.height(22.dp))

        if (sel == null) {
            NCard(Modifier.fillMaxWidth(), onClick = onAdd) {
                Column(Modifier.padding(24.dp)) {
                    Text("Add your first server", style = NType.title, color = n.text)
                    Text("Paste a link, scan a QR code, open a .limoo file or add a subscription.", style = NType.body, color = n.dim, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    NButton("ADD SERVER", onAdd, primary = true)
                }
            }
        } else {
            NCard(Modifier.fillMaxWidth(), onClick = { pickOpen = true }) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NLabel(sel.group.ifEmpty { "SERVER" } + " - TAP TO SWITCH")
                        Text(sel.name, style = NType.title, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            "${sel.protocol} - ${sel.network} - ${sel.security}".uppercase() + "  " + mask("${sel.host}:${sel.port}", st.privacyMode),
                            style = NType.label, color = n.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable(enabled = on && !testing) { scope.launch { runTest() } }) {
                        val ms = if (on) (realMs ?: -1L) else sel.pingMs
                        DotMeter(ms, testing)
                        Text(
                            when { testing -> "..."; ms > 0 -> "$ms MS"; ms == 0L -> "TIMEOUT"; else -> "- MS" }, style = NType.label,
                            color = if (ms == 0L) n.accent else n.dim, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            if (on) {
                Spacer(Modifier.height(10.dp))
                NCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            // Fixed 150dp slots: the pitch auto-scales inside, so the numbers never clip and
                            // the row never changes height as the values change.
                            Column(Modifier.weight(1f)) {
                                NLabel("DOWN")
                                DotTextFixed("↓ " + fmtBytes(traffic.down) + "/S", 150.dp, 16.dp, Modifier.padding(top = 8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                NLabel("UP")
                                DotTextFixed("↑ " + fmtBytes(traffic.up) + "/S", 150.dp, 16.dp, Modifier.padding(top = 8.dp))
                            }
                        }
                        Text(
                            ("SESSION  DOWN ${fmtBytes(traffic.totalDown)}  UP ${fmtBytes(traffic.totalUp)}" + if (exactTraffic) "  EXACT" else "").uppercase(),
                            style = NType.label, color = n.dim, modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }

            subs.firstOrNull { it.id == sel.subId }?.takeIf { it.total > 0 }?.let { sub ->
                val used = sub.upload + sub.download
                Spacer(Modifier.height(10.dp))
                NCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Row { NLabel("DATA - ${sub.name}", Modifier.weight(1f)); NLabel("${fmtBytes(used)} / ${fmtBytes(sub.total)}") }
                        DotBar(used.toFloat() / sub.total, Modifier.padding(top = 12.dp))
                        if (sub.expire > 0) {
                            val days = (sub.expire - System.currentTimeMillis() / 1000) / 86400
                            NLabel(if (days < 0) "EXPIRED" else "$days DAYS LEFT", Modifier.padding(top = 10.dp), color = if (days < 3) n.accent else n.dim)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("AUTO BEST", if (st.autoSelect) "ON" else "OFF", st.autoSelect, Modifier.weight(1f)) { tweak("AUTO BEST") { it.copy(autoSelect = !it.autoSelect) } }
                Tile("ROUTE", st.routingPreset.removePrefix("bypass").ifEmpty { "ALL" }.let { if (it == "global") "ALL" else it }, st.routingPreset != "global", Modifier.weight(1f)) {
                    val order = listOf("global", "bypassIran", "bypassChina", "bypassRussia")
                    tweak("ROUTE") { it.copy(routingPreset = order[(order.indexOf(it.routingPreset) + 1) % order.size]) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("AD BLOCK", if (st.blockAds) "ON" else "OFF", st.blockAds, Modifier.weight(1f)) { tweak("AD BLOCK") { it.copy(blockAds = !it.blockAds) } }
                Tile("FRAGMENT", if (st.fragment) "ON" else "OFF", st.fragment, Modifier.weight(1f)) { tweak("FRAGMENT") { it.copy(fragment = !it.fragment) } }
            }
        }

        CrashNotice()

        GeoNotice(store)
        Spacer(Modifier.height(24.dp))
    }

    if (pickOpen) ServerPickerSheet(store, { pickOpen = false }) { s ->
        pickOpen = false
        if (s.id != sel?.id) { store.select(s.id); if (on || state == State.Connecting) a.reconnect() }
    }
}

/**
 * If the last run ended in a crash, say so here rather than making the user reproduce it blind. Shows the
 * first line of the recorded failure plus its exception type, which is usually enough to name the cause.
 */
@Composable
private fun CrashNotice() {
    val n = LocalN.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = app.limoo.core.Crash.last() }
    val t = text ?: return
    val first = t.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    val ex = t.lineSequence().drop(1).firstOrNull { it.contains(':') }.orEmpty()
    Spacer(Modifier.height(10.dp))
    NCard(Modifier.fillMaxWidth(), radius = 24.dp, highlight = true) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            NLabel("LAST RUN CRASHED")
            Text(ex.take(160), style = NType.mono, color = n.accent, modifier = Modifier.padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            NButton("DISMISS", { app.limoo.core.Crash.clear(); text = null }, Modifier.padding(top = 12.dp), compact = true)
        }
    }
}

/** Shown on Home only when routing data needs attention. */
@Composable
private fun GeoNotice(store: Store) {
    val ctx = LocalContext.current; val n = LocalN.current; val scope = rememberCoroutineScope()
    val p by GeoManager.progress.collectAsState(); val st by store.settings.collectAsState()
    val ready = remember(p.running) { GeoManager.ready(ctx) }
    if (ready && !p.running && p.error == null) return
    Spacer(Modifier.height(10.dp))
    NCard(Modifier.fillMaxWidth(), radius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            NLabel("ROUTING DATA")
            Text(
                when { p.running -> "Downloading ${p.file}  ${p.percent}%"; p.error != null -> p.error!!; else -> "Not downloaded yet" },
                style = NType.mono, color = if (p.error != null) n.accent else n.text, modifier = Modifier.padding(top = 6.dp),
            )
            if (p.running) DotBar(p.percent / 100f, Modifier.padding(top = 12.dp))
            else NButton("DOWNLOAD", { scope.launch { GeoManager.forceUpdate(ctx, st.geoSource) } }, Modifier.padding(top = 12.dp), compact = true)
        }
    }
}

/** Quick server switch from Home. */
@Composable
fun ServerPickerSheet(store: Store, onDismiss: () -> Unit, onPick: (Server) -> Unit) {
    val n = LocalN.current
    val servers by store.servers.collectAsState(); val selId by store.selectedId.collectAsState(); val st by store.settings.collectAsState()
    var q by remember { mutableStateOf("") }
    val list = remember(servers, q) {
        servers.filter { q.isBlank() || it.name.contains(q, true) || it.group.contains(q, true) }
            .sortedWith(compareByDescending<Server> { it.fav }.thenBy { if (it.pingMs > 0) it.pingMs else Long.MAX_VALUE })
    }
    NSheet(onDismiss) {
        NLabel("SWITCH SERVER")
        Spacer(Modifier.height(10.dp)); NSearch(q, { q = it }); Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(list, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onPick(s) }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    NRadio(s.id == (selId ?: servers.firstOrNull()?.id)); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (s.fav) "* " else "") + s.name, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NLabel(s.group.ifEmpty { s.protocol } + "  " + mask(s.host, st.privacyMode))
                    }
                    DotMeter(s.pingMs, false)
                }
            }
        }
    }
}
