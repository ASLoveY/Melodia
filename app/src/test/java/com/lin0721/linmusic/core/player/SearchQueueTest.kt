package com.lin0721.linmusic.core.player

import org.junit.Assert.*
import org.junit.Test

class SearchQueueTest {
    private fun item(id: Long) = QueueItem(id, "$id", "artist", "")

    @Test fun appendPreservesCurrentPositionAndSourceOrderInShuffle() {
        val queue = PlaybackQueue()
        queue.setPlayMode(PlayMode.SHUFFLE)
        queue.replaceAll(listOf(item(1), item(2), item(3)), 1)
        val before = queue.items.value
        val current = queue.currentItem()
        val index = queue.currentIndex.value
        queue.append(listOf(item(4)))
        assertEquals(before + item(4), queue.items.value)
        assertEquals(listOf(item(1), item(2), item(3), item(4)), queue.original)
        assertEquals(current, queue.currentItem())
        assertEquals(index, queue.currentIndex.value)
    }

    @Test fun nextAndEndHaveDifferentPositionsAndKeepTheCurrentSong() {
        val queue = PlaybackQueue()
        queue.replaceAll(listOf(item(1), item(2), item(3)), 0)
        queue.append(listOf(item(4)))
        queue.insertNext(listOf(item(5)))
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), queue.items.value.map { it.songId })
        assertEquals(item(1), queue.currentItem())
    }
}
