package com.lin0721.linmusic.feature.library.data

import com.lin0721.linmusic.core.contentfilter.ContentFilter
import com.lin0721.linmusic.core.auth.UserSessionTag
import com.lin0721.linmusic.core.network.AppError
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLibraryApi(
    private val delete: suspend (PlaylistDeleteRequest) -> PlaylistActionResponse = {
        error("delete not used in this test")
    },
    private val subscription: suspend (String, PlaylistSubscriptionRequest) -> PlaylistActionResponse = { _, _ ->
        error("subscription not used in this test")
    }
) : LibraryApi {
    override suspend fun getUserRecord(body: UserRecordRequest): UserRecordResponse = error("not used")
    override suspend fun getAlbumSublist(body: AlbumSublistRequest): AlbumSublistResponse = error("not used")
    override suspend fun getUserSubcount(body: com.lin0721.linmusic.core.model.EmptyBody): UserSubcountResponse = error("not used")
    override suspend fun deletePlaylist(body: PlaylistDeleteRequest, sessionTag: UserSessionTag): PlaylistActionResponse = delete(body)
    override suspend fun updatePlaylistSubscription(
        op: String,
        body: PlaylistSubscriptionRequest,
        sessionTag: UserSessionTag
    ): PlaylistActionResponse = subscription(op, body)
}

class LibraryRepositoryImplTest {

    @Test
    fun `delete playlist sends positive id as JSON array string`() = runBlocking {
        var request: PlaylistDeleteRequest? = null
        val repository = LibraryRepositoryImpl(
            apiService = FakeLibraryApi(delete = { body ->
                request = body
                PlaylistActionResponse(code = 200)
            }),
            contentFilter = unusedContentFilter()
        )

        val result = repository.deletePlaylist(123L, testSession).first()

        assertTrue(result.isSuccess)
        assertEquals("[123]", request?.ids)
    }

    @Test
    fun `unsubscribe playlist calls unsubscribe operation and preserves id`() = runBlocking {
        var operation: String? = null
        var request: PlaylistSubscriptionRequest? = null
        val repository = LibraryRepositoryImpl(
            apiService = FakeLibraryApi(subscription = { op, body ->
                operation = op
                request = body
                PlaylistActionResponse(code = 200)
            }),
            contentFilter = unusedContentFilter()
        )

        val result = repository.unsubscribePlaylist(456L, testSession).first()

        assertTrue(result.isSuccess)
        assertEquals("unsubscribe", operation)
        assertEquals(456L, request?.id)
    }

    @Test
    fun `delete business failure is normalized by apiFlow`() = runBlocking {
        val repository = LibraryRepositoryImpl(
            apiService = FakeLibraryApi(delete = {
                PlaylistActionResponse(code = 400, message = "cannot delete")
            }),
            contentFilter = unusedContentFilter()
        )

        val error = repository.deletePlaylist(123L, testSession).first().exceptionOrNull()

        assertTrue(error is AppError.BizError)
        assertEquals(400, (error as AppError.BizError).code)
        assertEquals("cannot delete", error.rawMsg)
    }

    @Test
    fun `unsubscribe network failure is normalized by apiFlow`() = runBlocking {
        val repository = LibraryRepositoryImpl(
            apiService = FakeLibraryApi(
                subscription = { _, _ -> throw IOException("network down") }
            ),
            contentFilter = unusedContentFilter()
        )

        val error = repository.unsubscribePlaylist(456L, testSession).first().exceptionOrNull()

        assertTrue(error === AppError.NetworkError)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `delete rejects non-positive id`() {
        LibraryRepositoryImpl(FakeLibraryApi(), unusedContentFilter()).deletePlaylist(0L, testSession)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsubscribe rejects non-positive id`() {
        LibraryRepositoryImpl(FakeLibraryApi(), unusedContentFilter()).unsubscribePlaylist(-1L, testSession)
    }

    /** Delete/subscribe tests never touch the content filter; avoid Android DataStore setup. */
    private fun unusedContentFilter(): ContentFilter {
        return ContentFilter(flowOf(emptySet()))
    }

    private companion object {
        val testSession = UserSessionTag(uid = 42L, revision = 1L)
    }
}
