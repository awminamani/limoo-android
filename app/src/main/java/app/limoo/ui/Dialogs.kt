package app.limoo.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.limoo.format.LimooFile
import app.limoo.format.LimooPayload
import app.limoo.format.LinkBuilder
import app.limoo.model.Server
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- reusable rows ----------
@Composable fun Section(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(checked, onChange)
    }

@Composable fun ChoiceRow(title: String, options: List<String>, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Box {
            Text(value.ifEmpty { "(none)" }, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(open, { open = false }) { options.forEach { o -> DropdownMenuItem({ Text(o.ifEmpty { "(none)" }) }, { onPick(o); open = false }) } }
        }
    }
}

@Composable fun TextRow(title: String, value: String, multiline: Boolean = false, onChange: (String) -> Unit) {
    var t by remember(title) { mutableStateOf(value) }
    OutlinedTextField(t, { t = it; onChange(it) }, label = { Text(title) }, singleLine = !multiline, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

// ---------- per-server editor ----------
@Composable
fun ServerEditor(initial: Server?, onSave: (Server) -> Unit, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(initial ?: Server(name = "New server", protocol = "vless", host = "", port = 443, network = "tcp", security = "tls")) }
    val userIdLabel = if (s.protocol == "vless" || s.protocol == "vmess") "UUID" else "Password"
    AlertDialog(onDismiss, title = { Text(if (initial == null) "Add server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextRow("Name", s.name) { s = s.copy(name = it) }
                ChoiceRow("Protocol", listOf("vless", "vmess", "trojan", "shadowsocks"), s.protocol) { s = s.copy(protocol = it) }
                TextRow("Host", s.host) { s = s.copy(host = it.trim()) }
                TextRow("Port", s.port.toString()) { v -> v.toIntOrNull()?.let { s = s.copy(port = it) } }
                TextRow(userIdLabel, s.uuid) { s = s.copy(uuid = it.trim()) }
                if (s.protocol == "vmess" || s.protocol == "shadowsocks") TextRow("Method / security", s.method) { s = s.copy(method = it.trim()) }
                if (s.protocol == "vless") ChoiceRow("Flow", listOf("", "xtls-rprx-vision"), s.flow) { s = s.copy(flow = it) }
                ChoiceRow("Network", listOf("tcp", "ws", "grpc", "xhttp", "httpupgrade"), s.network) { s = s.copy(network = it) }
                ChoiceRow("Security", listOf("none", "tls", "reality"), s.security) { s = s.copy(security = it) }
                if (s.security != "none") {
                    TextRow("SNI", s.sni) { s = s.copy(sni = it.trim()) }
                    ChoiceRow("Fingerprint", listOf("chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized"), s.fp) { s = s.copy(fp = it) }
                    TextRow("ALPN (comma separated)", s.alpn) { s = s.copy(alpn = it.trim()) }
                    SwitchRow("Allow insecure", s.allowInsecure) { s = s.copy(allowInsecure = it) }
                }
                if (s.security == "reality") {
                    TextRow("Public key", s.pbk) { s = s.copy(pbk = it.trim()) }
                    TextRow("Short ID", s.sid) { s = s.copy(sid = it.trim()) }
                    TextRow("SpiderX", s.spx) { s = s.copy(spx = it.trim()) }
                }
                if (s.network in listOf("ws", "httpupgrade", "xhttp")) {
                    TextRow("Path", s.path) { s = s.copy(path = it.trim()) }
                    TextRow("Host header", s.hostHeader) { s = s.copy(hostHeader = it.trim()) }
                }
                if (s.network == "xhttp") ChoiceRow("XHTTP mode", listOf("", "auto", "packet-up", "stream-up", "stream-one"), s.xhttpMode) { s = s.copy(xhttpMode = it) }
                if (s.network == "grpc") TextRow("gRPC service name", s.serviceName) { s = s.copy(serviceName = it.trim()) }
                TextRow("Note", s.note, multiline = true) { s = s.copy(note = it) }
            }
        },
        confirmButton = { TextButton({ onSave(s) }, enabled = s.host.isNotBlank() && s.uuid.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ---------- QR export ----------
private fun qrBitmap(text: String, size: Int = 720): Bitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
} catch (e: Exception) { null }

@Composable
fun QrDialog(server: Server, onDismiss: () -> Unit) {
    var asLimoo by remember { mutableStateOf(false) }
    val text = if (asLimoo) LimooFile.toDeepLink(LimooPayload(name = server.name, servers = listOf(server.copy(pingMs = -1)))) else LinkBuilder.build(server)
    val bmp = remember(text) { qrBitmap(text) }
    val clip = LocalClipboardManager.current
    AlertDialog(onDismiss, title = { Text(server.name, maxLines = 1) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) Image(bmp.asImageBitmap(), "QR code", Modifier.size(260.dp)) else Text("Too long for a QR code - share as a file instead.")
                SwitchRow(".limoo link (off = standard link)", asLimoo) { asLimoo = it }
                Text(text, maxLines = 3, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ clip.setText(AnnotatedString(text)) }) { Text("Copy") } },
        dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

// ---------- per-app picker ----------
@Composable
fun AppPickerDialog(selected: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var sel by remember { mutableStateOf(selected.toSet()) }; var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() }
        }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Search apps") }, singleLine = true)
                    Spacer(Modifier.width(8.dp)); Button({ onDone(sel.toList()) }) { Text("Done (${sel.size})") }
                }
                val list = apps?.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                else LazyColumn { items(list, key = { it.first }) { (pkg, label) ->
                    val icon = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
                    Row(Modifier.fillMaxWidth().clickable { sel = if (pkg in sel) sel - pkg else sel + pkg }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(label, maxLines = 1); Text(pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        Checkbox(pkg in sel, { sel = if (pkg in sel) sel - pkg else sel + pkg })
                    }
                } }
            }
        }
    }
}
