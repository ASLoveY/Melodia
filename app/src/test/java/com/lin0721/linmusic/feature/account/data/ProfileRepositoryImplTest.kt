package com.lin0721.linmusic.feature.account.data

import com.lin0721.linmusic.core.model.EmptyBody
import com.lin0721.linmusic.core.auth.UserSessionTag
import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProfileApi(
    private val detail: suspend (Long) -> ProfileDetailResponse = { error("detail not used") },
    private val update: suspend (ProfileUpdateRequest) -> ProfileUpdateResponse = { error("update not used") }
) : ProfileApi {
    override suspend fun getProfile(uid: Long, body: EmptyBody, sessionTag: UserSessionTag?): ProfileDetailResponse = detail(uid)

    override suspend fun updateProfile(body: ProfileUpdateRequest, sessionTag: UserSessionTag): ProfileUpdateResponse = update(body)
}

class ProfileRepositoryImplTest {

    @Test
    fun `update preserves fields that were not edited`() = runBlocking {
        var request: ProfileUpdateRequest? = null
        val profile = completeProfile()
        val repository = ProfileRepositoryImpl(
            FakeProfileApi(update = { body ->
                request = body
                ProfileUpdateResponse(code = 200)
            })
        )

        val result = repository.updateProfile(profile, nickname = "新昵称", signature = "新签名", sessionTag = testSession).first()

        assertTrue(result.isSuccess)
        assertEquals("新昵称", request?.nickname)
        assertEquals("新签名", request?.signature)
        assertEquals(profile.gender, request?.gender)
        assertEquals(profile.birthday, request?.birthday)
        assertEquals(profile.province, request?.province)
        assertEquals(profile.city, request?.city)
    }

    @Test
    fun `missing editable fields can be read but reject update without calling api`() = runBlocking {
        var called = false
        val incomplete = completeProfile().copy(gender = null, birthday = null)
        val repository = ProfileRepositoryImpl(
            FakeProfileApi(update = {
                called = true
                ProfileUpdateResponse(code = 200)
            })
        )

        assertFalse(incomplete.isEditable)
        val result = repository.updateProfile(incomplete, nickname = "新昵称", signature = "新签名", sessionTag = testSession).first()

        assertTrue(result.isFailure)
        assertFalse(called)
    }

    @Test
    fun `detail fields may be missing while display fields remain available`() = runBlocking {
        val repository = ProfileRepositoryImpl(
            FakeProfileApi(detail = {
                ProfileDetailResponse(
                    code = 200,
                    profile = ProfilePayload(
                        userId = 42L,
                        nickname = "昵称",
                        avatarUrl = "avatar",
                        signature = "签名"
                    )
                )
            })
        )

        val profile = repository.getProfile(42L).first().getOrThrow()

        assertEquals("昵称", profile.nickname)
        assertEquals("签名", profile.signature)
        assertFalse(profile.isEditable)
    }

    @Test
    fun `detail account mismatch is rejected`() = runBlocking {
        val repository = ProfileRepositoryImpl(
            FakeProfileApi(detail = {
                ProfileDetailResponse(
                    code = 200,
                    profile = ProfilePayload(userId = 99L, nickname = "其他账号")
                )
            })
        )

        val result = repository.getProfile(42L).first()

        assertTrue(result.isFailure)
    }

    @Test
    fun `update business error is normalized by apiFlow`() = runBlocking {
        val repository = ProfileRepositoryImpl(
            FakeProfileApi(update = { ProfileUpdateResponse(code = 400, message = "invalid") })
        )

        val error = repository.updateProfile(completeProfile(), "新昵称", "新签名", testSession)
            .first()
            .exceptionOrNull()

        assertTrue(error is AppError.BizError)
        assertEquals(400, (error as AppError.BizError).code)
        assertEquals("invalid", error.rawMsg)
    }

    @Test
    fun `detail network error is normalized by apiFlow`() = runBlocking {
        val repository = ProfileRepositoryImpl(
            FakeProfileApi(detail = { throw IOException("network down") })
        )

        val error = repository.getProfile(42L).first().exceptionOrNull()

        assertTrue(error === AppError.NetworkError)
    }

    @Test
    fun `invalid uid is returned as a failed result`() = runBlocking {
        val repository = ProfileRepositoryImpl(FakeProfileApi())

        val result = repository.getProfile(0L).first()

        assertTrue(result.isFailure)
    }

    private fun completeProfile() = UserProfileDetails(
        userId = 42L,
        nickname = "旧昵称",
        avatarUrl = "avatar",
        signature = "旧签名",
        gender = 1,
        birthday = 946684800000L,
        province = 110000,
        city = 110101
    )

    private companion object {
        val testSession = UserSessionTag(uid = 42L, revision = 1L)
    }
}
