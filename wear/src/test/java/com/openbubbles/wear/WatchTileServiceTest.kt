package com.openbubbles.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTileServiceTest {
    @Test fun footerCombinesCountsOnOneLine() {
        assertEquals("+2 more unread · 3 queued to send", tileFooter(2, 3))
        assertEquals("+2 more unread", tileFooter(2, 0))
        assertEquals("3 queued to send", tileFooter(0, 3))
        assertNull(tileFooter(0, 0))
        assertNull(tileFooter(-1, -1))
    }
}
