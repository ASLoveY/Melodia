package com.lin0721.linmusic.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Media3 cache stack on a device. JVM tests cover the state helpers and the
 * evictor protocol in isolation; these cases verify SimpleCache, FileDataSource and CacheDataSource
 * still agree when files are removed or a write is in flight.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class AudioCacheIntegrationInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clearRemovesCommittedAudioAndCacheCanReadAndWriteAgain() = withCache(maxBytes = 64 * 1024) { cache, _ ->
        val first = bytes(4096, seed = 11)
        val firstSpec = spec("audio://clear/first", "first", first.size)
        readAll(cacheDataSource(cache, ByteArrayDataSource(first)), firstSpec)

        val cachedFile = cache.getCachedSpans("first").single().file
        assertTrue(cachedFile != null && cachedFile.exists())

        clearCachedAudio(cache)

        assertTrue(cache.keys.isEmpty())
        assertFalse(cachedFile!!.exists())

        val second = bytes(2048, seed = 29)
        val secondSpec = spec("audio://clear/second", "second", second.size)
        readAll(cacheDataSource(cache, ByteArrayDataSource(second)), secondSpec)
        assertTrue(cache.isCached("second", 0, second.size.toLong()))
        assertArrayEquals(second, readAll(cacheDataSource(cache, failOnCacheMiss()), secondSpec))
    }

    @Test
    fun shrinkingCapacityEvictsLeastRecentlyUsedSpanButKeepsRecentSpanReadable() =
        withCache(maxBytes = 8192) { cache, evictor ->
            val old = bytes(4096, seed = 41)
            val recent = bytes(4096, seed = 73)
            val oldSpec = spec("audio://lru/old", "old", old.size)
            val recentSpec = spec("audio://lru/recent", "recent", recent.size)

            readAll(cacheDataSource(cache, ByteArrayDataSource(old)), oldSpec)
            Thread.sleep(10)
            readAll(cacheDataSource(cache, ByteArrayDataSource(recent)), recentSpec)
            // Touch the first span after the second one was written, making it the recent entry.
            Thread.sleep(10)
            assertArrayEquals(old, readAll(cacheDataSource(cache, failOnCacheMiss()), oldSpec))

            evictor.updateMaxBytes(cache, old.size.toLong())

            assertEquals(setOf("old"), cache.keys)
            assertArrayEquals(old, readAll(cacheDataSource(cache, failOnCacheMiss()), oldSpec))
            assertFalse(cache.keys.contains("recent"))
        }

    @Test
    fun missingSelectedCacheFileFallsBackOnceWithoutDuplicatingBytes() = withCache(maxBytes = 64 * 1024) { cache, _ ->
        val data = bytes(8192, seed = 97)
        val dataSpec = spec("audio://recovery/missing", "missing", data.size)
        readAll(cacheDataSource(cache, ByteArrayDataSource(data)), dataSpec)

        val cachedSpan = cache.getCachedSpans("missing").single()
        assertTrue(cachedSpan.isCached)
        assertTrue(cachedSpan.file != null && cachedSpan.file!!.exists())

        // CacheDataSource normally receives the span from startReadWriteNonBlocking and opens the
        // file afterwards. Delete it at that boundary to make the race deterministic.
        val raceCache = deleteCachedFileAfterSpanSelection(cache)
        val upstream = CountingDataSource.Factory(data)
        val recovering = CacheReadRecoveryDataSource(
            CacheDataSource(
                raceCache,
                upstream.createDataSource(),
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
            )
        )

        assertArrayEquals(data, readAll(recovering, dataSpec))
        assertEquals(1, upstream.openCount)
        assertEquals(data.size, upstream.bytesRead)
    }

    @Test
    fun clearingWhileCacheWriteIsActiveDoesNotBreakThatWrite() = withCache(maxBytes = 64 * 1024) { cache, _ ->
        val data = bytes(16 * 1024, seed = 131)
        val dataSpec = spec("audio://active/write", "active", data.size)
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val upstream = BlockingDataSource(data, firstReadStarted, releaseFirstRead)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val future = executor.submit<ByteArray> {
                readAll(cacheDataSource(cache, upstream), dataSpec)
            }

            assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS))
            clearCachedAudio(cache)
            releaseFirstRead.countDown()

            assertArrayEquals(data, future.get(10, TimeUnit.SECONDS))
            assertTrue(cache.isCached("active", 0, data.size.toLong()))

            // The completed write remains usable without touching the upstream again.
            val failingUpstream = object : DataSource {
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw AssertionError("cache miss after active clear")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw AssertionError("cache miss after active clear")
                override fun getUri(): Uri? = null
                override fun close() = Unit
            }
            assertArrayEquals(data, readAll(cacheDataSource(cache, failingUpstream), dataSpec))
        } finally {
            releaseFirstRead.countDown()
            executor.shutdownNow()
        }
    }

    private fun cacheDataSource(cache: Cache, upstream: DataSource): DataSource =
        CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun failOnCacheMiss(): DataSource = object : DataSource {
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = throw AssertionError("Unexpected cache miss")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw AssertionError("Unexpected upstream read")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    private fun spec(uri: String, key: String, length: Int): DataSpec =
        DataSpec(Uri.parse(uri), 0L, length.toLong(), key)

    private fun readAll(source: DataSource, dataSpec: DataSpec): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        try {
            source.open(dataSpec)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                check(count > 0) { "unexpected empty read" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) and 0xFF).toByte() }

    private fun <T> withCache(
        maxBytes: Long,
        block: (SimpleCache, ResizableCacheEvictor) -> T
    ): T {
        val directory = File(context.cacheDir, "instrumented-audio-cache-${System.nanoTime()}")
        directory.mkdirs()
        val evictor = ResizableCacheEvictor(maxBytes)
        val cache = SimpleCache(directory, evictor, StandaloneDatabaseProvider(context))
        return try {
            block(cache, evictor)
        } finally {
            cache.release()
            directory.deleteRecursively()
        }
    }

    private fun deleteCachedFileAfterSpanSelection(cache: Cache): Cache =
        Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            val result = method.invoke(cache, *(args ?: emptyArray()))
            if (method.name == "startReadWrite" || method.name == "startReadWriteNonBlocking") {
                val span = result as? CacheSpan
                if (span?.isCached == true) {
                    checkNotNull(span.file).delete()
                }
            }
            result
        } as Cache

    private class CountingDataSource private constructor(
        private val delegate: ByteArrayDataSource,
        private val owner: Factory
    ) : DataSource {
        class Factory(private val data: ByteArray) : DataSource.Factory {
            var openCount: Int = 0
                private set
            var bytesRead: Int = 0
                private set

            override fun createDataSource(): DataSource = CountingDataSource(ByteArrayDataSource(data), this)

            fun onOpen() {
                openCount += 1
            }

            fun onRead(count: Int) {
                if (count > 0) bytesRead += count
            }
        }

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            owner.onOpen()
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            owner.onRead(count)
            return count
        }

        override fun getUri(): Uri? = delegate.uri

        override fun close() = delegate.close()
    }

    private class BlockingDataSource(
        private val data: ByteArray,
        private val firstReadStarted: CountDownLatch,
        private val releaseFirstRead: CountDownLatch
    ) : DataSource {
        private var position = 0
        private var blocked = false

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            position = dataSpec.position.toInt()
            blocked = true
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                (data.size - position).toLong()
            } else {
                dataSpec.length
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (blocked) {
                blocked = false
                firstReadStarted.countDown()
                check(releaseFirstRead.await(10, TimeUnit.SECONDS)) { "timed out waiting for clear" }
            }
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri? = null

        override fun close() = Unit
    }
}
