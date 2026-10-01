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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.TextAlign
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.limoo.R
import app.limoo.core.LimooVpnService
import app.limoo.core.LimooVpnService.State
import app.limoo.core.UsageLog
import app.limoo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home-screen widget, in the app's language: OLED black, hairline border, a dotted ring that goes solid
 * when connected, the "L" mark, state, server, and figures.
 *
 * Built with Glance, so there is no hand-written RemoteViews code (see docs/UI-STYLE.md section 6).
 *
 * Two changes from the first version:
 *  - Reports SESSION and 7-DAY USAGE totals with a bar chart, not only the live per-second rate.
 *  - Refreshes every 2s while connected instead of every 10s. 10s made the figures look broken: they
 *    were visibly stale the moment you looked at them.
 */
class LimooWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Body(context, LimooVpnService.state.value) }
    }

    @Composable
    private fun Body(ctx: Context, st: State) {
        val t = LimooVpnService.traffic.value
        val blocked = LimooVpnService.blocked.value
        val on = st == State.Connected
        val text = ColorProvider(Color(0xFFF2F2F2))
        val dim = ColorProvider(Color(0xFFA0A0A0))
        val muted = ColorProvider(Color(0xFF6B6B6B))
        val accent = ColorProvider(Color(0xFFD71920))

        val label = when {
            blocked -> ctx.getString(R.string.notif_blocked_title)
            on -> ctx.getString(R.string.state_on)
            st == State.Connecting -> ctx.getString(R.string.state_connecting)
            st == State.Error -> ctx.getString(R.string.state_error)
            else -> ctx.getString(R.string.state_off)
        }

        // Usage history: session totals plus the last 7 days, for the tall layout.
        val days = UsageLog.days(ctx)
        val today = LocalDate.now()
        val week = UsageLog.lastN(days, today, 7)
        val weekTotals = week.map { it.second.total }
        val weekMax = (weekTotals.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val weekTotal = weekTotals.sum()
        val sessionTotal = (t?.down ?: 0L) + (t?.up ?: 0L)

        Row(
            GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg))
                .padding(12.dp)
                .clickable(actionRunCallback<ToggleAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.size(44.dp).background(
                    ImageProvider(if (on) R.drawable.widget_ring_on else R.drawable.widget_ring_off),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(R.drawable.ic_stat), contentDescription = "Limoo",
                    modifier = GlanceModifier.size(22.dp),
                    colorFilter = ColorFilter.tint(if (on) text else muted),
                )
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.fillMaxHeight()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label.uppercase(),
                        style = TextStyle(
                            color = if (st == State.Error || blocked) accent else muted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        "\u2193 ${LimooVpnService.fmtRate(t?.downRate ?: 0L)}",
                        style = TextStyle(color = text, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                    )
                }
                Text(
                    LimooVpnService.serverName.value.ifBlank { ctx.getString(R.string.app_name) },
                    style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )

                // Session and 7-day figures. Glance has no runtime size, so both layouts always show them;
                // the chart is simply omitted when the host is too short to render it legibly.
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Metric(ctx.getString(R.string.widget_session), LimooVpnService.fmtBytes(sessionTotal), text, dim, GlanceModifier.defaultWeight())
                    Metric(ctx.getString(R.string.widget_week), LimooVpnService.fmtBytes(weekTotal), text, dim, GlanceModifier.defaultWeight())
                }

                if (weekTotals.isNotEmpty()) {
                    Spacer(GlanceModifier.height(10.dp))
                    UsageChart(weekTotals, weekMax, text, muted)
                    Spacer(GlanceModifier.height(4.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        week.forEachIndexed { i, (d, _) ->
                            Text(
                                d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US),
                                style = TextStyle(
                                    color = if (i == week.lastIndex) text else muted,
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A labelled figure: micro label above, value below. */
@Composable
private fun Metric(label: String, value: String, text: ColorProvider, dim: ColorProvider, modifier: GlanceModifier) {
    Column(modifier) {
        Text(label.uppercase(), style = TextStyle(color = dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
        Text(value, style = TextStyle(color = text, fontSize = 12.sp, fontFamily = FontFamily.Monospace), maxLines = 1)
    }
}

/**
 * Seven-day usage chart as bars. Glance cannot draw, so each bar is a Box: a tinted base height scaled to
 * the week's maximum, and a brighter cap on top for the leading day. An empty day keeps a 2dp stub so the
 * week holds its rhythm instead of collapsing.
 */
@Composable
private fun UsageChart(values: List<Long>, max: Long, text: ColorProvider, muted: ColorProvider) {
    Row(GlanceModifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            val frac = if (v <= 0L || max <= 0L) 0f else (v.toFloat() / max).coerceIn(0f, 1f)
            val h = if (frac <= 0f) 2.dp else (2 + (32 * frac)).dp
            val isToday = i == values.lastIndex
            // Bar body: recede for past days, full contrast for today.
            Box(
                GlanceModifier.size(6.dp, h)
                    .background(ImageProvider(R.drawable.widget_bar), ColorFilter.tint(if (isToday) text else muted)),
            ) {}
            Spacer(GlanceModifier.width(6.dp))
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
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ContextCompat.startForegroundService(context, svc.setAction(LimooVpnService.ACTION_START))
        }
    }
}

/**
 * Keeps the widget current: any state, traffic or blocked change pushes at once, and there is a 2s tick
 * so live figures keep moving. The previous 10s interval is what made the widget look stale.
 */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch { LimooVpnService.state.collect { push(app) } }
        scope.launch { LimooVpnService.blocked.collect { push(app) } }
        scope.launch { LimooVpnService.traffic.collect { push(app) } }
        scope.launch {
            while (true) {
                delay(2_000)
                push(app)
            }
        }
    }

    fun push(ctx: Context) {
        scope.launch { runCatching { LimooWidget().updateAll(ctx) } }
    }
}
