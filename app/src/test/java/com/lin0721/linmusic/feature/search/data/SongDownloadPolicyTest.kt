package com.lin0721.linmusic.feature.search.data

import com.lin0721.linmusic.core.player.data.FreeTrialInfo
import com.lin0721.linmusic.core.player.data.SongUrlItem
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.Tag

class SongDownloadPolicyTest {
    private val valid = SongUrlItem(id = 42, url = "https://example.com/song.mp3")

    @Test fun acceptsOnlyTheRequestedFullSong() {
        assertEquals(valid, SongDownloadResponse(200, valid).requireFullDownload(42))
        listOf(
            SongDownloadResponse(403, valid), SongDownloadResponse(200, null),
            SongDownloadResponse(200, valid.copy(id = 43)), SongDownloadResponse(200, valid.copy(url = "")),
            SongDownloadResponse(200, valid.copy(freeTrialInfo = FreeTrialInfo(0, 30)))
        ).forEach { assertTrue(runCatching { it.requireFullDownload(42) }.isFailure) }
    }

    @Test fun allAccountRequestsRequireAnExplicitSessionTag() {
        SearchSongActionsApi::class.java.declaredMethods.forEach { method ->
            assertTrue(method.name, method.parameterAnnotations.any { annotations -> annotations.any { it is Tag } })
        }
    }
}
