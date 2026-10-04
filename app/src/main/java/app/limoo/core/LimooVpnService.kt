package app.limoo.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.TrafficStats
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import app.limoo.LimooApp
import app.limoo.R
import app.limoo.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class LimooVpnService : VpnService() {
    enum class State { Idle, Connecting, Connected, Error }

    companion object {
        const val ACTION_START = "app.limoo.START"; const val ACTION_STOP = "app.limoo.STOP"
        const val ACTION_RECONNECT = "app.limoo.RECONNECT"

        /** Weak handle to the running service, so static callers can bounce the tunnel. */
        @Volatile private var instance: LimooVpnService? = null

        /**
         * Stop the tunnel and bring it back up on the newly selected server. Safe from any thread and does
         * not need an Activity: it goes through the service's own start/stop intents, and waits for the
         * service to actually reach Idle before restarting so the old core is fully down first.
         */
        fun reconnect() {
            val svc = instance ?: return
            svc.reconnectInPlace()
        }
        private const val NOTIF_ID = 1
        private const val CHANNEL = "limoo"
        val state = MutableStateFlow(State.Idle); val error = MutableStateFlow<String?>(null)
        val connectedAt = MutableStateFlow(0L) // epoch ms, 0 when not connected
        val serverName = MutableStateFlow("") // name of the server the core is running
        /**
         * Live traffic for the session: up/down = session totals in bytes, upRate/downRate = bytes per second.
         * The service is the ONLY reader of the core's counters (they reset on read), so the Home screen,
         * widget and notification always show the same numbers. Null until the first sample.
         */
        val traffic = MutableStateFlow<CoreTraffic?>(null)
        /** True when [traffic] comes from Xray's own counters, false when from Android's per-app estimate. */
        val trafficExact = MutableStateFlow(false)
        /** Kill switch engaged: the tunnel is held open with no core behind it, so all traffic is dropped. */
        val blocked = MutableStateFlow(false)

        /** Locale.US on purpose: Persian/Arabic locales format digits the dot-matrix font cannot draw. */
        fun fmtBytes(b: Long): String {
            val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = b.coerceAtLeast(0).toDouble(); var i = 0
            while (v >= 1024 && i < 4) { v /= 1024; i++ }
            return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.1f %s", v, u[i])
        }
        fun fmtRate(bytesPerSec: Long): String = fmtBytes(bytesPerSec) + "/s"
    }

    private var tun: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var counterJob: Job? = null
    private var reconnectJob: Job? = null
    private var stopJob: Job? = null
    /** Live default network, so the tun can name what sits underneath it. See [trackNetwork]. */
    @Volatile private var currentNetwork: android.net.Network? = null
    @Volatile private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: CoreEngine by lazy { LibXrayEngine(this) }

    // usage bytes not yet written to UsageLog
    private var serverId = ""
    @Volatile private var pendingUp = 0L
    @Volatile private var pendingDown = 0L

    private val nm get() = getSystemService(NotificationManager::class.java)
    private val settings get() = (application as LimooApp).store.settings.value

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            // Reconnect is just stop-then-start; handled by the same path as a normal start.
            ACTION_RECONNECT -> { reconnectInPlace(); return START_STICKY }
        }
        startVpn(); return START_STICKY // null intent = system restart / Always-on VPN
    }

    private fun pending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, LimooVpnService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp() = PendingIntent.getActivity(
        this, 1, Intent(this, app.limoo.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The foreground notification. Platform templates ONLY - no RemoteViews (docs/UI-STYLE.md section 6).
     * Monochrome, the "L" silhouette as small icon, server as title, live speeds as text, session totals
     * on the expanded line, and a system chronometer for uptime so the clock ticks without re-posting.
     */
    private fun notification(title: String, speed: String, total: String? = null, since: Long = 0L): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title.ifBlank { "Limoo" })
            .setContentText(speed)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(0xFF000000.toInt())
            .setColorized(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.notif_disconnect), pending(ACTION_STOP, 0)).build())
        // No setSubText: Android renders subText ABOVE the title, which put the session totals on screen
        // twice (above the server name, then again in the expanded body). Collapsed shows the speed only;
        // the expanded body carries the session totals.
        if (total != null) b.setStyle(Notification.BigTextStyle().bigText("$speed\n$total"))
        if (since > 0L) b.setWhen(since).setShowWhen(true).setUsesChronometer(true) else b.setShowWhen(false)
        return b.build()
    }

    /** Kill-switch notification: traffic is blocked, offer Reconnect and Disconnect. */
    private fun blockedNotification(reason: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_blocked_title))
            .setContentText(getString(R.string.notif_blocked_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.notif_blocked_text) + "\n" + reason))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(0xFF000000.toInt()).setColorized(false)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.notif_retry), pending(ACTION_START, 2)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.notif_disconnect), pending(ACTION_STOP, 0)).build())
            .build()

    /**
     * Re-establish the tunnel on the newly selected server, from inside the service.
     *
     * Restarting from the outside raced teardown: stopVpn() marks the state Idle and calls stopSelf()
     * immediately, so an outside caller waiting for Idle is released instantly and its startForegroundService
     * then lands on a service that is already being destroyed - the restart is silently dropped and the app
     * sits disconnected until the user taps the ring again.
     *
     * Doing it in place removes the window: teardown completes, the core and tun fd are released, and only
     * then does the new session start on the same instance. [stopVpn] takes `stayAlive` so the service is not
     * stopped in between.
     */
    private fun reconnectInPlace() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            stopVpn(stayAlive = true)
            // Wait for the core to actually release, so the new session starts against a clean slate.
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && stopJob?.isActive == true) delay(80)
            delay(300)
            startVpn()
        }
    }

    private fun startVpn() {
        if (job?.isActive == true) return
        val store = (application as LimooApp).store
        state.value = State.Connecting; error.value = null
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); enableVibration(false); setSound(null, null)
            },
        )
        // startForeground() must not throw - it is on the connect path. minimalForeground() is the fallback.
        val fg = runCatching { notification("Limoo", getString(R.string.notif_connecting)) }.getOrNull()
        if (fg != null) {
            runCatching {
                ServiceCompat.startForeground(this, NOTIF_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure { minimalForeground() }
        } else minimalForeground()

        job = scope.launch {
            // A tunnel held by the kill switch stays up until the new one replaces it, so nothing leaks
            // during the reconnect.
            val held = tun
            try {
                val st = store.settings.value
                // Auto-select spins up a core probe per server. On battery saver that is far too expensive to
                // do silently on every connect, so it is skipped unless the user asked for it explicitly.
                if (st.autoSelect && !st.batterySaver) store.autoSelectBest()
                val server = store.selected() ?: throw IllegalStateException("No server selected")
                // Validate BEFORE touching geo data, the tun, or the core. Xray rejects an unbuildable
                // config with a sentence about JSON paths, and until now that was the only signal a user
                // ever got. The rules behind these messages were found by running `xray run -test` over
                // the generated matrix - they are enforced while the core BUILDS the config, so nothing
                // in the JSON reveals them.
                val bad = ConfigValidator.validate(server)
                if (bad.isNotEmpty()) {
                    // Plain, user-facing sentences. The raw core message is not available here because
                    // the core was never asked, which is the point.
                    throw IllegalStateException(bad.joinToString("\n"))
                }
                if (!GeoManager.ensure(applicationContext, st.geoSource, if (st.geoAutoUpdate) 7 else Long.MAX_VALUE))
                    throw IllegalStateException("Routing data (geoip/geosite) is missing and could not be downloaded. Check the connection and retry.")
                if (st.mode == "vpn") { trackNetwork(true); tun = buildTun(st) ?: throw IllegalStateException("VPN permission was revoked") }
                else { unregisterNetwork(); tun = null }
                if (held != null && held !== tun) runCatching { held.close() }
                blocked.value = false
                engine.start(XrayConfigBuilder.build(server, st, tun != null), tun?.fd ?: -1, st)
                serverName.value = server.name; serverId = server.id
                connectedAt.value = System.currentTimeMillis(); store.touch(server.id)
                state.value = State.Connected
                runCatching { nm.notify(NOTIF_ID, notification(server.name, fmtSpeed(0, 0), fmtTotal(0, 0), connectedAt.value)) }
                startCounterLoop(st)
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
        if (st.perAppMode != "allow") runCatching { b.addDisallowedApplication(packageName) } // Xray's own sockets must bypass the tunnel
        // Tell Android which physical network sits underneath, so the system does not have to discover it
        // itself and can avoid routing the tunnel's own bound sockets back into it. Must be a real Network,
        // so it is only set while we are actually tracking a live default network.
        currentNetwork?.let { runCatching { b.setUnderlyingNetworks(arrayOf(it)) } }
        return b.establish()
    }

    /**
     * Tracks the device's current default network so the tun builder can name it.
     *
     * This is not cosmetic: without an underlying network the system keeps re-resolving the physical route
     * on every network change, and a handover (Wi-Fi to cellular) can leave the tunnel bound to a dead
     * interface for seconds. Registering the callback lets the tunnel follow the handover instead.
     */
    private fun trackNetwork(enabled: Boolean) {
        if (!enabled) { unregisterNetwork(); return }
        if (netCallback != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                currentNetwork = network
                // A handover invalidates the tun: rebuild it so the new network is the one underneath.
                if (LimooVpnService.state.value == State.Connected) runCatching { reconnectInPlace() }
            }
            override fun onLost(network: android.net.Network) {
                if (currentNetwork == network) currentNetwork = null
            }
        }
        netCallback = cb
        val req = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.registerNetworkCallback(req, cb) }
            .onFailure { netCallback = null }
        runCatching { currentNetwork = cm.activeNetwork }
    }

    private fun unregisterNetwork() {
        val cb = netCallback ?: return; netCallback = null
        runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        currentNetwork = null
    }

    private fun fmtSpeed(downRate: Long, upRate: Long) = "↓ ${fmtRate(downRate)}   ↑ ${fmtRate(upRate)}"
    private fun fmtTotal(down: Long, up: Long) = getString(R.string.notif_session) + " ↓ ${fmtBytes(down)}  ↑ ${fmtBytes(up)}"

    private fun flushUsage() {
        val u = pendingUp; val d = pendingDown; pendingUp = 0; pendingDown = 0
        if (u > 0 || d > 0) runCatching { UsageLog.add(applicationContext, serverId, u, d) }
    }

    /**
     * One-second sampler and the single source of truth for live traffic, plus a core watchdog.
     *
     * Source: Xray's own counters when the core reports them, otherwise Android's per-UID counters (Xray runs
     * in this process, so its sockets are ours). If the core keeps reporting zero while Android sees real
     * bytes (an AAR without outbound stats), it switches to the Android source rather than showing 0 forever.
     * Rates are measured against the real elapsed time, so a late tick does not show a fake spike.
     *
     * The tick rate is the user's (AppSettings.statIntervalMs), doubled by battery saver, and the watchdog
     * interval is derived from it so a slow tick does not mean a slow death check.
     */
    private fun startCounterLoop(st: AppSettings) {
        counterJob?.cancel()
        val tickMs = (if (st.batterySaver) st.statIntervalMs.coerceAtLeast(500) * 2 else st.statIntervalMs).coerceIn(500, 5000).toLong()
        val watchdogEvery = maxOf(1L, 5000L / tickMs)
        counterJob = scope.launch {
            val uid = Process.myUid()
            fun rx() = TrafficStats.getUidRxBytes(uid).let { if (it < 0) 0L else it }
            fun tx() = TrafficStats.getUidTxBytes(uid).let { if (it < 0) 0L else it }
            runCatching { engine.queryTraffic() } // drain anything counted before the session started
            var lastRx = rx(); var lastTx = tx()
            var totalUp = 0L; var totalDown = 0L
            var sysUp = 0L; var sysDown = 0L
            var useCore = true
            var lastTick = SystemClock.elapsedRealtime()
            var lastPost = 0L; var lastFlush = lastTick; var ticks = 0L
            var downEma = 0.0; var upEma = 0.0
            while (isActive) {
                delay(tickMs); ticks++
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastTick).coerceAtLeast(1L); lastTick = now

                // Watchdog: the Go core can die without telling us. Check it on a wall-clock cadence,
                // derived from the tick so a slower poll does not also slow the death check.
                if (ticks % watchdogEvery == 0L && !runCatching { engine.isAlive() }.getOrDefault(true)) {
                    fail(getString(R.string.core_died)); return@launch
                }

                val r = rx(); val x = tx()
                val dRx = (r - lastRx).coerceAtLeast(0); val dTx = (x - lastTx).coerceAtLeast(0)
                lastRx = r; lastTx = x
                sysDown += dRx; sysUp += dTx

                val core = if (useCore) runCatching { engine.queryTraffic() }.getOrNull() else null
                var stepDown = dRx; var stepUp = dTx
                if (core != null) {
                    stepDown = core.down; stepUp = core.up
                    totalDown += stepDown; totalUp += stepUp
                    // Core stuck at zero while Android has seen > 256 KB: outbound stats are not available.
                    if (totalDown + totalUp == 0L && sysDown + sysUp > 256 * 1024) {
                        useCore = false; totalDown = sysDown; totalUp = sysUp; stepDown = dRx; stepUp = dTx
                        Crash.log("core traffic stays 0, using Android counters", null)
                    }
                } else if (useCore) {
                    useCore = false; totalDown = sysDown; totalUp = sysUp
                    Crash.log("traffic stats unavailable, using Android counters", null)
                } else {
                    totalDown += dRx; totalUp += dTx
                }
                pendingDown += stepDown; pendingUp += stepUp

                // Light smoothing so the readout is steady but still reacts within ~2 s.
                val instDown = stepDown * 1000.0 / elapsed; val instUp = stepUp * 1000.0 / elapsed
                downEma = if (downEma == 0.0) instDown else downEma * 0.4 + instDown * 0.6
                upEma = if (upEma == 0.0) instUp else upEma * 0.4 + instUp * 0.6
                val downRate = if (instDown == 0.0) 0L else downEma.toLong()
                val upRate = if (instUp == 0.0) 0L else upEma.toLong()

                traffic.value = CoreTraffic(up = totalUp, down = totalDown, upRate = upRate, downRate = downRate)
                trafficExact.value = useCore && core != null

                if (now - lastFlush >= 30_000L) { lastFlush = now; flushUsage() }

                // Notification: re-posting is the single most expensive thing in this loop, so the interval
                // follows the same battery-saver decision as the sampler.
                val postEvery = if (st.batterySaver) 5_000L else 2_000L
                if (now - lastPost >= postEvery) {
                    lastPost = now
                    runCatching {
                        nm.notify(NOTIF_ID, notification(serverName.value, fmtSpeed(downRate, upRate), fmtTotal(totalDown, totalUp), connectedAt.value))
                    }
                }
            }
        }
    }

    /** Absolute last-resort foreground notification: only fields the platform guarantees to accept. */
    private fun minimalForeground() {
        val n = runCatching {
            Notification.Builder(this, CHANNEL)
                .setContentTitle("Limoo")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }.getOrNull() ?: return
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { runCatching { startForeground(NOTIF_ID, n) } }
    }

    private fun fail(msg: String) {
        error.value = msg; state.value = State.Error
        // Persist the reason too. `error` only lives in memory, so the explanation was gone once the
        // process died - and a connect failure with no trace is the hardest kind to report. The raw
        // message is stored verbatim; nothing is added that could contain the config or credentials.
        Crash.log("connect failed: $msg", null)
        if (settings.killSwitch && tun != null) holdBlocked(msg) else stopVpn(keepError = true)
    }

    /**
     * Kill switch. The core is stopped but the tun interface is kept: Android still routes every app into it
     * and nothing reads it, so all traffic is dropped instead of leaking onto the open network.
     */
    private fun holdBlocked(msg: String) {
        counterJob?.cancel(); counterJob = null; job?.cancel(); job = null; reconnectJob?.cancel()
        flushUsage()
        runCatching { engine.stop() }
        connectedAt.value = 0; traffic.value = null; trafficExact.value = false
        blocked.value = true
        runCatching { nm.notify(NOTIF_ID, blockedNotification(msg)) }
    }

    /**
     * Disconnect returns immediately: state, notification and the Home ring update first, and the slow part
     * (stopping the Go core, closing the tun fd) runs on a background thread.
     */
    private fun stopVpn(keepError: Boolean = false, stayAlive: Boolean = false) {
        job?.cancel(); job = null; counterJob?.cancel(); counterJob = null
        flushUsage()
        if (!keepError) state.value = State.Idle
        connectedAt.value = 0; traffic.value = null; trafficExact.value = false; blocked.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterNetwork()
        val t = tun; tun = null
        // Record the teardown so a reconnect can wait for the core to actually let go before starting again.
        stopJob = scope.launch { runCatching { engine.stop() }; runCatching { t?.close() } }
        if (!stayAlive) { stopJob = null; stopSelf() }
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        // If the system destroys us without ACTION_STOP, do not leave the core or the tun fd running.
        counterJob?.cancel(); job?.cancel()
        flushUsage()
        unregisterNetwork()
        runCatching { engine.stop() }; runCatching { tun?.close() }; tun = null
        if (state.value != State.Error) state.value = State.Idle
        connectedAt.value = 0; traffic.value = null; trafficExact.value = false; blocked.value = false
        instance = null
        scope.cancel(); super.onDestroy()
    }
}