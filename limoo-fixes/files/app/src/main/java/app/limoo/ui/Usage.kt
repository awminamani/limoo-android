package app.limoo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.limoo.core.UsageLog
import java.time.LocalDate

/**
 * Data usage: today / 7 days / 30 days, a 7-day segment chart (instrument, not decoration: one bar per day,
 * today inverted to full text colour) and the lifetime total for the selected server.
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
    val max = (week.maxOfOrNull { it.second.total } ?: 0L).coerceAtLeast(1L)

    NCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.standard)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NLabel("Data usage")
                Spacer(Modifier.weight(1f))
                NButton("Reset", { UsageLog.reset(ctx) }, compact = true)
            }
            Spacer(Modifier.height(Space.compact))
            Row(Modifier.fillMaxWidth()) {
                NStat("Today", fmtBytes(UsageLog.window(days, today, 1).total), Modifier.weight(1f))
                NStat("7 days", fmtBytes(UsageLog.window(days, today, 7).total), Modifier.weight(1f))
                NStat("30 days", fmtBytes(UsageLog.window(days, today, 30).total), Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.standard))
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val gap = 4.dp.toPx()
                val w = (size.width - gap * 6) / 7
                val hair = 1.dp.toPx()
                week.forEachIndexed { i, (_, u) ->
                    val h = if (u.total == 0L) hair else (size.height * u.total / max).coerceAtLeast(hair * 2)
                    val c = when { i == 6 -> n.text; u.total == 0L -> n.line; else -> n.dim }
                    drawRect(c, Offset(i * (w + gap), size.height - h), Size(w, h))
                }
            }
            Spacer(Modifier.height(Space.small))
            NLabel("This server " + fmtBytes(perServer[serverId]?.total ?: 0L), color = n.muted)
        }
    }
}
