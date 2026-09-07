package com.lin0721.linmusic

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodiaNavigationStateTest {

    // 状态读写发生在组合之外，需显式包一层快照
    private fun <T> inSnapshot(block: () -> T): T {
        val snapshot = Snapshot.takeMutableSnapshot()
        return try {
            val result = snapshot.enter(block)
            snapshot.apply()
            result
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun `初始处于主页且无法回退`() = inSnapshot {
        val nav = MelodiaNavigationState()
        assertEquals(Screen.Home, nav.currentScreen)
        assertFalse(nav.canNavigateBack)
    }

    @Test
    fun `跳转到详情页后可以回退`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.navigateTo(Screen.Settings)
        assertEquals(Screen.Settings, nav.currentScreen)
        assertTrue(nav.canNavigateBack)

        nav.navigateBack()
        assertEquals(Screen.Home, nav.currentScreen)
        assertFalse(nav.canNavigateBack)
    }

    @Test
    fun `重复跳转到当前页不入栈`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.navigateTo(Screen.Settings)
        nav.navigateTo(Screen.Settings)
        nav.navigateBack()
        assertEquals(Screen.Home, nav.currentScreen)
    }

    @Test
    fun `跳转到主页会清空历史`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.navigateTo(Screen.Settings)
        nav.navigateTo(Screen.Artist)
        nav.navigateTo(Screen.Home)
        assertEquals(Screen.Home, nav.currentScreen)
        assertFalse(nav.canNavigateBack)
    }

    @Test
    fun `底栏入口始终保留主页作为回退目标`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.navigateTo(Screen.Settings)
        nav.navigateTo(Screen.Artist)
        nav.navigateTo(Screen.Library)

        assertEquals(Screen.Library, nav.currentScreen)
        nav.navigateBack()
        // 中间的 Settings/Artist 已被清掉，直接回到主页
        assertEquals(Screen.Home, nav.currentScreen)
        assertFalse(nav.canNavigateBack)
    }

    @Test
    fun `已在栈底时回退不越界`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.navigateBack()
        nav.navigateBack()
        assertEquals(Screen.Home, nav.currentScreen)
    }

    @Test
    fun `打开歌单会记录ID与专辑标记并跳转`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.openPlaylist(id = 123L, isAlbum = true)
        assertEquals(Screen.Playlist, nav.currentScreen)
        assertEquals(123L, nav.activePlaylistId)
        assertTrue(nav.activePlaylistIsAlbum)
    }

    @Test
    fun `打开歌手会记录ID并跳转`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.openArtist(456L)
        assertEquals(Screen.Artist, nav.currentScreen)
        assertEquals(456L, nav.activeArtistId)
    }

    @Test
    fun `从主页搜索框进入时自动聚焦`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.openSearch(autoFocus = true)
        assertEquals(Screen.Search, nav.currentScreen)
        assertTrue(nav.searchAutoFocus)
    }

    @Test
    fun `从底栏进入搜索页时不自动聚焦`() = inSnapshot {
        val nav = MelodiaNavigationState()
        nav.openSearch(autoFocus = true)
        nav.navigateTo(Screen.Home)
        nav.openTab(Screen.Search)
        assertEquals(Screen.Search, nav.currentScreen)
        assertFalse(nav.searchAutoFocus)
    }

    @Test
    fun `创建歌单登录成功后只消费一次待创建操作`() {
        var state = CreateLoginFlowState().requestLogin().openWebLogin()

        state = state.reportLoginSuccess()
        assertEquals(CreateLoginSurface.Syncing, state.surface)
        assertTrue(state.pendingCreate)
        assertEquals(state, state.reportLoginSuccess())

        val restored = state.consumePendingCreate()
        assertFalse(restored.pendingCreate)
        assertEquals(CreateLoginSurface.None, restored.surface)
        assertEquals(restored, restored.consumePendingCreate())
    }

    @Test
    fun `取消创建歌单登录会清除待执行操作`() {
        val chooserCancelled = CreateLoginFlowState()
            .requestLogin()
            .cancel()
        assertEquals(CreateLoginSurface.None, chooserCancelled.surface)
        assertFalse(chooserCancelled.pendingCreate)

        val webCancelled = CreateLoginFlowState()
            .requestLogin()
            .openWebLogin()
            .cancel()
        assertEquals(CreateLoginSurface.None, webCancelled.surface)
        assertFalse(webCancelled.pendingCreate)

        val syncCancelled = CreateLoginFlowState()
            .requestLogin()
            .openWebLogin()
            .reportLoginSuccess()
            .cancel()
        assertEquals(CreateLoginSurface.None, syncCancelled.surface)
        assertFalse(syncCancelled.pendingCreate)
    }

    @Test
    fun `旧登录请求的迟到回调不能消费新请求`() {
        val firstRequest = CreateLoginFlowState()
            .requestLogin(requestId = 1L)
            .openWebLogin()
            .reportLoginSuccess()
            .cancel()
        val secondRequest = firstRequest.requestLogin(requestId = 2L)

        assertFalse(secondRequest.ownsPendingRequest(1L))
        assertTrue(secondRequest.ownsPendingRequest(2L))
    }
}
