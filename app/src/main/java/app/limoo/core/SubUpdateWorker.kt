package app.limoo.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.limoo.LimooApp
import app.limoo.model.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Periodic subscription refresh.
 *
 * The interval is the user's: `AppSettings.subUpdateIntervalMin`, whose presets are 1/6/12/24 hours but
 * whose value is otherwise honoured literally. Auto-update is on by default.
 *
 * Why WorkManager rather than an `app.onCreate` call plus the existing "older than 6 hours" check: that only
 * ever ran while the app happened to be open, which for a VPN client is rarely. WorkManager survives process
 * death, respects Doze, and re-arms itself after a reboot, so the list stays fresh without the user
 * opening the app.
 */
class SubUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LimooApp ?: return Result.success()
        val store = app.store
        // Always persist before/after: the worker's window may be short and a killed process must not lose
        // the refreshed servers.
        try {
            store.refreshAll()
            store.flush()
            return Result.success()
        } catch (t: Throwable) {
            Crash.log("sub auto update", t)
            return Result.retry()
        }
    }

    companion object {
        const val NAME = "limoo-sub-update"

        /** WorkManager will not run anything more often than every 15 minutes. */
        val MIN_MINUTES = 15L

        /**
         * Registers (or re-registers) the periodic job to match [st]. Safe to call repeatedly: with
         * [ExistingPeriodicWorkPolicy.UPDATE] the interval is changed in place rather than queued behind a
         * cancel, so changing the setting takes effect immediately instead of after the old schedule drains.
         */
        fun sync(ctx: Context, st: AppSettings) {
            val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull() ?: return
            if (!st.subAutoUpdate) {
                runCatching { wm.cancelUniqueWork(NAME) }
                return
            }
            val minutes = st.subUpdateIntervalMin.coerceAtLeast(MIN_MINUTES.toInt()).toLong()
            val request = PeriodicWorkRequestBuilder<SubUpdateWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // Not urgent: this is a nice-to-have refresh and must never fight the user's battery.
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            runCatching { wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request) }
        }
    }
}