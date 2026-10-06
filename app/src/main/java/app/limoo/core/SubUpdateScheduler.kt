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

    /** Prefs slot holding the (enabled, interval) pair the alarm was last armed with. */
    private const val ARMED = "sub_update_armed"
    /** Epoch ms of the last completed refresh, so a short repeat window can skip the ticks it does not need. */
    private const val LAST = "sub_update_last"

    /**
     * The alarm's repeat window, in ms.
     *
     * Pure and unit-tested: this is the value the old code got wrong by multiplying
     * `AlarmManager.INTERVAL_HOUR` (3_600_000) by 1000, which produced a ~41-day window, so the
     * repeating alarm effectively never repeated and the user's interval was ignored entirely.
     */
    fun intervalMs(minutes: Int): Long =
        minutes.coerceAtLeast(MIN_MINUTES.toInt()).toLong() * 60_000L

    /**
     * Arm (or leave alone) the periodic refresh.
     *
     * Idempotent on purpose: this runs from `Application.onCreate`, so the old unconditional
     * `cancel` + re-arm pushed the first run `interval` into the future on every process start — opening
     * the app often meant the refresh never came due. Now the alarm is only touched when the
     * `(enabled, interval)` pair actually differs from what was last armed.
     */
    fun apply(ctx: Context, st: AppSettings) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val wanted = "${st.subAutoUpdate}:${intervalMs(st.subUpdateIntervalMin)}"
        val existing = pending(ctx, PendingIntent.FLAG_NO_CREATE)
        val prefs = ctx.getSharedPreferences("limoo_core", Context.MODE_PRIVATE)

        // Already armed with this exact configuration and the alarm still exists: nothing to do.
        if (prefs.getString(ARMED, null) == wanted && (existing != null || !st.subAutoUpdate)) return

        if (existing != null) am.cancel(existing)
        if (!st.subAutoUpdate) {
            prefs.edit().putString(ARMED, wanted).apply()
            return
        }
        val pi = pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = SystemClock.elapsedRealtime() + intervalMs(st.subUpdateIntervalMin)
        runCatching { am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intervalMs(st.subUpdateIntervalMin), pi) }
        prefs.edit().putString(ARMED, wanted).apply()
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return)
        ctx.getSharedPreferences("limoo_core", Context.MODE_PRIVATE).edit().remove(ARMED).apply()
    }

    /**
     * The alarm's PendingIntent, or null under [PendingIntent.FLAG_NO_CREATE] when it was never armed
     * (which is how [apply] tells "already scheduled" from "cancelled since we last looked").
     */
    private fun pending(ctx: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, RQ_CODE,
        Intent(ctx, SubUpdateReceiver::class.java).setAction(ACTION).setPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE or flag,
    )

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
        val last = lastRun(ctx)
        val now = System.currentTimeMillis()
        if (!force && last > 0 && now - last < intervalMs(st.subUpdateIntervalMin)) return 0
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

/**
 * Re-arms the periodic refresh after the device reboots or the app is replaced.
 *
 * AlarmManager drops every alarm when the device powers off, and an app update replaces the
 * PendingIntent's target. Without this receiver the auto-update silently stopped after a reboot until the
 * user happened to open the app — which, for a tool whose whole point is a fresh server list, means it
 * effectively never ran. `RECEIVE_BOOT_COMPLETED` is a normal permission, granted at install.
 *
 * `onReceive` is deliberately synchronous: `apply()` only touches AlarmManager and a prefs slot, and the
 * store is already constructed by `Application.onCreate` before any receiver runs.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> Unit
            else -> return
        }
        val app = context.applicationContext as? LimooApp ?: return
        runCatching { SubUpdateScheduler.apply(app, app.store.settings.value) }
            .onFailure { Crash.log("sub auto update re-arm", it) }
    }
}
