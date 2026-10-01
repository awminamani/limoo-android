package app.limoo.widget

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.unit.ColorProvider
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorFilter
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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

/**
 * Home-screen widget.
 *
 * Two things changed after the first version:
 *  - It reports SESSION and USAGE totals with a 7-day bar chart instead of only the live per-second rate.
 *  - It refreshes every 2s while connected rather than every 10s, and pushes immediately on any state
 *    change. 10s felt broken: the numbers were visibly stale the moment you looked at them.
 *
 * A compact layout is used for the 4x1/small size and a taller one for 4x2, chosen from the widget's own
 * size so both variants fill their space properly.
 */
class LimooWidget : GlanceAppWidget() {

    @Composable
    override fun provideGlance(context: Context, id: GlanceId) {
        // Glance has no size in the composition, so the widget height is read from the host configuration.
        // >=110dp means the user has made it at least 2 rows tall, which is when the chart is worth showing.
        val h = LocalConfiguration.current.screenHeightDp
        val w = LocalConfiguration.current.screenWidthDp
        val tall = h >= 400 || w >= 400
        provideContent { GlanceTheme { Body(context, tall) } }
    }

    @Composable
    private fun Body(ctx: Context, tall: Boolean) {
        val st = LimooVpnService.state.value
        val t = LimooVpnService.traffic.value
        val blocked = LimooVpnService.blocked.value
        val on = st == State.Connected
        val text = ColorProvider(Color(0xFFF2F2F2))
        val dim = ColorProvider(Color(0xFFA0A0A0))
        val muted = ColorProvider(Color(0xFF6B6B6B))
        val accent = ColorProvider(Color(0xFFD71920))

        val label = when {
            blocked -> ctx.getString(R.string.notif_blocked_title)
            st == State.Connected -> ctx.getString(R.string.state_on)
            st == State.Connecting -> ctx.getString(R.string.state_connecting)
            st == State.Error -> ctx.getString(R.string.state_error)
            else -> ctx.getString(R.string.state_off)
        }

        // Usage history: totals for the session and for the last 7 days, plus the bars.
        // Keyed on the log version so the chart re-reads after every write, and on the day so it rolls
        // over at midnight instead of showing yesterday as "today" forever.
        val logVersion = UsageLog.version.value
        val today = remember(logVersion) { LocalDate.now() }
        val week = remember(logVersion, today) { UsageLog.lastN(UsageLog.days(ctx), today, 7) }
        val max = (week.maxOfOrNull { it.second.total } ?: 0L).coerceAtLeast(1L)
        val weekTotal = week.sumOf { it.second.total }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label.uppercase(),
                        style = TextStyle(
                            color = if (st == State.Error || blocked) accent else muted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    if (on) Text(
                        "↓ ${LimooVpnService.fmtRate(t?.downRate ?: 0)}",
                        style = TextStyle(color = text, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                    )
                }
                Text(
                    LimooVpnService.serverName.value.ifBlank { ctx.getString(R.string.app_name) },
                    style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )

                if (tall) {
                    // Session totals, then the 7-day chart.
                    Spacer(GlanceModifier.height(8.dp))
                    Row {
                        Metric(ctx.getString(R.string.widget_session), LimooVpnService.fmtBytes(t?.totalDown ?: 0), text, dim, GlanceModifier.defaultWeight())
                        Metric(ctx.getString(R.string.widget_week), LimooVpnService.fmtBytes(weekTotal), text, dim, GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.height(10.dp))
                    UsageChart(week.map { it.second.total }, max, text, muted)
                    Spacer(GlanceModifier.height(4.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        Text(
                            week.first().first.month.name.take(3) + " – " + week.last().first.month.name.take(3),
                            style = TextStyle(color = muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            ctx.getString(R.string.widget_today) + " " + LimooVpnService.fmtBytes(
                                UsageLog.window(UsageLog.days(ctx), today, 1).total,
                            ),
                            style = TextStyle(color = dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                        )
                    }
                } else if (on && t != null) {
                    // Compact size: one session line.
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        "↑ ${LimooVpnService.fmtRate(t.upRate)}   ${LimooVpnService.fmtBytes(t.totalDown + t.totalUp)}",
                        style = TextStyle(color = dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                    )
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
 * 7-day usage chart drawn as bars. Today's bar is full-contrast and the rest recede, so the current day
 * reads first; a day with no usage shows a hairline rather than nothing, so the week keeps its rhythm.
 */
@Composable
private fun UsageChart(values: List<Long>, max: Long, text: ColorProvider, muted: ColorProvider) {
    Row(GlanceModifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            val frac = if (v <= 0L) 0f else (v.toFloat() / max).coerceIn(0f, 1f)
            // Today (last slot) gets the full-contrast colour.
            val c = if (i == values.lastIndex) text else muted
            Box(
                GlanceModifier.defaultWeight().height(
                    if (frac <= 0f) 2.dp else (2 + (32 * frac)).dp,
                ).background(ImageProvider(R.drawable.widget_bar)),
            ) {
                Box(GlanceModifier.fillMaxSize().background(c))
            }
            if (i != values.lastIndex) Spacer(GlanceModifier.width(4.dp))
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
 * Keeps the widget current. Every state change pushes immediately, and while connected it re-pushes every
 * 2s - the previous 10s interval made the figures look broken.
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
