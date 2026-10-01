package app.limoo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreStatsTest {
    @Test fun parsesCoreTextFormat() {
        val t = CoreStats.parse("proxy,uplink,100;proxy,downlink,2000;direct,downlink,50;direct,uplink,5;")
        assertEquals(105L, t.up); assertEquals(2050L, t.down)
    }

    @Test fun ignoresFragmentAndBlock() {
        // fragment is a dialerProxy under proxy: counting it would double every byte.
        val t = CoreStats.parse("proxy,downlink,1000;fragment,downlink,1000;block,uplink,9;")
        assertEquals(0L, t.up); assertEquals(1000L, t.down)
    }

    @Test fun emptyMeansNoTrafficNotUnavailable() {
        val t = CoreStats.parse("")
        assertEquals(0L, t.up); assertEquals(0L, t.down)
    }

    @Test fun toleratesGarbageRecords() {
        val t = CoreStats.parse("proxy,uplink,abc;;proxy,downlink,7;oops;")
        assertEquals(0L, t.up); assertEquals(7L, t.down)
    }

    @Test fun acceptsJsonShapeAsFallback() {
        val t = CoreStats.parse("{\"uplink\": 3, \"downlink\": 4}")
        assertEquals(3L, t.up); assertEquals(4L, t.down)
    }
}
