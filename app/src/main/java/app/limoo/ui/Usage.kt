package app.limoo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.limoo.core.UsageLog
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Data usage: today / 7 days / 30 days, the lifetime total for the selected server, and a 7-day chart.
 *
 * The chart is drawn rather than composed, so it stays inside the design language: a hairline baseline, a
 * soft "envelope" showing the week's peak, and one bar per day. Today's bar carries the text colour and a
 * marker dot so the current day is unmistakable; the rest recede. Zero days keep a 1dp stub so the week
 * keeps its rhythm instead of collapsing.
 */
@Composable
fun UsageCard(serverId: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val n = LocalN.current
    val v by UsageLog.version.collectAsState()
    val days = remember(v) { UsageLog.days(ctx) }
    val perServer = remember(v) { UsageLog.servers(ctx) }
    val today = LocalDate.now()
    val week = remember(days) { UsageLog.lastN(days, today, 7) }
    val totals = week.map { it.second.total }
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)

    NCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.standard)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NLabel("Data usage")
                Spacer(Modifier.weight(1f))
                NButton("Reset", { UsageLog.reset(ctx) }, compact = true)
            }
            Spacer(Modifier.height(Space.standard))
            Row(Modifier.fillMaxWidth()) {
                NStat("Today", fmtBytes(UsageLog.window(days, today, 1).total), Modifier.weight(1f))
                NStat("7 days", fmtBytes(UsageLog.window(days, today, 7).total), Modifier.weight(1f))
                NStat("30 days", fmtBytes(UsageLog.window(days, today, 30).total), Modifier.weight(1f))
            }

            Spacer(Modifier.height(Space.card))

            UsageChart(totals, max, Modifier.fillMaxWidth().height(64.dp))

            Spacer(Modifier.height(Space.compact))
            // Day initials under the chart, aligned to the bars they label.
            Row(Modifier.fillMaxWidth()) {
                week.forEachIndexed { i, (d, _) ->
                    val isToday = i == week.lastIndex
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US),
                        style = NType.micro,
                        color = if (isToday) n.text else n.muted,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(Space.standard))
            NDivider()
            Spacer(Modifier.height(Space.small))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NLabel("This server")
                Spacer(Modifier.weight(1f))
                Text(
                    fmtBytes(perServer[serverId]?.total ?: 0L),
                    style = NType.mono, color = n.text, maxLines = 1,
                )
            }
        }
    }
}

/**
 * Seven-day usage chart.
 *
 * Drawn with Compose Canvas so it inherits the app's hairline/bone language rather than looking like a
 * generic bar widget: a dashed peak envelope behind the bars, a solid baseline, rounded bar caps, and the
 * tallest day called out so the week's shape is readable at a glance.
 */
@Composable
private fun UsageChart(values: List<Long>, max: Long, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val count = values.size
        val gap = 6.dp.toPx()
        val barW = (size.width - gap * (count - 1)) / count
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        val baselineY = size.height

        // Peak envelope: a faint stepped line showing the week's maximum, so the bars have context.
        val peak = values.maxOfOrNull { it } ?: 0L
        if (peak > 0) {
            val peakY = size.height * 0.06f
            var px = 0f
            for (v in values) {
                val h = size.height * (v.toFloat() / peak)
                val y = (baselineY - h).coerceAtMost(peakY)
                drawLine(n.line, Offset(px, y), Offset(px + barW, y), strokeWidth = 1.dp.toPx())
                px += barW + gap
            }
        }

        // Bars.
        values.forEachIndexed { i, v ->
            val x = i * (barW + gap)
            val frac = if (v <= 0L || max <= 0L) 0f else (v.toFloat() / max).coerceIn(0f, 1f)
            if (v <= 0L) {
                // A 1dp stub keeps an empty day visible without pretending there was usage.
                drawRoundRect(n.line, Offset(x, baselineY - 1.dp.toPx()), Size(barW, 1.dp.toPx()), radius)
            } else {
                val h = (size.height * frac).coerceAtLeast(3.dp.toPx())
                val c = if (i == count - 1) n.text else n.dim
                drawRoundRect(c, Offset(x, baselineY - h), Size(barW, h), radius)
                // Marker dot above today's bar.
                if (i == count - 1) {
                    drawCircle(n.accent, 2.5.dp.toPx(), Offset(x + barW / 2f, baselineY - h - 6.dp.toPx()))
                }
            }
        }

        // Baseline hairline.
        drawLine(n.lineStrong, Offset(0f, baselineY), Offset(size.width, baselineY), strokeWidth = 1.dp.toPx())
    }
}
