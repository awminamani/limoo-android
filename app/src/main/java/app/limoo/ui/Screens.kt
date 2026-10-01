package app.limoo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.limoo.Store
import app.limoo.core.GeoManager
import app.limoo.core.Latency
import app.limoo.core.LimooVpnService.State
import app.limoo.format.LinkBuilder
import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LimooTheme(st: AppSettings, content: @Composable () -> Unit) {
    val dark = when (st.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        st.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFB6D63B))
        else -> lightColorScheme(primary = Color(0xFF5E7A00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun LimooRoot(
    store: Store, state: State, error: String?, onToggle: () -> Unit, onPickFile: () -> Unit, onPasteImport: () -> Unit, onScan: () -> Unit,
    onShare: (all: Boolean, pw: String?, link: Boolean) -> Unit, onAddSub: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Home" to Icons.Default.Home, "Servers" to Icons.Default.Dns, "Settings" to Icons.Default.Settings).forEachIndexed { i, (l, ic) ->
                NavigationBarItem(tab == i, { tab = i }, { Icon(ic, l) }, label = { Text(l) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(store, state, error, onToggle)
                1 -> ServersScreen(store, onPickFile, onPasteImport, onScan, onShare, onAddSub)
                else -> SettingsScreen(store)
            }
        }
    }
}

fun fmtBytes(b: Long): String {
    val u = arrayOf("B", "KB", "MB", "GB"); var v = b.coerceAtLeast(0).toDouble(); var i = 0
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

@Composable
fun TrafficCard(active: Boolean) {
    var down by remember { mutableLongStateOf(0) }; var up by remember { mutableLongStateOf(0) }
    var totD by remember { mutableLongStateOf(0) }; var totU by remember { mutableLongStateOf(0) }
    LaunchedEffect(active) {
        down = 0; up = 0; totD = 0; totU = 0
        if (!active) return@LaunchedEffect
        val uid = Process.myUid()
        val r0 = TrafficStats.getUidRxBytes(uid); val t0 = TrafficStats.getUidTxBytes(uid); var lr = r0; var lt = t0
        while (true) {
            delay(1000)
            val r = TrafficStats.getUidRxBytes(uid); val t = TrafficStats.getUidTxBytes(uid)
            down = r - lr; up = t - lt; totD = r - r0; totU = t - t0; lr = r; lt = t
        }
    }
    if (!active) return
    Card(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text("Down ${fmtBytes(down)}/s"); Text("Up ${fmtBytes(up)}/s")
            }
            Text("Session: down ${fmtBytes(totD)}, up ${fmtBytes(totU)} (approx., includes protocol overhead)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun GeoStatus(store: Store) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val p by GeoManager.progress.collectAsState(); val st by store.settings.collectAsState()
    val ready = remember(p.running) { GeoManager.ready(ctx) }
    Column(Modifier.fillMaxWidth()) {
        Text(when { p.running -> "Downloading ${p.file}... ${p.percent}%"; ready -> "Routing data ready (${GeoManager.ageDays(ctx)} d old)"; else -> "Routing data missing" },
            style = MaterialTheme.typography.bodyMedium)
        if (p.running) LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!p.running) TextButton({ scope.launch { GeoManager.forceUpdate(ctx, st.geoSource) } }) { Text(if (ready) "Update now" else "Download") }
    }
}

@Composable
fun HomeScreen(store: Store, state: State, error: String?, onToggle: () -> Unit) {
    val servers by store.servers.collectAsState(); val selId by store.selectedId.collectAsState(); val st by store.settings.collectAsState()
    val sel = servers.firstOrNull { it.id == selId } ?: servers.firstOrNull()
    val on = state == State.Connected
    val scope = rememberCoroutineScope(); var testMs by remember { mutableStateOf<Long?>(null) }; var testing by remember { mutableStateOf(false) }
    LaunchedEffect(on) { testMs = null }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Limoo", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        FilledIconButton(onToggle, Modifier.size(120.dp), enabled = sel != null,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) {
            Icon(Icons.Default.PowerSettingsNew, "Connect", Modifier.size(56.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(when (state) { State.Idle -> "Disconnected"; State.Connecting -> "Connecting..."; State.Connected -> "Connected"; State.Error -> "Error" })
        Text(sel?.let { "${it.name}  -  ${it.protocol}/${it.network}/${it.security}" } ?: "No server. Import one from the Servers tab.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (on) {
            TextButton({ scope.launch { testing = true; testMs = Latency.viaProxy(st.socksPort, st.testUrl); testing = false } }, enabled = !testing) {
                Text(when { testing -> "Testing..."; testMs == null -> "Test connection"; testMs == 0L -> "Test failed - retry"; else -> "${testMs} ms - test again" })
            }
        }
        TrafficCard(on)
        Spacer(Modifier.height(16.dp)); GeoStatus(store)
    }
}

@Composable
fun ServersScreen(store: Store, onPickFile: () -> Unit, onPaste: () -> Unit, onScan: () -> Unit, onShare: (Boolean, String?, Boolean) -> Unit, onAddSub: (String) -> Unit) {
    val servers by store.servers.collectAsState(); val selId by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var shareOpen by remember { mutableStateOf(false) }; var subOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Server?>(null) }; var adding by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<Server?>(null) }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onPaste) { Text("Paste") }
            FilledTonalButton(onScan) { Text("Scan QR") }
            FilledTonalButton(onPickFile) { Text(".limoo") }
            FilledTonalButton({ adding = true }) { Text("Add") }
            FilledTonalButton({ subOpen = true }) { Text("Sub") }
            FilledTonalButton({ shareOpen = true }) { Text("Share") }
            FilledTonalButton({ scope.launch { busy = true; store.pingAll(); busy = false } }, enabled = !busy) { Text(if (busy) "Pinging..." else "Ping all") }
            FilledTonalButton({ scope.launch { busy = true; store.autoSelectBest(); busy = false } }, enabled = !busy) { Text("Best") }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(servers, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { store.select(s.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton((selId ?: servers.firstOrNull()?.id) == s.id, { store.select(s.id) })
                    Column(Modifier.weight(1f)) {
                        Text(s.name, maxLines = 1)
                        Text("${s.protocol} / ${s.network} / ${s.security}  ${s.host}:${s.port}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    if (s.pingMs >= 0) Text(if (s.pingMs == 0L) "timeout" else "${s.pingMs} ms", style = MaterialTheme.typography.labelMedium,
                        color = if (s.pingMs == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Edit") }, { menu = false; editing = s })
                            DropdownMenuItem({ Text("QR code") }, { menu = false; qr = s })
                            DropdownMenuItem({ Text("Copy link") }, {
                                menu = false
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", LinkBuilder.build(s)))
                                Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                            })
                            DropdownMenuItem({ Text("Test latency") }, { menu = false; scope.launch { store.setPing(s.id, Latency.tcp(s)) } })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; store.remove(s.id) })
                        }
                    }
                }
            }
        }
    }
    if (adding) ServerEditor(null, { store.upsert(it); adding = false }, { adding = false })
    editing?.let { e -> ServerEditor(e, { store.upsert(it); editing = null }, { editing = null }) }
    qr?.let { QrDialog(it) { qr = null } }
    if (subOpen) {
        var url by remember { mutableStateOf("") }
        AlertDialog({ subOpen = false }, title = { Text("Add subscription") },
            text = { OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true) },
            confirmButton = { TextButton({ subOpen = false; onAddSub(url.trim()) }) { Text("Add") } },
            dismissButton = { TextButton({ subOpen = false }) { Text("Cancel") } })
    }
    if (shareOpen) {
        var all by remember { mutableStateOf(false) }; var pw by remember { mutableStateOf("") }
        AlertDialog({ shareOpen = false }, title = { Text("Share as .limoo") },
            text = {
                Column {
                    SwitchRow("All servers (off = selected only)", all) { all = it }
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional)") }, singleLine = true)
                }
            },
            confirmButton = { Row {
                TextButton({ shareOpen = false; onShare(all, pw.ifEmpty { null }, true) }) { Text("Link") }
                TextButton({ shareOpen = false; onShare(all, pw.ifEmpty { null }, false) }) { Text("File") } } },
            dismissButton = { TextButton({ shareOpen = false }) { Text("Cancel") } })
    }
}
