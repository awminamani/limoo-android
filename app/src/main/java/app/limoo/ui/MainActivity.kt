package app.limoo.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import app.limoo.LimooApp
import app.limoo.Store
import app.limoo.core.GeoManager
import app.limoo.core.LimooVpnService
import app.limoo.format.ImportPreview
import app.limoo.format.Importer
import app.limoo.format.LimooFile
import app.limoo.format.LimooPayload
import app.limoo.model.Server
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val store get() = (application as LimooApp).store
    private var preview by mutableStateOf<ImportPreview?>(null)
    private var clipOffer by mutableStateOf<ImportPreview?>(null)
    private val dismissedClips = HashSet<Int>()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) startVpn() }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { u -> read(u)?.let { open(it, "FILE") } } }
    private val scanner = registerForActivityResult(ScanContract()) { r -> r.contents?.let { open(it, "QR") } }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val actions by lazy {
        Actions(
            toggle = ::toggle, reconnect = ::reconnect,
            pasteImport = ::pasteImport, pickFile = { filePicker.launch(arrayOf("*/*")) },
            scan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a Limoo / VLESS / VMess QR").setBeepEnabled(false).setOrientationLocked(false)) },
            peekClip = { clipText()?.let { t -> runCatching { Importer.parse(t, "CLIPBOARD") }.getOrNull() } },
            share = ::share, exportBackup = ::exportBackup,
            commitImport = ::commit, unlock = { p, pw -> open(p.raw, p.source, pw) },
            addSub = ::addSub,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // First open downloads geoip/geosite; later opens refresh weekly. Subscriptions refresh when older than 6 hours.
        lifecycleScope.launch {
            val st = store.settings.value
            GeoManager.ensure(applicationContext, st.geoSource, if (st.geoAutoUpdate) 7 else Long.MAX_VALUE)
            store.refreshAll()
        }
        if (savedInstanceState == null && store.settings.value.autoConnect && LimooVpnService.state.value == LimooVpnService.State.Idle && store.servers.value.isNotEmpty()) toggle()
        setContent {
            val st by store.settings.collectAsState()
            NTheme(st) {
                LimooRoot(
                    store, LimooVpnService.state.collectAsState().value, LimooVpnService.error.collectAsState().value,
                    preview, clipOffer, actions,
                    onDismissPreview = { preview = null },
                    onDismissClip = { clipOffer?.let { dismissedClips += it.raw.hashCode() }; clipOffer = null },
                    onOpenPreview = { clipOffer = null; preview = it },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) checkClipboard() }

    // ---------- import ----------
    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        if (d.scheme == "limoo") open(d.toString(), "LINK") else read(d)?.let { open(it, "FILE") }
    }

    private fun read(u: Uri) = runCatching { contentResolver.openInputStream(u)!!.bufferedReader().readText() }.getOrNull()

    private fun clipText(): String? = runCatching {
        getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()

    /** Parses [text] and shows the import preview. */
    private fun open(text: String, source: String, password: String? = null) {
        try {
            val p = Importer.parse(text, source, password)
            if (p == null) Ui.say("NO CONFIG FOUND") else { clipOffer = null; preview = p }
        } catch (e: LimooFile.BadPassword) { Ui.say("WRONG PASSWORD") } catch (e: Exception) { Ui.say("INVALID FILE") }
    }

    private fun pasteImport() { val t = clipText(); if (t.isNullOrBlank()) Ui.say("CLIPBOARD IS EMPTY") else open(t, "CLIPBOARD") }

    /** When Limoo comes to the front with something importable on the clipboard, offer it (never auto-imports). */
    private fun checkClipboard() {
        if (!store.settings.value.clipboardWatch) { clipOffer = null; return }
        val t = clipText()?.takeIf { it.isNotBlank() && it.length < 200_000 } ?: run { clipOffer = null; return }
        if (t.trim().hashCode() in dismissedClips) return
        val p = runCatching { Importer.parse(t, "CLIPBOARD") }.getOrNull()
        val have = store.servers.value.map { Store.key(it) }.toHashSet()
        clipOffer = when {
            p == null -> null
            p.needsPassword -> p
            p.servers.isNotEmpty() && p.servers.any { Store.key(it) !in have } -> p
            else -> null                                   // plain URLs and already-imported configs stay quiet
        }
    }

    private fun commit(p: ImportPreview, chosen: List<Server>, group: String, restore: Boolean, subName: String) {
        preview = null; clipOffer = null; dismissedClips += p.raw.hashCode()
        val added = store.addServers(chosen.map { if (group.isNotEmpty() && it.group.isEmpty()) it.copy(group = group) else it })
        if (restore) p.settings?.let { s -> store.update { s } }
        p.subUrls.forEach { u -> addSub(u, if (p.subUrls.size == 1) subName else "") }
        when {
            added.isNotEmpty() -> { val ids = added.map { it.id }.toSet(); Ui.say("IMPORTED ${added.size}", "UNDO") { store.removeMany(ids) } }
            p.subUrls.isEmpty() && !restore -> Ui.say("ALREADY IMPORTED")
        }
    }

    private fun addSub(url: String, name: String) {
        lifecycleScope.launch {
            runCatching { store.addSubscription(url, name) }.onSuccess { Ui.say("SUBSCRIPTION ADDED - $it SERVERS") }.onFailure { Ui.say("SUBSCRIPTION FAILED: ${it.message}") }
        }
    }

    // ---------- share / backup ----------
    private fun share(servers: List<Server>, name: String, note: String, days: Int, pw: String?, asLink: Boolean) {
        if (servers.isEmpty()) return Ui.say("NOTHING TO SHARE")
        send(LimooPayload(
            name = name.ifBlank { "Limoo servers" }, note = note, expires = if (days > 0) System.currentTimeMillis() / 1000 + days * 86_400L else 0,
            servers = servers.map { it.copy(pingMs = -1, fav = false, lastUsed = 0, subId = "") },
        ), pw, asLink)
    }

    private fun exportBackup() = send(LimooPayload(
        name = "Limoo backup", servers = store.servers.value.map { it.copy(pingMs = -1) },
        subscriptions = store.subs.value.map { it.url }, settings = store.settings.value,
    ), null, false)

    private fun send(payload: LimooPayload, pw: String?, asLink: Boolean) {
        val text = if (asLink) LimooFile.toDeepLink(payload, pw) else LimooFile.encode(payload, pw)
        if (asLink) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share link")); return }
        val f = File(cacheDir, "share").apply { mkdirs() }.let { File(it, "${payload.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${LimooFile.EXT}") }
        f.writeText(text)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(LimooFile.MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share .limoo"))
    }

    // ---------- connection ----------
    private fun toggle() {
        val s = LimooVpnService.state.value
        if (s == LimooVpnService.State.Connected || s == LimooVpnService.State.Connecting) stop()
        else if (store.settings.value.mode == "vpn") VpnService.prepare(this)?.let { vpnPermission.launch(it) } ?: startVpn()
        else startVpn()
    }

    private fun stop() { startService(Intent(this, LimooVpnService::class.java).setAction(LimooVpnService.ACTION_STOP)) }
    private fun reconnect() { stop(); lifecycleScope.launch { delay(700); startVpn() } }
    private fun startVpn() { startForegroundService(Intent(this, LimooVpnService::class.java).setAction(LimooVpnService.ACTION_START)) }
}
