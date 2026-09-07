package com.lin0721.linmusic.feature.library.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRemovalCoordinatorTest {

    @Test
    fun `请求按登录态和歌单归属选择删除或取消收藏`() = runTest {
        var userId: Long? = 42L
        val harness = Harness { userId }
        val coordinator = harness.coordinator(this)

        coordinator.request(ownedTarget)
        assertEquals(PlaylistRemovalKind.DELETE, confirmState(coordinator).kind)

        coordinator.request(othersTarget)
        assertEquals(PlaylistRemovalKind.UNSUBSCRIBE, confirmState(coordinator).kind)

        userId = null
        coordinator.request(ownedTarget)
        assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
    }

    @Test
    fun `非法歌单和我喜欢的音乐不会显示确认页`() = runTest {
        val harness = Harness { 42L }
        val coordinator = harness.coordinator(this)

        listOf(
            ownedTarget.copy(id = 0L),
            ownedTarget.copy(ownerId = 0L),
            ownedTarget.copy(isLikedSongs = true)
        ).forEach { target ->
            coordinator.request(target)
            assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
        }
    }

    @Test
    fun `确认成功只调用一次并在回调后隐藏`() = runTest {
        val harness = Harness { 42L }
        val coordinator = harness.coordinator(this)

        coordinator.request(ownedTarget)
        coordinator.confirm()
        coordinator.confirm()
        runCurrent()

        assertEquals(1, harness.calls.size)
        assertEquals(PlaylistRemovalKind.DELETE, harness.calls.single().kind)
        assertTrue((coordinator.state.value as PlaylistRemovalState.Confirm).isSubmitting)

        harness.responses.single().complete(Result.success(Unit))
        runCurrent()

        assertEquals(listOf(ownedTarget.id), harness.removedIds)
        assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
    }

    @Test
    fun `失败保留错误确认页并允许重试`() = runTest {
        val harness = Harness { 42L }
        val coordinator = harness.coordinator(this)

        coordinator.request(othersTarget)
        coordinator.confirm()
        runCurrent()
        harness.responses[0].complete(Result.failure(IllegalStateException("server unavailable")))
        runCurrent()

        val failed = confirmState(coordinator)
        assertFalse(failed.isSubmitting)
        assertEquals("server unavailable", failed.error)
        assertEquals(1, harness.calls.size)

        coordinator.confirm()
        runCurrent()
        assertEquals(2, harness.calls.size)
        harness.responses[1].complete(Result.success(Unit))
        runCurrent()

        assertEquals(listOf(othersTarget.id), harness.removedIds)
        assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
    }

    @Test
    fun `取消后不会自动重试或消费迟到成功`() = runTest {
        val harness = Harness { 42L }
        val coordinator = harness.coordinator(this)

        coordinator.request(ownedTarget)
        coordinator.confirm()
        runCurrent()
        coordinator.dismiss()
        harness.responses.single().complete(Result.success(Unit))
        runCurrent()

        coordinator.confirm()
        assertEquals(1, harness.calls.size)
        assertTrue(harness.removedIds.isEmpty())
        assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
    }

    @Test
    fun `reset会使旧请求的迟到回调失效`() = runTest {
        val harness = Harness { 42L }
        val coordinator = harness.coordinator(this)

        coordinator.request(ownedTarget)
        coordinator.confirm()
        runCurrent()
        coordinator.reset()
        harness.responses.single().complete(Result.success(Unit))
        runCurrent()

        assertTrue(harness.removedIds.isEmpty())
        assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
    }

    @Test
    fun `确认和执行期间账号切换不会删除新账号的条目`() = runTest {
        var userId = 42L
        val harness = Harness { userId.toLong() }
        val coordinator = harness.coordinator(this)

        coordinator.request(ownedTarget)
        coordinator.confirm()
        runCurrent()
        userId = 99L
        harness.responses.single().complete(Result.success(Unit))
        runCurrent()

        assertTrue(harness.removedIds.isEmpty())
        assertEquals(PlaylistRemovalState.Hidden, coordinator.state.value)
    }

    private fun confirmState(coordinator: PlaylistRemovalCoordinator): PlaylistRemovalState.Confirm =
        coordinator.state.value as PlaylistRemovalState.Confirm

    private class Harness(private val userId: () -> Long?) {
        data class Call(val id: Long, val kind: PlaylistRemovalKind)

        val calls = mutableListOf<Call>()
        val responses = mutableListOf<CompletableDeferred<Result<Unit>>>()
        val removedIds = mutableListOf<Long>()

        fun coordinator(scope: kotlinx.coroutines.CoroutineScope) = PlaylistRemovalCoordinator(
            scope = scope,
            currentUserId = userId,
            remove = { id, kind ->
                calls += Call(id, kind)
                CompletableDeferred<Result<Unit>>().also { responses += it }.await()
            },
            onRemoved = { removedIds += it }
        )
    }

    private companion object {
        val ownedTarget = PlaylistRemovalTarget(
            id = 100L,
            title = "我的歌单",
            ownerId = 42L
        )
        val othersTarget = PlaylistRemovalTarget(
            id = 200L,
            title = "收藏的歌单",
            ownerId = 7L
        )
    }
}
