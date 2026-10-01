package app.limoo.widget

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.limoo.R
import app.limoo.core.LimooVpnService
import app.limoo.core.LimooVpnService.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home-screen widget in the app's language: OLED black, hairline border (widget_bg), a dotted ring that
 * goes solid when connected, the "L" mark, state as a mono micro label, server name, live speed.
 * Built with Glance, so there is no hand-written RemoteViews code (see docs/UI-STYLE.md section 6).
 */
class LimooWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Body(context) }
    }

    @Composable
    private fun Body(ctx: Context) {
        val st = LimooVpnService.state.value
        val t = LimooVpnService.traffic.value
        val blocked = LimooVpnService.blocked.value
        val on = st == State.Connected
        val text = ColorProvider(Color(0xFFF2F2F2))
        val muted = ColorProvider(Color(0xFF6B6B6B))
        val accent = ColorProvider(Color(0xFFD71920))
        val label = when {
            blocked -> ctx.getString(R.string.notif_blocked_title)
            st == State.Connected -> ctx.getString(R.string.state_on)
            st == State.Connecting -> ctx.getString(R.string.state_connecting)
            st == State.Error -> ctx.getString(R.string.state_error)
            else -> ctx.getString(R.string.state_off)
        }
        Row(
            GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(12.dp)
                .clickable(actionRunCallback<ToggleAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.size(44.dp).background(ImageProvider(if (on) R.drawable.widget_ring_on else R.drawable.widget_ring_off)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(R.drawable.ic_stat), contentDescription = "Limoo",
                    modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(if (on) text else muted),
                )
            }
            Spacer(GlanceModifier.width(12.dp))
            Column {
                Text(
                    label.uppercase(),
                    style = TextStyle(color = if (st == State.Error || blocked) accent else muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                )
                Text(
                    LimooVpnService.serverName.value.ifBlank { "Limoo" },
                    style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                )
                if (on && t != null) Text(
                    "↓ ${LimooVpnService.fmtRate(t.downRate)}  ↑ ${LimooVpnService.fmtRate(t.upRate)}",
                    style = TextStyle(color = text, fontSize = 11.sp, fontFamily = FontFamily.Monospace), maxLines = 1,
                )
            }
        }
    }
}

class LimooWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LimooWidget()
}

/** Tap = toggle. If VPN permission was never granted, open the app so the system dialog can be shown. */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val st = LimooVpnService.state.value
        val svc = Intent(context, LimooVpnService::class.java)
        if (st == State.Connected || st == State.Connecting || LimooVpnService.blocked.value) {
            context.startService(svc.setAction(LimooVpnService.ACTION_STOP))
        } else if (VpnService.prepare(context) != null) {
            context.startActivity(Intent(context, app.limoo.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ContextCompat.startForegroundService(context, svc.setAction(LimooVpnService.ACTION_START))
        }
    }
}

/** Keeps the widget current: on every state change, and every 10 s while connected. Started from LimooApp. */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch { LimooVpnService.state.collect { push(app) } }
        scope.launch { LimooVpnService.blocked.collect { push(app) } }
        scope.launch {
            while (true) {
                delay(10_000)
                if (LimooVpnService.state.value == State.Connected) push(app)
            }
        }
    }

    fun push(ctx: Context) {
        scope.launch { runCatching { LimooWidget().updateAll(ctx) } }
    }
}
