package app.limoo.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.graphics.drawable.Icon
import androidx.core.app.ServiceCompat
import app.limoo.LimooApp
import app.limoo.R
import app.limoo.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class LimooVpnService : VpnService() {
    enum class State { Idle, Connecting, Connected, Error }

    companion object {
        const val ACTION_START = "app.limoo.START"; const val ACTION_STOP = "app.limoo.STOP"
        val state = MutableStateFlow(State.Idle); val error = MutableStateFlow<String?>(null)
        val connectedAt = MutableStateFlow(0L)      // epoch ms, 0 when not connected
        val serverName = MutableStateFlow("")        // name of the server the core is running
        /** Live counters: (down, up) bytes since the session started. Null until the first sample arrives. */
        val traffic = MutableStateFlow<CoreTraffic?>(null)
    }

    private var tun: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var counterJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: CoreEngine by lazy { LibXrayEngine(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startVpn(); return START_STICKY      // null intent = system restart / Always-on VPN
    }

    /**
     * Notification in the app's own language: the server name as the title, live speed and session total
     * as the text, plus a custom dot-grid strip that fills as data flows. Android's own layouts cannot draw
     * the dot-matrix font, so the counters use monospace here - the identity comes from the custom view,
     * the flat black background and the hairline separator rather than from a stock notification.
     */
    /**
     * The foreground notification.
     *
     * Deliberately built from the platform's own templates - NO RemoteViews. Two earlier attempts used a
     * custom layout and both crashed the app on connect with
     * `RemoteServiceException$BadForegroundServiceNotificationException: Bad notification(tag=null, id=1)`:
     * SystemUI inflates the notification in its own process and rejected our layout both times. The identity
     * now comes from the dot-grid small icon, flat black background and the server name as the title, and
     * the live counters ride in the standard content text, which cannot fail to inflate.
     */
    private fun notification(text: String, sub: String? = null, extra: String? = null): Notification {
        val stop = android.app.PendingIntent.getService(
            this, 0, Intent(this, LimooVpnService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val open = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, app.limoo.ui.MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (text.isBlank()) "Limoo" else text
        val body = sub?.takeIf { it.isNotBlank() } ?: "Connected"

        return Notification.Builder(this, "limoo")
            .setSmallIcon(R.drawable.ic_stat)          // dot-grid "L", renders in the status bar
            .setContentTitle(title)                     // current server
            .setContentText(body)                       // live speed
            .setSubText(extra ?: "")                    // session total, on the template's own second line
            .setColor(0xFF000000.toInt())
            .setColorized(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Disconnect", stop).build())
            .build()
    }

    private fun startVpn() {
        if (job?.isActive == true) return
        val store = (application as LimooApp).store
        state.value = State.Connecting; error.value = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("limoo", "Limoo VPN", NotificationManager.IMPORTANCE_LOW))
        // startForeground() must not throw. A BadForegroundServiceNotificationException from SystemUI
        // previously killed the app outright on connect, so every layer here is fallible-by-design:
        // build the notification defensively, and if the platform still refuses it, degrade to the
        // absolute minimum rather than taking the process down.
        val fg = runCatching { notification("Connecting...") }.getOrNull()
        if (fg != null) {
            runCatching {
                ServiceCompat.startForeground(this, 1, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure { minimalForeground() }
        } else minimalForeground()


        job = scope.launch {
            try {
                val st = store.settings.value
                if (st.autoSelect) store.autoSelectBest()
                val server = store.selected() ?: throw IllegalStateException("No server selected")
                if (!GeoManager.ensure(applicationContext, st.geoSource, if (st.geoAutoUpdate) 7 else Long.MAX_VALUE))
                    throw IllegalStateException("Routing data (geoip/geosite) is missing and could not be downloaded. Check the connection and retry.")
                if (st.mode == "vpn") tun = buildTun(st) ?: throw IllegalStateException("VPN permission was revoked")
                engine.start(XrayConfigBuilder.build(server, st, tun != null), tun?.fd ?: -1, st)
                serverName.value = server.name
                connectedAt.value = System.currentTimeMillis(); store.touch(server.id)
                state.value = State.Connected
                runCatching { nm.notify(1, notification(server.name)) }
                startCounterLoop(nm)
            } catch (c: CancellationException) { throw c
            } catch (t: Throwable) { fail(t.message ?: t.javaClass.simpleName) }
        }
    }

    private fun buildTun(st: AppSettings): ParcelFileDescriptor? {
        val b = Builder().setSession("Limoo").setMtu(st.mtu).setMetered(false)
            .addAddress("10.10.14.1", 30).addRoute("0.0.0.0", 0).addDnsServer(st.vpnDns)
        if (st.ipv6) b.addAddress("fd00:10:14::1", 126).addRoute("::", 0)
        when (st.perAppMode) {
            "allow" -> st.perApp.forEach { runCatching { b.addAllowedApplication(it) } }
            "deny" -> st.perApp.forEach { runCatching { b.addDisallowedApplication(it) } }
        }
        if (st.perAppMode != "allow") runCatching { b.addDisallowedApplication(packageName) }   // Xray's own sockets must bypass the tunnel
        return b.establish()
    }

    /**
     * Polls the core's counters once a second. Drives the shared [traffic] flow (Home screen) and rebuilds
     * the notification every 5s with the live rate and session total, so the shade shows real numbers
     * without being re-posted so often that it flickers.
     */
    private fun startCounterLoop(nm: NotificationManager) {
        counterJob?.cancel()
        counterJob = scope.launch {
            var lastDown = 0L; var lastPost = 0L
            while (true) {
                delay(1000)
                // runCatching: this loop runs for the whole session, so any escaping exception would
                // cancel the coroutine and, worse, surface as a crash. Counters are optional.
                val now = runCatching { engine.queryTraffic() }.getOrNull() ?: continue
                traffic.value = now
                val t = System.currentTimeMillis()
                val delta = (now.down - lastDown).coerceAtLeast(0)
                val elapsed = t - lastPost
                val bytesPerSec = if (elapsed > 0) delta * 1000L / elapsed else 0L
                // Repost at most every 3s, but immediately on the first sample so the shade is never empty.
                if (lastPost == 0L || elapsed >= 3000L) {
                    lastPost = t; lastDown = now.down
                    runCatching {
                        nm.notify(
                            1,
                            notification(
                                serverName.value,
                                "${fmtRate(bytesPerSec)} down",
                                "${fmtBytes(now.up + now.down)} total",
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Absolute last-resort foreground notification: only the fields the platform guarantees to accept.
     * Used when the styled notification is rejected, so connecting degrades instead of crashing.
     */
    private fun minimalForeground() {
        val n = runCatching {
            Notification.Builder(this, "limoo")
                .setContentTitle("Limoo")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }.getOrNull() ?: return
        runCatching { ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { runCatching { startForeground(1, n) } }
    }

    private fun fmtRate(bytesPerTick: Long): String {
        val kb = bytesPerTick / 1024
        return if (kb >= 1024) "${kb / 1024}/s" else "${kb}K/s"
    }

    private fun fmtBytes(b: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = b.coerceAtLeast(0).toDouble(); var i = 0
        while (v >= 1024 && i < 4) { v /= 1024; i++ }
        return (if (i == 0) "%.0f" else "%.1f").format(v) + " " + u[i]
    }

    private fun fail(msg: String) { error.value = msg; state.value = State.Error; stopVpn(keepError = true) }

    private fun stopVpn(keepError: Boolean = false) {
        job?.cancel(); job = null; counterJob?.cancel(); counterJob = null
        connectedAt.value = 0; traffic.value = null
        runCatching { engine.stop() }; runCatching { tun?.close() }; tun = null
        if (!keepError) state.value = State.Idle
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
