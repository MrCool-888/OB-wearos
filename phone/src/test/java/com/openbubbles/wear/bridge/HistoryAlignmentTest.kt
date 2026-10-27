package com.openbubbles.wear.bridge

import org.junit.Assert.*
import org.junit.Test

class HistoryAlignmentTest {
    private fun texts(vararg values: String) = values.map { it to false }

    @Test fun emptyCacheAcceptsReadableHistory() {
        assertEquals(2, HistoryAlignment.olderPrefixSize(texts("one", "two"), emptyList()))
    }
    @Test fun oldestContiguousAnchorSeparatesOlderFromNewer() {
        assertEquals(1, HistoryAlignment.olderPrefixSize(texts("older", "a", "b", "newer"), texts("a", "b")))
    }
    @Test fun noAnchorDoesNotRelabelNewerMessagesAsOlder() {
        assertNull(HistoryAlignment.olderPrefixSize(texts("newer"), texts("cached")))
    }
    @Test fun matchingOnlyALaterCachedMessageIsNotAnOldestAnchor() {
        assertNull(HistoryAlignment.olderPrefixSize(texts("b", "c"), texts("a", "b", "c")))
    }
    @Test fun repeatedTextWithTwoPossibleAnchorsIsRejected() {
        assertNull(HistoryAlignment.olderPrefixSize(texts("ok", "hi", "ok", "hi"), texts("ok", "hi")))
    }
    @Test fun directionDisambiguatesRepeatedText() {
        assertEquals(1, HistoryAlignment.olderPrefixSize(listOf("ok" to true, "ok" to false), texts("ok")))
    }
    @Test fun repeatedOlderTextIsRetainedIfContiguousContextIsUnique() {
        assertEquals(2, HistoryAlignment.olderPrefixSize(texts("ok", "ok", "ok", "unique"), texts("ok", "unique")))
    }
}
