package com.lin0721.linmusic.core.player

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.TreeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioCachePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `shrinking capacity evicts oldest spans without releasing cache`() {
        val cache = TestCache(30)
        cache.add(span("old", 10, 1))
        cache.add(span("recent", 10, 2))

        cache.policy.updateMaxBytes(cache.api, 10)

        assertEquals(setOf("recent"), cache.api.keys)
        cache.add(span("next", 10, 3))
        assertEquals(setOf("next"), cache.api.keys)
    }

    @Test
    fun `growing capacity retains existing cache and accepts more data`() {
        val cache = TestCache(10)
        cache.add(span("one", 10, 1))
        cache.policy.updateMaxBytes(cache.api, 30)
        cache.add(span("two", 10, 2))

        assertEquals(20L, cache.api.cacheSpace)
        assertEquals(setOf("one", "two"), cache.api.keys)
    }

    @Test
    fun `touching a span changes eviction order`() {
        val cache = TestCache(20)
        val old = span("one", 10, 1)
        cache.add(old)
        cache.add(span("two", 10, 2))
        cache.touch(old, span("one", 10, 3))
        cache.add(span("three", 10, 4))

        assertEquals(setOf("one", "three"), cache.api.keys)
    }

    @Test
    fun `equal timestamps keep separate resources and offsets`() {
        val cache = TestCache(30)
        cache.add(span("one", 10, 1))
        cache.add(span("one", 10, 1, position = 10))
        cache.add(span("two", 10, 1))
        cache.policy.updateMaxBytes(cache.api, 10)

        assertEquals(10L, cache.api.cacheSpace)
        assertEquals(setOf("two"), cache.api.keys)
    }

    @Test
    fun `file reservation trims cache while unknown lengths do not`() {
        val cache = TestCache(20)
        cache.add(span("one", 10, 1))
        cache.add(span("two", 10, 2))
        cache.policy.onStartFile(cache.api, "three", 0, C.LENGTH_UNSET.toLong())
        assertEquals(20L, cache.api.cacheSpace)

        cache.policy.onStartFile(cache.api, "three", 0, 10)
        assertEquals(setOf("two"), cache.api.keys)
    }

    @Test
    fun `oversized spans are evicted and accounting recovers after clearing`() {
        val cache = TestCache(10)
        cache.add(span("too-large", 11, 1))
        assertTrue(cache.api.keys.isEmpty())
        cache.add(span("one", 10, 2))
        clearCachedAudio(cache.api)
        cache.add(span("two", 10, 3))

        assertEquals(10L, cache.api.cacheSpace)
        assertEquals(setOf("two"), cache.api.keys)
    }

    @Test
    fun `clear removes committed files and leaves cache usable`() {
        val cache = TestCache(20)
        val file = temporaryFolder.newFile("audio-span")
        cache.add(span("one", 10, 1, file = file))

        clearCachedAudio(cache.api)

        assertFalse(file.exists())
        assertTrue(cache.api.keys.isEmpty())
        cache.add(span("next", 10, 2))
        assertEquals(setOf("next"), cache.api.keys)
    }

    @Test(expected = IOException::class)
    fun `failed file deletion is reported instead of claiming successful clear`() {
        val cache = TestCache(20, deleteFiles = false)
        cache.add(span("one", 10, 1, file = temporaryFolder.newFile("undeleted-span")))
        clearCachedAudio(cache.api)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid capacity is rejected`() {
        val cache = TestCache(20)
        cache.policy.updateMaxBytes(cache.api, 0)
    }

    private fun span(key: String, length: Long, touched: Long, position: Long = 0, file: File? = null) =
        CacheSpan(key, position, length, touched, file)

    // 只实现驱逐与清理需要的协议；任何 release/索引替换等意外调用都会直接使测试失败。
    private class TestCache(maxBytes: Long, private val deleteFiles: Boolean = true) {
        val policy = ResizableCacheEvictor(maxBytes)
        private val spans = mutableListOf<CacheSpan>()
        val api: Cache = Proxy.newProxyInstance(
            Cache::class.java.classLoader, arrayOf(Cache::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "getKeys" -> spans.map { it.key }.toSet()
                "getCachedSpans" -> TreeSet(spans.filter { it.key == arguments!![0] })
                "getCacheSpace" -> spans.sumOf { it.length }
                "removeSpan" -> { remove(arguments!![0] as CacheSpan); null }
                "removeResource" -> {
                    spans.filter { it.key == arguments!![0] }.toList().forEach(::remove)
                    null
                }
                else -> error("Unexpected cache operation: ${method.name}")
            }
        } as Cache

        fun add(span: CacheSpan) {
            spans.add(span)
            policy.onSpanAdded(api, span)
        }

        fun touch(old: CacheSpan, updated: CacheSpan) {
            spans.remove(old)
            spans.add(updated)
            policy.onSpanTouched(api, old, updated)
        }

        private fun remove(span: CacheSpan) {
            check(spans.remove(span))
            if (deleteFiles) span.file?.delete()
            policy.onSpanRemoved(api, span)
        }
    }
}
