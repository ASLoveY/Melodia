package com.lin0721.linmusic.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

// 使用 Media3 的 span 驱逐协议，实现可变容量的 LRU；所有访问须持有 Cache 的监视器。
// https://github.com/androidx/media/blob/1.6.0/libraries/datasource/src/main/java/androidx/media3/datasource/cache/CacheEvictor.java
@UnstableApi
internal class ResizableCacheEvictor(initialMaxBytes: Long) : CacheEvictor {
    private var maxBytes = initialMaxBytes.also { require(it > 0) }
    private var cachedBytes = 0L
    private val spans = TreeSet(
        compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position }
    )

    fun updateMaxBytes(cache: Cache, value: Long) {
        require(value > 0)
        maxBytes = value
        trim(cache, 0)
    }

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) trim(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) cachedBytes += span.length
        trim(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) cachedBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun trim(cache: Cache, incomingBytes: Long) {
        // 减法避免 cachedBytes + incomingBytes 溢出；单个片段超限时也会被驱逐。
        while (spans.isNotEmpty() && cachedBytes > maxBytes - incomingBytes) {
            cache.removeSpan(spans.first())
        }
    }
}
