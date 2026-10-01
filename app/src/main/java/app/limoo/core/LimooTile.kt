package app.limoo.core

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.limoo.ui.MainActivity
import kotlinx.coroutines.*

class LimooTile : TileService() {
    private var job: Job? = null
    override fun onStartListening() { job = CoroutineScope(Dispatchers.Main).launch { LimooVpnService.state.collect { render(it) } } }
    override fun onStopListening() { job?.cancel() }

    override fun onClick() {
        val s = LimooVpnService.state.value
        if (s == LimooVpnService.State.Connected || s == LimooVpnService.State.Connecting) {
            startService(Intent(this, LimooVpnService::class.java).setAction(LimooVpnService.ACTION_STOP))
        } else if (VpnService.prepare(this) != null) {          // permission not granted yet: let the app ask
            @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else startForegroundService(Intent(this, LimooVpnService::class.java).setAction(LimooVpnService.ACTION_START))
    }

    private fun render(s: LimooVpnService.State) = qsTile?.apply {
        label = "Limoo"; subtitle = when (s) { LimooVpnService.State.Connected -> "Connected"; LimooVpnService.State.Connecting -> "Connecting"; else -> "Off" }
        state = if (s == LimooVpnService.State.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile()
    }
}
