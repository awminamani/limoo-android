package app.limoo.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class UsageLogTest {
    private val today = LocalDate.of(2026, 10, 1)

    @Test fun mergeAccumulates() {
        val m = UsageLog.merge(UsageLog.merge(emptyMap(), "a", Usage(1, 2)), "a", Usage(10, 20))
        assertEquals(Usage(11, 22), m["a"])
    }

    @Test fun lastNIsOldestFirstAndZeroFilled() {
        val days = mapOf("2026-10-01" to Usage(1, 1), "2026-09-29" to Usage(5, 5))
        val l = UsageLog.lastN(days, today, 3)
        assertEquals(listOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), today), l.map { it.first })
        assertEquals(listOf(10L, 0L, 2L), l.map { it.second.total })
    }

    @Test fun windowSumsOnlyTheWindow() {
        val days = mapOf("2026-10-01" to Usage(1, 0), "2026-09-25" to Usage(0, 4), "2026-08-01" to Usage(100, 100))
        assertEquals(1L, UsageLog.window(days, today, 1).total)
        assertEquals(5L, UsageLog.window(days, today, 7).total)
        assertEquals(5L, UsageLog.window(days, today, 30).total)
    }

    @Test fun dayKeyUsesTheGivenZone() {
        // 2026-09-30T22:00Z is already Oct 1 in Tehran (+03:30).
        val ms = 1790805600000L
        assertEquals("2026-09-30", UsageLog.dayKey(ms, ZoneOffset.UTC))
        assertEquals("2026-10-01", UsageLog.dayKey(ms, ZoneOffset.ofHoursMinutes(3, 30)))
    }
}
