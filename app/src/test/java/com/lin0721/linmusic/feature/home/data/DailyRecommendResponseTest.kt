package com.lin0721.linmusic.feature.home.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.POST
import kotlinx.coroutines.runBlocking

class DailyRecommendResponseTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Test fun modernAndLegacySongsPreserveArtistAlbumAndDuration() {
        val modern = json.decodeFromString<DailyRecommendSongsResponse>("""{"code":200,"data":{"dailySongs":[{"id":42,"name":"song","ar":[{"id":1,"name":"artist"}],"al":{"id":2,"name":"album"},"dt":120000}]}}""")
        val legacy = json.decodeFromString<DailyRecommendSongsResponse>("""{"code":200,"recommend":[{"id":42,"name":"song","artists":[{"id":1,"name":"artist"}],"album":{"id":2,"name":"album"},"duration":120000}]}""")
        assertEquals(modern.songs, legacy.songs)
        assertEquals(120000L, modern.songs!!.single().dt)
    }
    @Test fun emptyResponseIsDifferentFromMissingPayloadAndLoginError() {
        assertEquals(emptyList<DailySong>(), json.decodeFromString<DailyRecommendSongsResponse>("""{"code":200,"data":{"dailySongs":[]}}""").songs)
        assertNull(json.decodeFromString<DailyRecommendSongsResponse>("""{"code":200,"data":{}}""").songs)
        val unauth = json.decodeFromString<DailyRecommendSongsResponse>("""{"code":301,"msg":"需要登录"}""")
        assertFalse(unauth.isSuccess); assertEquals("需要登录", unauth.message)
    }
    @Test fun dailyRequestUsesTheWeapiEndpoint() {
        val method = HomeApi::class.java.methods.single { it.name == "getDailyRecommendSongs" }
        assertEquals("/weapi/v3/discovery/recommend/songs", method.getAnnotation(POST::class.java)!!.value)
        val legacy = HomeApi::class.java.methods.single { it.name == "getLegacyDailyRecommendSongs" }
        assertEquals("/weapi/v1/discovery/recommend/songs", legacy.getAnnotation(POST::class.java)!!.value)
    }
    @Test fun actualNullPayloadFallsBackToAnExplicitEmptyDailyList() = runBlocking {
        val primary = json.decodeFromString<DailyRecommendSongsResponse>("""{"code":200,"data":null}""")
        val legacy = json.decodeFromString<DailyRecommendSongsResponse>("""{"code":200,"recommend":[]}""")
        var calls = 0
        val result = resolveDailyRecommendations({ primary }, { calls++; legacy })
        assertEquals(1, calls)
        assertEquals(emptyList<DailySong>(), result.songs)
    }
    @Test fun primarySongsAndExplicitEmptyListsDoNotTriggerFallback() = runBlocking {
        for (songs in listOf(emptyList(), listOf(DailySong(id = 42)))) {
            val primary = DailyRecommendSongsResponse(200, DailyRecommendData(songs))
            assertSame(primary, resolveDailyRecommendations({ primary }, { error("Unexpected fallback") }))
        }
    }
    @Test fun authAndServiceErrorsAreNeverReplacedByFallback() = runBlocking {
        for (code in listOf(301, 403, 500)) {
            val primary = DailyRecommendSongsResponse(code)
            assertSame(primary, resolveDailyRecommendations({ primary }, { error("Unexpected fallback") }))
        }
    }
    @Test fun fallbackPreservesRealSongsAndDoesNotMaskFailure() = runBlocking {
        val missing = DailyRecommendSongsResponse(200)
        val song = DailySong(id = 42)
        assertEquals(listOf(song), resolveDailyRecommendations({ missing }, {
            DailyRecommendSongsResponse(200, recommend = listOf(song))
        }).songs)
        val failure = DailyRecommendSongsResponse(301, message = "需要登录")
        assertSame(failure, resolveDailyRecommendations({ missing }, { failure }))
        assertNull(resolveDailyRecommendations({ missing }, { missing }).songs)
    }
}
