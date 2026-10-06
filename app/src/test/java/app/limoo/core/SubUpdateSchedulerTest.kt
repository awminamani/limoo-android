package app.limoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription auto-update interval arithmetic.
 *
 * This is the regression test for a real bug: the repeat window was
 * `maxOf(AlarmManager.INTERVAL_HOUR * 1000L, interval)`, and `INTERVAL_HOUR` is already 3_600_000 ms,
 * so the multiplication produced 3_600_000_000 ms — about **41 days**. The user's configured interval
 * was ignored and the repeating alarm effectively never repeated.
 */
class SubUpdateSchedulerTest {

    /** The configured interval wins, in ms, with no hidden multiplier. */
    @Test fun intervalIsTheConfiguredMinutesInMillis() {
        assertEquals(15 * 60_000L, SubUpdateScheduler.intervalMs(15))
        assertEquals(60 * 60_000L, SubUpdateScheduler.intervalMs(60))
        assertEquals(360 * 60_000L, SubUpdateScheduler.intervalMs(360))
        assertEquals(1440 * 60_000L, SubUpdateScheduler.intervalMs(1440))
    }

    /** Anything below Android's 15-minute floor is clamped up to it, never down to zero. */
    @Test fun intervalClampsToThePlatformFloor() {
        assertEquals(SubUpdateScheduler.intervalMs(15), SubUpdateScheduler.intervalMs(0))
        assertEquals(SubUpdateScheduler.intervalMs(15), SubUpdateScheduler.intervalMs(1))
        assertEquals(SubUpdateScheduler.intervalMs(15), SubUpdateScheduler.intervalMs(14))
        assertEquals(SubUpdateScheduler.intervalMs(15), SubUpdateScheduler.intervalMs(-99))
    }

    /**
     * The exact regression: no value the UI can produce may land anywhere near the old ~41-day window.
     * A day is the largest preset, so anything above that means the multiplier is back.
     */
    @Test fun noIntervalEverProducesTheOldFortyOneDayWindow() {
        val oldBug = 3_600_000L * 1000L
        for (minutes in listOf(-1, 0, 1, 15, 60, 360, 720, 1440, 10_000)) {
            val ms = SubUpdateScheduler.intervalMs(minutes)
            assertTrue("intervalMs($minutes) = $ms must be well under the 41-day bug", ms < oldBug)
            assertTrue("intervalMs($minutes) = $ms must be at least 15 min", ms >= 15 * 60_000L)
        }
    }

    /** The floor constant and the clamp must agree; a drift here silently changes the UI contract. */
    @Test fun floorConstantMatchesTheClamp() {
        assertEquals(SubUpdateScheduler.MIN_MINUTES * 60_000L, SubUpdateScheduler.intervalMs(0))
    }
}
