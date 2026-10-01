package app.limoo.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import app.limoo.LimooApp
import app.limoo.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class LimooVpnService : VpnService() {
    enum class State { Idle, Connecting, Connected, Error }
    companion object {
        const val ACTION_START = "app.limoo.START"; const val ACTION_STOP = "app.limoo.STOP"
        val state = MutableStateFlow(State.Idle); val error = MutableStateFlow<String?>(null)
    }

    private var tun: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: CoreEngine by lazy { LibXrayEngine(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startVpn(); return START_STICKY      // null intent = system restart / Always-on VPN
    }

    private fun notification(text: String): Notification {
        val stop = android.app.PendingIntent.getService(this, 0, Intent(this, LimooVpnService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "limoo").setContentTitle("Limoo").setContentText(text).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
    }

    private fun startVpn() {
        if (job?.isActive == true) return
        val store = (application as LimooApp).store
        state.value = State.Connecting; error.value = null
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("limoo", "Limoo VPN", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notification("Connecting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        job = scope.launch {
            try {
                val st = store.settings.value
                if (st.autoSelect) store.autoSelectBest()
                val server = store.selected() ?: throw IllegalStateException("No server selected")
                if (!GeoManager.ensure(applicationContext, st.geoSource, if (st.geoAutoUpdate) 7 else Long.MAX_VALUE))
                    throw IllegalStateException("Routing data (geoip/geosite) is missing and could not be downloaded. Check the connection and retry.")
                if (st.mode == "vpn") tun = buildTun(st) ?: throw IllegalStateException("VPN permission was revoked")
                engine.start(XrayConfigBuilder.build(server, st, tun != null), tun?.fd ?: -1, st)
                nm.notify(1, notification(server.name))
                state.value = State.Connected
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

    private fun fail(msg: String) { error.value = msg; state.value = State.Error; stopVpn(keepError = true) }

    private fun stopVpn(keepError: Boolean = false) {
        job?.cancel(); job = null
        runCatching { engine.stop() }; runCatching { tun?.close() }; tun = null
        if (!keepError) state.value = State.Idle
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
