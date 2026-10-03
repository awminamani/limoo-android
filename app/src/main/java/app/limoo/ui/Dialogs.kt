package app.limoo.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.limoo.LimooApp
import app.limoo.format.LimooFile
import app.limoo.format.LimooPayload
import app.limoo.format.LinkBuilder
import app.limoo.format.LinkParser
import app.limoo.model.Server
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================== server editor (full screen) ==============================

@Composable
fun ServerEditor(initial: Server?, onSave: (Server) -> Unit, onDismiss: () -> Unit) {
    val n = LocalN.current; val clip = LocalClipboardManager.current
    var s by remember { mutableStateOf(initial ?: Server(name = "New server", protocol = "vless", host = "", port = 443, network = "tcp", security = "tls")) }
    var portText by remember { mutableStateOf(s.port.toString()) }
    val idLabel = if (s.protocol == "vless" || s.protocol == "vmess") "UUID" else "PASSWORD"
    val problem = when {
        s.host.isBlank() -> "HOST IS REQUIRED"; s.uuid.isBlank() -> "$idLabel IS REQUIRED"; s.port !in 1..65535 -> "PORT MUST BE 1-65535"
        s.security == "reality" && s.pbk.isBlank() -> "REALITY NEEDS A PUBLIC KEY"; else -> ""
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = n.bg) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NButton("BACK", onDismiss, compact = true); Spacer(Modifier.width(12.dp))
                    Text(if (initial == null) "New server" else "Edit server", style = NType.title, color = LocalN.current.text); Spacer(Modifier.weight(1f))
                    NButton("SAVE", { onSave(s) }, primary = true, enabled = problem.isEmpty(), compact = true)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                    if (initial == null) {
                        NButton("FILL FROM CLIPBOARD LINK", {
                            val l = clip.getText()?.text?.lines()?.firstOrNull { it.contains("://") }?.trim()
                            LinkParser.parse(l ?: "")?.let { s = it.copy(id = s.id); portText = it.port.toString() }
                        }, Modifier.fillMaxWidth(), compact = true)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (problem.isNotEmpty()) NLabel(problem, Modifier.padding(vertical = 4.dp), color = n.accent)
                    NField("NAME", s.name, { s = s.copy(name = it) })
                    NField("GROUP (OPTIONAL)", s.group, { s = s.copy(group = it, subId = "") })
                    ChoiceRow("Protocol", listOf("vless", "vmess", "trojan", "shadowsocks"), s.protocol) { s = s.copy(protocol = it) }
                    NField("HOST", s.host, { s = s.copy(host = it.trim()) })
                    NField("PORT", portText, { v -> portText = v; s = s.copy(port = v.toIntOrNull() ?: 0) }, keyboard = KeyboardType.Number)
                    NField(idLabel, s.uuid, { s = s.copy(uuid = it.trim()) })
                    if (s.protocol == "vmess" || s.protocol == "shadowsocks") NField("METHOD / SECURITY", s.method, { s = s.copy(method = it.trim()) })
                    if (s.protocol == "vless") ChoiceRow("Flow", listOf("", "xtls-rprx-vision"), s.flow) { s = s.copy(flow = it) }
                    ChoiceRow("Network", listOf("tcp", "ws", "grpc", "xhttp", "httpupgrade"), s.network) { s = s.copy(network = it) }
                    ChoiceRow("Security", listOf("none", "tls", "reality"), s.security) { s = s.copy(security = it) }
                    if (s.security != "none") {
                        NField("SNI", s.sni, { s = s.copy(sni = it.trim()) })
                        ChoiceRow("Fingerprint", listOf("chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized"), s.fp) { s = s.copy(fp = it) }
                        NField("ALPN (COMMA SEPARATED)", s.alpn, { s = s.copy(alpn = it.trim()) })
                        ToggleRow("Allow insecure", s.allowInsecure) { s = s.copy(allowInsecure = it) }
                    }
                    if (s.security == "reality") {
                        NField("PUBLIC KEY", s.pbk, { s = s.copy(pbk = it.trim()) })
                        NField("SHORT ID", s.sid, { s = s.copy(sid = it.trim()) })
                        NField("SPIDERX", s.spx, { s = s.copy(spx = it.trim()) })
                    }
                    if (s.network in listOf("ws", "httpupgrade", "xhttp")) {
                        NField("PATH", s.path, { s = s.copy(path = it.trim()) })
                        NField("HOST HEADER", s.hostHeader, { s = s.copy(hostHeader = it.trim()) })
                    }
                    if (s.network == "xhttp") ChoiceRow("XHTTP mode", listOf("", "auto", "packet-up", "stream-up", "stream-one"), s.xhttpMode) { s = s.copy(xhttpMode = it) }
                    if (s.network == "grpc") NField("GRPC SERVICE NAME", s.serviceName, { s = s.copy(serviceName = it.trim()) })
                    NField("NOTE", s.note, { s = s.copy(note = it) }, multiline = true)
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

// ============================== QR ==============================

private fun qrBitmap(text: String, size: Int = 720): Bitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
} catch (e: Exception) { null }

@Composable
fun QrDialog(server: Server, onDismiss: () -> Unit) {
    val n = LocalN.current; val clip = LocalClipboardManager.current
    var asLimoo by remember { mutableStateOf(false) }
    // v2 payload, same as a shared file: servers plus the sender's look, minus the image (a content:// URI
    // would mean nothing on the receiving device).
    val st = LocalContext.current.let { c -> (c.applicationContext as? LimooApp)?.store?.settings?.value }
    val text = if (asLimoo) LimooFile.toDeepLink(
        LimooPayload(
            name = server.name,
            servers = listOf(server.copy(pingMs = -1, fav = false, subId = "")),
            appearance = st?.let { LimooFile.appearanceOf(it) },
        ),
    ) else LinkBuilder.build(server)
    val bmp = remember(text) { qrBitmap(text) }
    Dialog(onDismiss) {
        NCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                NLabel("QR CODE"); Text(server.name, style = NType.title, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                if (bmp != null) Image(bmp.asImageBitmap(), "QR code", Modifier.size(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp))
                else Text("Too long for a QR code. Share it as a file instead.", style = NType.body, color = n.dim)
                Row(Modifier.fillMaxWidth().clickable { asLimoo = !asLimoo }.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(".limoo link instead of standard", style = NType.body, color = n.text, modifier = Modifier.weight(1f)); NSwitch(asLimoo) { asLimoo = it }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NButton("COPY", { clip.setText(AnnotatedString(text)); Ui.say("COPIED") }, Modifier.weight(1f))
                    NButton("CLOSE", onDismiss, Modifier.weight(1f), primary = true)
                }
            }
        }
    }
}

// ============================== per-app picker ==============================

@Composable
fun AppPickerDialog(selected: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val n = LocalN.current; val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Triple<String, String, Boolean>>?>(null) }   // package, label, isSystem
    var sel by remember { mutableStateOf(selected.toSet()) }; var query by remember { mutableStateOf("") }; var system by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { Triple(it.packageName, pm.getApplicationLabel(it).toString(), (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) }
        }
    }
    val initial = remember { selected.toSet() }
    val list = remember(apps, query, system) {
        apps?.filter { (p, l, sys) -> (system || !sys || p in initial) && (query.isBlank() || l.contains(query, true) || p.contains(query, true)) }
            ?.sortedWith(compareByDescending<Triple<String, String, Boolean>> { it.first in initial }.thenBy { it.second.lowercase() })
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = n.bg) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    NButton("BACK", onDismiss, compact = true); Spacer(Modifier.weight(1f))
                    NButton("DONE ${sel.size}", { onDone(sel.toList()) }, primary = true, compact = true)
                }
                NSearch(query, { query = it })
                Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NChip("SYSTEM APPS", system) { system = !system }
                    NChip("CLEAR", false) { sel = emptySet() }
                }
                if (list == null) NLabel("LOADING APPS", Modifier.padding(top = 16.dp))
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.first }) { (pkg, label, _) ->
                        val icon = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { sel = if (pkg in sel) sel - pkg else sel + pkg }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) Image(icon, null, Modifier.size(38.dp)) else Spacer(Modifier.size(38.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(label, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis); NLabel(pkg)
                            }
                            NCheck(pkg in sel)
                        }
                    }
                }
            }
        }
    }
}
