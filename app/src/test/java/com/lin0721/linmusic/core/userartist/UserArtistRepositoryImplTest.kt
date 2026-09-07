package com.lin0721.linmusic.core.userartist

import com.lin0721.linmusic.core.model.Artist
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UserArtistRepositoryImplTest {
    private class Api(private val subscribed: suspend () -> ArtistSublistResponse) : UserArtistApi {
        var recommendedCalls = 0
        override suspend fun getArtistSublist(body: ArtistSublistRequest) = subscribed()
        override suspend fun getTopArtists(body: TopArtistsRequest): TopArtistsResponse {
            recommendedCalls++
            return TopArtistsResponse(200, listOf(Artist(id = 999, name = "推荐歌手")))
        }
    }

    @Test fun emptySubscriptionsRemainEmptyWithoutRecommendations() = runTest {
        val api = Api { ArtistSublistResponse(200, emptyList()) }
        val result = UserArtistRepositoryImpl(api).getFavoriteArtists().first()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        assertEquals(0, api.recommendedCalls)
    }

    @Test fun failedSubscriptionsDoNotBecomeRecommendedArtists() = runTest {
        val api = Api { throw IOException("offline") }
        assertTrue(UserArtistRepositoryImpl(api).getFavoriteArtists().first().isFailure)
        assertEquals(0, api.recommendedCalls)
    }

    @Test fun onlySubscribedArtistsAreMapped() = runTest {
        val api = Api { ArtistSublistResponse(200, listOf(Artist(12, "关注歌手", "fallback-cover", ""))) }
        val result = UserArtistRepositoryImpl(api).getFavoriteArtists().first().getOrThrow()
        assertEquals(listOf(12L), result.map { it.id })
        assertEquals("fallback-cover", result.single().avatarUrl)
        assertEquals(0, api.recommendedCalls)
    }

    @Test fun cancellationPropagatesWithoutStartingARecommendationRequest() = runTest {
        val api = Api { throw CancellationException("cancelled") }
        try {
            UserArtistRepositoryImpl(api).getFavoriteArtists().first()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, api.recommendedCalls)
    }

    @Test fun serverFailureDoesNotReturnRecommendationContent() = runTest {
        val api = Api { ArtistSublistResponse(500, emptyList()) }
        assertTrue(UserArtistRepositoryImpl(api).getFavoriteArtists().first().isFailure)
        assertEquals(0, api.recommendedCalls)
    }

    @Test fun primaryArtistArtworkIsPreserved() = runTest {
        val api = Api { ArtistSublistResponse(200, listOf(Artist(12, "关注歌手", "fallback", "primary"))) }
        assertEquals("primary", UserArtistRepositoryImpl(api).getFavoriteArtists().first().getOrThrow().single().avatarUrl)
    }
}
