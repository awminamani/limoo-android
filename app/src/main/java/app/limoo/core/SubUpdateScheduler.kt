package app.limoo.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.limoo.LimooApp
import app.limoo.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Periodic subscription refresh, scheduled with [AlarmManager].
 *
 * **Why not WorkManager.** WorkManager would be the usual answer, but it drags in Room and SQLite — several
 * megabytes and a pile of transitive artifacts — for the single job of "wake up every N hours". When the
 * dependency was first added, the CI runner hit Maven Central rate limits (HTTP 429) resolving exactly that
 * new subtree and the build failed before compiling a line of Kotlin. `setInexactRepeating` needs no
 * permission on any API level, survives Doze as a deferred window, and re-arms itself indefinitely, so the
 * platform service this actually wants is already in the framework.
 *
 * `setInexactRepeating` rather than `setExactAndAllowWhileIdle` on purpose: an exact repeating alarm would
 * need `SCHEDULE_EXACT_ALARM` on API 31+, and waking the CPU at an exact instant to refresh a server list
 * is exactly the battery cost the user is trying to avoid.
 */
object SubUpdateScheduler {
    /** Broadcast action, namespaced so it cannot collide with anything else. */
    const val ACTION = "app.limoo.SUB_UPDATE"
    const val RQ_CODE = 4200

    /** Android will not honour a repeating interval shorter than this. */
    const val MIN_MINUTES = 15L

    /** No surface for the exact interval, so the UI shows the preset instead. */
    private const val SCHEDULE_INSECTS = AlarmManager.INTERVAL_HOUR

    fun apply(ctx: Context, st: AppSettings) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        am.cancel(pi)
        if (!st.subAutoUpdate) return
        // INTERVAL_HOUR as the tick; `st.subUpdateIntervalMin` decides how many ticks are skipped by
        // rescheduling with the stored elapsed-realtime anchor rather than relying on a sub-hour repeat.
        val at = SystemClock.elapsedRealtime() + (st.subUpdateIntervalMin.coerceAtLeast(MIN_MINUTES.toInt()) * 60_000L)
        runCatching { am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intervalMs(st), pi) }
    }

    /**
     * The repeat window. `setInexactRepeating` ignores anything under 60 s anyway; for the 15-minute floor we
     * ask for hourly ticks and simply skip the ones that are not yet due, checked in [refreshIfDue].
     */
    private fun intervalMs(st: AppSettings): Long = maxOf(SCHEDULE_INSECTS * 1000L, st.subUpdateIntervalMin.coerceAtLeast(MIN_MINUTES.toInt()) * 60_000L)

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RQ_CODE,
        Intent(ctx, SubUpdateReceiver::class.java).setAction(ACTION).setPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Epoch ms of the last completed refresh, so a short repeat window can skip the ticks it does not need. */
    private const val LAST = "sub_update_last"
    private fun lastRun(ctx: Context): Long = ctx.getSharedPreferences("limoo_core", Context.MODE_PRIVATE).getLong(LAST, 0L)
    private fun markRun(ctx: Context) {
        ctx.getSharedPreferences("limoo_core", Context.MODE_PRIVATE).edit().putLong(LAST, System.currentTimeMillis()).apply()
    }

    /**
     * Runs the refresh if [st]'s interval has actually elapsed.
     *
     * The receiver calls this; it is public so the manual "Update now" path can reuse the same guard.
     * Returns the number of subscriptions refreshed.
     */
    suspend fun refreshIfDue(ctx: Context, st: AppSettings, force: Boolean = false): Int {
        val intervalMs = st.subUpdateIntervalMin.coerceAtLeast(MIN_MINUTES.toInt()) * 60_000L
        val last = lastRun(ctx)
        val now = System.currentTimeMillis()
        if (!force && last > 0 && now - last < intervalMs) return 0
        val app = ctx.applicationContext as? LimooApp ?: return 0
        // refreshAll() applies the per-subscription flag and reports failures into the subscription record,
        // so a dead link is visible in the subscriptions sheet rather than silently retried forever.
        app.store.refreshAll(force = true)
        app.store.flush()
        markRun(ctx)
        return app.store.subs.value.count { it.autoUpdate }
    }
}

/**
 * Receives the alarm and does the work off the main thread.
 *
 * `goAsync()` keeps the process alive past `onReceive` for the duration of the coroutine. Without it Android
 * may kill the app the moment this returns, and a list that refreshes "sometimes" is worse than one that does
 * not claim to refresh at all.
 */
class SubUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SubUpdateScheduler.ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val st = (app as? LimooApp)?.store?.settings?.value ?: return@launch
                SubUpdateScheduler.refreshIfDue(app, st)
            } catch (t: Throwable) {
                Crash.log("sub auto update", t)
            } finally {
                // Always released, including on the early return, or the receiver leaks a wake lock.
                runCatching { pending.finish() }
            }
        }
    }
}