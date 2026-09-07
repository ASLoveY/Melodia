package com.lin0721.linmusic.core.auth

import com.lin0721.linmusic.core.api.AccountInfoResponse
import com.lin0721.linmusic.core.api.UserProfile as RemoteUserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncProfileAfterLoginUseCaseTest {

    @Test
    fun `账号资料为空时回滚本次Cookie和旧资料`() = runTest {
        val oldProfile = UserProfile(uid = 7L, nickname = "旧账号", avatarUrl = "old-avatar")
        val store = FakeLoginSessionStore(
            currentCookies = "old-cookie",
            currentProfile = oldProfile
        )
        val useCase = SyncProfileAfterLoginUseCase(store, ImmediateAuthRepository(emptyFlow()))

        assertNull(useCase("new-cookie"))
        assertEquals("old-cookie", store.currentCookies)
        assertEquals(oldProfile, store.currentProfile)
        assertEquals(1, store.rollbackCalls)
    }

    @Test
    fun `同步被取消时回滚本次会话`() = runTest {
        val oldProfile = UserProfile(uid = 7L, nickname = "旧账号", avatarUrl = "old-avatar")
        val store = FakeLoginSessionStore(
            currentCookies = "old-cookie",
            currentProfile = oldProfile
        )
        val useCase = SyncProfileAfterLoginUseCase(store, BlockingAuthRepository())

        val job = backgroundScope.launch { useCase("new-cookie") }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals("old-cookie", store.currentCookies)
        assertEquals(oldProfile, store.currentProfile)
        assertEquals(1, store.rollbackCalls)
    }

    @Test
    fun `旧请求不能覆盖新登录的Cookie或资料`() = runTest {
        val store = FakeLoginSessionStore(
            currentCookies = "old-cookie",
            currentProfile = UserProfile(uid = 7L, nickname = "旧账号", avatarUrl = "old-avatar")
        )
        val firstResult = CompletableDeferred<Result<AccountInfoResponse>>()
        val secondResult = CompletableDeferred<Result<AccountInfoResponse>>()
        val firstUseCase = SyncProfileAfterLoginUseCase(store, DeferredAuthRepository(firstResult))
        val secondUseCase = SyncProfileAfterLoginUseCase(store, DeferredAuthRepository(secondResult))
        var firstProfile: UserProfile? = null
        var secondProfile: UserProfile? = null

        val firstJob = backgroundScope.launch {
            firstProfile = firstUseCase("first-cookie")
        }
        runCurrent()
        val secondJob = backgroundScope.launch {
            secondProfile = secondUseCase("second-cookie")
        }
        runCurrent()

        firstResult.complete(Result.success(account("first-profile")))
        firstJob.join()
        assertNull(firstProfile)
        assertEquals("second-cookie", store.currentCookies)

        secondResult.complete(Result.success(account("second-profile")))
        secondJob.join()

        assertEquals("second-cookie", store.currentCookies)
        assertEquals(UserProfile(42L, "second-profile", "second-profile-avatar"), secondProfile)
        assertEquals(secondProfile, store.currentProfile)
    }

    @Test
    fun `前一次取消后下一次失败恢复最初会话`() = runTest {
        val oldProfile = UserProfile(uid = 7L, nickname = "旧账号", avatarUrl = "old-avatar")
        val store = FakeLoginSessionStore("old-cookie", oldProfile)
        val firstResult = CompletableDeferred<Result<AccountInfoResponse>>()
        val secondResult = CompletableDeferred<Result<AccountInfoResponse>>()
        val useCase = SyncProfileAfterLoginUseCase(
            store,
            SequencedAuthRepository(ArrayDeque(listOf(firstResult, secondResult)))
        )

        val firstJob = backgroundScope.launch { useCase("first-cookie") }
        runCurrent()
        val secondJob = backgroundScope.launch { useCase("second-cookie") }
        runCurrent()

        firstJob.cancelAndJoin()
        runCurrent()
        secondResult.complete(Result.success(AccountInfoResponse(code = 200)))
        secondJob.join()

        assertEquals("old-cookie", store.currentCookies)
        assertEquals(oldProfile, store.currentProfile)
    }

    private fun account(nickname: String): AccountInfoResponse = AccountInfoResponse(
        code = 200,
        profile = RemoteUserProfile(
            userId = 42L,
            nickname = nickname,
            avatarUrl = "$nickname-avatar"
        )
    )
}

private class FakeLoginSessionStore(
    var currentCookies: String?,
    var currentProfile: UserProfile?
) : LoginSessionStore {

    private var revision = 0L
    var rollbackCalls = 0
        private set

    override suspend fun beginLogin(cookies: String): LoginSessionAttempt {
        val previousRevision = revision
        val attempt = LoginSessionAttempt(
            cookies = cookies,
            previousCookies = currentCookies,
            previousProfile = currentProfile,
            previousRevision = previousRevision,
            revision = previousRevision + 1L
        )
        currentCookies = cookies
        revision = attempt.revision
        return attempt
    }

    override suspend fun saveUserProfileIfCurrent(
        attempt: LoginSessionAttempt,
        profile: UserProfile
    ): Boolean {
        if (currentCookies != attempt.cookies || revision != attempt.revision) return false
        currentProfile = profile
        return true
    }

    override suspend fun rollbackLogin(attempt: LoginSessionAttempt) {
        rollbackCalls++
        if (currentCookies != attempt.cookies || revision != attempt.revision) return
        currentCookies = attempt.previousCookies
        currentProfile = attempt.previousProfile
        revision += 1L
    }
}

private class ImmediateAuthRepository(
    private val accountFlow: Flow<Result<AccountInfoResponse>>
) : AuthRepository {
    override fun getAccountInfo(): Flow<Result<AccountInfoResponse>> = accountFlow

    override fun logout(): Flow<Result<Unit>> = flowOf(Result.success(Unit))
}

private class BlockingAuthRepository : AuthRepository {
    override fun getAccountInfo(): Flow<Result<AccountInfoResponse>> = flow {
        awaitCancellation()
    }

    override fun logout(): Flow<Result<Unit>> = flowOf(Result.success(Unit))
}

private class DeferredAuthRepository(
    private val result: CompletableDeferred<Result<AccountInfoResponse>>
) : AuthRepository {
    override fun getAccountInfo(): Flow<Result<AccountInfoResponse>> = flow {
        emit(result.await())
    }

    override fun logout(): Flow<Result<Unit>> = flowOf(Result.success(Unit))
}

private class SequencedAuthRepository(
    private val results: ArrayDeque<CompletableDeferred<Result<AccountInfoResponse>>>
) : AuthRepository {
    override fun getAccountInfo(): Flow<Result<AccountInfoResponse>> = flow {
        emit(results.removeFirst().await())
    }

    override fun logout(): Flow<Result<Unit>> = flowOf(Result.success(Unit))
}
