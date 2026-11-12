package io.github.yyan115.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Removes the least recently used pieces of a cache when it grows past [maxBytes]. It works like the evictor of the library, but the limit
 * can be changed while the cache is open: [trim] applies a lower one at once.
 */
@UnstableApi
class AdjustableEvictor(maxBytes: Long) : CacheEvictor {
    @Volatile var maxBytes: Long = maxBytes
    private val leastRecent = TreeSet<CacheSpan> { a, b ->
        val difference = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (difference == 0L) a.compareTo(b) else if (difference < 0) -1 else 1
    }
    private var current = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}

    @Synchronized override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    @Synchronized override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecent.add(span); current += span.length
        evict(cache, 0)
    }

    @Synchronized override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (leastRecent.remove(span)) current -= span.length
    }

    @Synchronized override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
    }

    /** Applies the current limit now, for example after it was lowered. */
    @Synchronized fun trim(cache: Cache) = evict(cache, 0)

    private fun evict(cache: Cache, requiredSpace: Long) {
        while (current + requiredSpace > maxBytes && leastRecent.isNotEmpty()) cache.removeSpan(leastRecent.first())
    }
}
