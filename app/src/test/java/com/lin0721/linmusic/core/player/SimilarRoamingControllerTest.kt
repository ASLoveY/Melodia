package com.lin0721.linmusic.core.player

import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SimilarRoamingControllerTest {
    private fun item(id: Long) = QueueItem(id, "song$id", "artist", "")
    private fun repository(response: suspend (Long) -> List<Track>) = Proxy.newProxyInstance(
        PlaybackRepository::class.java.classLoader, arrayOf(PlaybackRepository::class.java)
    ) { _, method, args ->
        check(method.name == "getSimilarSongs")
        flow { emit(Result.success(response(args[0] as Long))) }
    } as PlaybackRepository

    @Test fun existingFollowingTracksAreNotReplacedOnEverySong() = runTest {
        val queue = PlaybackQueue().apply { replaceAll(listOf(item(1), item(2), item(3)), 1); setPlayContext(SimilarRoamingController.CONTEXT_ROAMING) }
        var calls = 0
        val controller = SimilarRoamingController(this, repository { calls++; listOf(Track(id = 9)) }, queue, { true }, {}, {})
        controller.prefetchOnPlay(2, 1); advanceUntilIdle()
        assertEquals(0, calls); assertEquals(listOf(1L, 2L, 3L), queue.items.value.map { it.songId })
    }
    @Test fun responseAfterManualQueueEditCannotClobberThatEdit() = runTest {
        val reply = CompletableDeferred<List<Track>>()
        val queue = PlaybackQueue().apply { replaceAll(listOf(item(1)), 0); setPlayContext(SimilarRoamingController.CONTEXT_ROAMING) }
        val controller = SimilarRoamingController(this, repository { reply.await() }, queue, { true }, {}, {})
        controller.prefetchOnPlay(1, 0); runCurrent()
        queue.insertNext(listOf(item(8)))
        reply.complete(listOf(Track(id = 9))); advanceUntilIdle()
        assertEquals(listOf(1L, 8L), queue.items.value.map { it.songId })
    }
    @Test fun tailRequestsAreSingleFlightAndExcludeSeedsAndDuplicates() = runTest {
        val reply = CompletableDeferred<List<Track>>(); var calls = 0
        val queue = PlaybackQueue().apply { replaceAll(listOf(item(1)), 0); setPlayContext(SimilarRoamingController.CONTEXT_ROAMING) }
        val controller = SimilarRoamingController(this, repository { calls++; reply.await() }, queue, { true }, {}, {})
        controller.prefetchOnPlay(1, 0); runCurrent(); controller.onProgressTick(1, 5000)
        reply.complete(listOf(Track(id = 1), Track(id = 2), Track(id = 2), Track(id = 0))); advanceUntilIdle()
        assertEquals(1, calls); assertEquals(listOf(1L, 2L), queue.items.value.map { it.songId })
    }
    @Test fun emptyResponsesCanRetryAfterCooldownAndSingleLoopIsRespected() = runTest {
        var calls = 0
        val queue = PlaybackQueue().apply { replaceAll(listOf(item(1)), 0); setPlayMode(PlayMode.SINGLE_LOOP) }
        val controller = SimilarRoamingController(this, repository { calls++; if(calls == 1) emptyList() else listOf(Track(id = 2)) }, queue, { true }, {}, {}, { testScheduler.currentTime })
        controller.onProgressTick(1, 5000); runCurrent(); assertEquals(0, calls)
        queue.setPlayMode(PlayMode.LIST_LOOP)
        controller.onProgressTick(1, 5000); runCurrent(); assertEquals(1, calls)
        controller.onProgressTick(1, 4000); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(30001); controller.onProgressTick(1, 3000); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), queue.items.value.map { it.songId })
    }
    @Test fun disablingRoamingCancelsPendingResponseAndRestoresContext() = runTest {
        val reply = CompletableDeferred<List<Track>>()
        val queue = PlaybackQueue().apply { replaceAll(listOf(item(1)), 0); setPlayContext("playlist") }
        val controller = SimilarRoamingController(this, repository { reply.await() }, queue, { true }, {}, {})
        controller.onProgressTick(1, 10000); runCurrent(); controller.disable()
        reply.complete(listOf(Track(id = 2))); advanceUntilIdle()
        assertEquals("playlist", queue.playContext.value); assertEquals(listOf(1L), queue.items.value.map { it.songId })
    }
}
