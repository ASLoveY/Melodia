package com.lin0721.linmusic.feature.search.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lin0721.linmusic.core.model.*
import com.lin0721.linmusic.core.ui.components.*
import com.lin0721.linmusic.core.ui.theme.MelodiaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchSongOptionsTest {
    @get:Rule val compose = createComposeRule()
    private val song = Track(42, "测试歌曲", listOf(Artist(1, "歌手甲"), Artist(2, "歌手乙")), Album(3, "测试专辑"))

    @Test fun longPressOpensOptionsWithoutPlayingAndTapStillPlays() {
        var plays = 0
        var longPresses = 0
        compose.setContent { MelodiaTheme {
            SongRow(SongRowData(42, "测试歌曲", "歌手", null), onClick = { plays++ }, onLongClick = { longPresses++ })
        } }
        compose.onNodeWithText("测试歌曲").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, plays); assertEquals(1, longPresses) }
        compose.onNodeWithText("测试歌曲").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun choosesTheRequestedArtistInsteadOfAlwaysTheFirstOne() {
        var selected = 0L
        compose.setContent { MelodiaTheme(darkTheme = false) {
            SearchSongOptionsSheet(song, {}, {}, {}, {}, { selected = it }, {}, {})
        } }
        compose.onNodeWithTag("song_action_查看歌手").performClick()
        compose.onNodeWithTag("song_action_歌手乙").performClick()
        compose.runOnIdle { assertEquals(2L, selected) }
    }

    @Test fun queueAlbumDownloadCollectAndShareActionsAreDistinctInDarkTheme() {
        val actions = mutableListOf<String>()
        compose.setContent { MelodiaTheme(darkTheme = true) {
            SearchSongOptionsSheet(song, {}, { actions += "queue:$it" }, { actions += "collect" },
                { actions += "download" }, {}, { actions += "album:$it" }, { actions += "share" })
        } }
        listOf("下一首播放", "加入播放队列末尾", "添加到歌单", "下载到本地", "查看专辑", "分享歌曲").forEach {
            compose.onNodeWithTag("song_action_$it").performScrollTo().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("queue:true", "queue:false", "collect", "download", "album:3", "share"), actions) }
    }

    @Test fun missingArtistAndAlbumMetadataDisablesNavigation() {
        compose.setContent { MelodiaTheme {
            SearchSongOptionsSheet(Track(42, "无元数据"), {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("song_action_查看歌手").assertIsNotEnabled()
        compose.onNodeWithTag("song_action_查看专辑").assertIsNotEnabled()
    }
}
