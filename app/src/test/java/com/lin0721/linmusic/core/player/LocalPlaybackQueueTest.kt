package com.lin0721.linmusic.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalPlaybackQueueTest {

    private fun local(uri: String, title: String = uri) = QueueItem(
        songId = 0L,
        title = title,
        artist = "本地歌手",
        coverUrl = "",
        localUri = uri
    )

    @Test
    fun `local items use URI identity and remain distinct when songId is zero`() {
        val first = local("content://music/one")
        val second = local("content://music/two")

        assertTrue(first.isLocal)
        assertTrue(second.isLocal)
        assertEquals("local:content://music/one", first.stableKey)
        assertEquals("local:content://music/two", second.stableKey)
        assertFalse(first.stableKey == second.stableKey)
    }

    @Test
    fun `mixed remote and local queue preserves distinct identities through reorder and removal`() {
        val remote = QueueItem(7L, "远端", "歌手", "cover")
        val firstLocal = local("content://music/one")
        val secondLocal = local("content://music/two")
        val queue = PlaybackQueue().apply {
            replaceAll(listOf(remote, firstLocal, secondLocal), startIndex = 1)
        }

        assertEquals(1, queue.currentIndex.value)
        assertEquals(firstLocal.stableKey, queue.currentItem()?.stableKey)

        queue.move(2, 0)
        assertEquals(listOf(secondLocal.stableKey, remote.stableKey, firstLocal.stableKey), queue.items.value.map { it.stableKey })
        assertEquals(2, queue.currentIndex.value)

        queue.removeAt(0)
        assertEquals(listOf(remote.stableKey, firstLocal.stableKey), queue.items.value.map { it.stableKey })
        assertEquals(firstLocal.stableKey, queue.currentItem()?.stableKey)
    }

    @Test
    fun `shuffle and restore locate a local item by stable key`() {
        val remote = QueueItem(7L, "远端", "歌手", "cover")
        val firstLocal = local("content://music/one")
        val secondLocal = local("content://music/two")
        val queue = PlaybackQueue().apply {
            replaceAll(listOf(remote, firstLocal, secondLocal), startIndex = 2)
        }
        val current = queue.currentItem()!!

        queue.applyMode(PlayMode.SHUFFLE, current)
        assertEquals(current.stableKey, queue.currentItem()?.stableKey)

        queue.applyMode(PlayMode.LIST_LOOP, current)
        assertEquals(listOf(remote.stableKey, firstLocal.stableKey, secondLocal.stableKey), queue.items.value.map { it.stableKey })
        assertEquals(current.stableKey, queue.currentItem()?.stableKey)
    }

    @Test
    fun `old queue JSON remains readable and local URI persists`() {
        val json = Json { ignoreUnknownKeys = true }
        val remote = json.decodeFromString<QueueItem>(
            """{"songId":7,"title":"远端","artist":"歌手","coverUrl":"cover"}"""
        )
        val localJson = json.encodeToString(local("content://music/one"))

        assertFalse(remote.isLocal)
        assertTrue(localJson.contains("content://music/one"))
    }
}
