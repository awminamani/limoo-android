package app.limoo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.limoo.LimooApp
import app.limoo.core.GeoManager
import app.limoo.core.LimooVpnService
import app.limoo.format.LimooFile
import app.limoo.format.LimooPayload
import app.limoo.format.LinkParser
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val store get() = (application as LimooApp).store
    private var pendingImport by mutableStateOf<String?>(null)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) startVpn() }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { read(it)?.let(::importText) } }
    private val scanner = registerForActivityResult(ScanContract()) { r -> r.contents?.let(::importText) }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // First open: download geoip/geosite automatically; later opens refresh weekly. Also refresh subscriptions.
        lifecycleScope.launch {
            val st = store.settings.value
            GeoManager.ensure(applicationContext, st.geoSource, if (st.geoAutoUpdate) 7 else Long.MAX_VALUE)
            store.refreshAll()
        }
        setContent {
            val st by store.settings.collectAsState()
            LimooTheme(st) {
                Surface(Modifier.fillMaxSize()) {
                    LimooRoot(store, LimooVpnService.state.collectAsState().value, LimooVpnService.error.collectAsState().value,
                        onToggle = ::toggle, onPickFile = { filePicker.launch(arrayOf("*/*")) }, onPasteImport = ::importClipboard,
                        onScan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a Limoo / VLESS / VMess QR").setBeepEnabled(false).setOrientationLocked(false)) },
                        onShare = ::share,
                        onAddSub = { url -> lifecycleScope.launch { runCatching { store.addSubscription(url) }.onFailure { toast(it.message ?: "Failed") } } })
                    pendingImport?.let { text ->
                        var pw by remember { mutableStateOf("") }
                        AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Password required") },
                            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true) },
                            confirmButton = { TextButton({ pendingImport = null; importText(text, pw) }) { Text("Import") } },
                            dismissButton = { TextButton({ pendingImport = null }) { Text("Cancel") } })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        if (d.scheme == "limoo") importText(d.toString()) else read(d)?.let(::importText)
    }

    private fun read(u: Uri) = runCatching { contentResolver.openInputStream(u)!!.bufferedReader().readText() }.getOrNull()

    private fun importClipboard() {
        val t = getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        if (t.isNullOrBlank()) toast("Clipboard is empty") else importText(t)
    }

    /** Handles .limoo text, limoo:// links, share links (vless/vmess/trojan/ss) and subscription URLs. */
    private fun importText(text: String, password: String? = null) {
        try {
            val p = LimooFile.parseAny(text, password)
            if (p == null && text.trim().startsWith("http")) {
                lifecycleScope.launch { runCatching { store.addSubscription(text.trim()) }.onFailure { toast(it.message ?: "Failed") } }; return
            }
            val servers = p?.servers ?: LinkParser.parseMany(text)
            if (servers.isEmpty()) return toast("No servers found")
            store.addServers(servers)
            p?.subscriptions?.forEach { u -> lifecycleScope.launch { runCatching { store.addSubscription(u) } } }
            val expired = p != null && p.expires > 0 && p.expires < System.currentTimeMillis() / 1000
            toast(if (expired) "Imported ${servers.size} (config has expired)" else "Imported ${servers.size} server(s)")
        } catch (e: LimooFile.NeedsPassword) { pendingImport = text
        } catch (e: Exception) { toast(e.message ?: "Invalid config") }
    }

    private fun share(all: Boolean, password: String?, asLink: Boolean) {
        val list = if (all) store.servers.value else listOfNotNull(store.selected())
        if (list.isEmpty()) return toast("Nothing to share")
        val payload = LimooPayload(name = if (all) "Limoo servers" else list[0].name, servers = list.map { it.copy(pingMs = -1) })
        val text = if (asLink) LimooFile.toDeepLink(payload, password) else LimooFile.encode(payload, password)
        if (asLink) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share link")); return }
        val f = File(cacheDir, "share").apply { mkdirs() }.let { File(it, "${payload.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${LimooFile.EXT}") }
        f.writeText(text)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(LimooFile.MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share .limoo"))
    }

    private fun toggle() {
        val s = LimooVpnService.state.value
        if (s == LimooVpnService.State.Connected || s == LimooVpnService.State.Connecting)
            startService(Intent(this, LimooVpnService::class.java).setAction(LimooVpnService.ACTION_STOP))
        else if (store.settings.value.mode == "vpn") VpnService.prepare(this)?.let { vpnPermission.launch(it) } ?: startVpn()
        else startVpn()
    }

    private fun startVpn() = startForegroundService(Intent(this, LimooVpnService::class.java).setAction(LimooVpnService.ACTION_START))
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
