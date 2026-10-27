package com.openbubbles.wear.bridge

/** Conservative text/alignment anchor for UI-only history. Null means we cannot prove what's older. */
internal object HistoryAlignment {
    fun olderPrefixSize(scraped: List<Pair<String, Boolean>>, known: List<Pair<String, Boolean>>): Int? {
        if (known.isEmpty()) return scraped.size
        // Only the oldest known prefix proves that preceding text is older.
        // Matching a later cached bubble could relabel already-known messages.
        val anchor = known.take(12)
        val starts = scraped.indices.filter { start ->
            start + anchor.size <= scraped.size && anchor.indices.all { scraped[start + it] == anchor[it] }
        }
        return starts.singleOrNull()
    }
}
