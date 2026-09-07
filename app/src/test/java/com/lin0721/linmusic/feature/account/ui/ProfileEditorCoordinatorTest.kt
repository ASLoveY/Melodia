package com.lin0721.linmusic.feature.account.ui

import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorCoordinatorTest {

    @Test
    fun `未登录加载进入 SignedOut`() = runTest {
        val harness = Harness(uid = null)
        val coordinator = harness.coordinator(this)

        coordinator.loadProfile()

        assertEquals(ProfileEditorState.SignedOut, coordinator.state.value)
        assertEquals(0, harness.loadCalls)
    }

    @Test
    fun `加载成功保留完整资料并初始化无改动草稿`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = harness.coordinator(this)
        coordinator.loadProfile()
        runCurrent()
        harness.loads.single().complete(Result.success(profile()))
        runCurrent()

        val ready = coordinator.state.value as ProfileEditorState.Ready
        assertEquals(profile(), ready.profile)
        assertEquals("原昵称", ready.nickname)
        assertEquals("原签名", ready.signature)
        assertFalse(ready.hasChanges)
    }

    @Test
    fun `旧加载回调不能覆盖新一轮加载`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = harness.coordinator(this)

        coordinator.loadProfile()
        runCurrent()
        coordinator.loadProfile()
        runCurrent()
        harness.loads[0].complete(Result.success(profile(nickname = "旧资料")))
        harness.loads[1].complete(Result.success(profile(nickname = "新资料")))
        runCurrent()

        assertEquals("新资料", (coordinator.state.value as ProfileEditorState.Ready).nickname)
        assertEquals(2, harness.loadCalls)
    }

    @Test
    fun `保存期间编辑被拒绝且请求不重复`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = loadedCoordinator(harness, this)

        coordinator.editNickname("新昵称")
        coordinator.saveProfile()
        coordinator.saveProfile()
        runCurrent()

        val saving = coordinator.state.value as ProfileEditorState.Ready
        assertTrue(saving.isSaving)
        coordinator.editSignature("不应写入")
        assertEquals("原签名", (coordinator.state.value as ProfileEditorState.Ready).signature)
        assertEquals(1, harness.saveCalls.size)

        harness.saveCalls.single().response.complete(Result.success(Unit))
        runCurrent()
        assertTrue((coordinator.state.value as ProfileEditorState.Ready).saved)
    }

    @Test
    fun `昵称为空时不提交`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = loadedCoordinator(harness, this)

        coordinator.editNickname("   ")
        coordinator.saveProfile()

        val ready = coordinator.state.value as ProfileEditorState.Ready
        assertEquals("昵称不能为空", ready.error)
        assertFalse(ready.isSaving)
        assertTrue(harness.saveCalls.isEmpty())
    }

    @Test
    fun `原始资料字段不完整时不提交并保留草稿`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = harness.coordinator(this)
        coordinator.loadProfile()
        runCurrent()
        harness.loads.single().complete(Result.success(profile().copy(gender = null)))
        runCurrent()

        coordinator.editNickname("新昵称")
        coordinator.saveProfile()

        val ready = coordinator.state.value as ProfileEditorState.Ready
        assertEquals("资料不完整，暂时无法保存", ready.error)
        assertEquals("新昵称", ready.nickname)
        assertTrue(harness.saveCalls.isEmpty())
    }

    @Test
    fun `保存失败保留草稿和完整原始字段并可重试`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = loadedCoordinator(harness, this)

        coordinator.editNickname("新昵称")
        coordinator.editSignature("新签名")
        coordinator.saveProfile()
        runCurrent()
        val firstCall = harness.saveCalls.single()
        assertEquals(123L, firstCall.profile.birthday)
        assertEquals(110000, firstCall.profile.province)
        assertEquals(110101, firstCall.profile.city)
        assertEquals(2, firstCall.profile.gender)
        assertEquals("头像", firstCall.profile.avatarUrl)
        firstCall.response.complete(Result.failure(IllegalStateException("服务不可用")))
        runCurrent()

        val failed = coordinator.state.value as ProfileEditorState.Ready
        assertEquals("新昵称", failed.nickname)
        assertEquals("新签名", failed.signature)
        assertEquals("服务不可用", failed.error)
        assertFalse(failed.saved)

        coordinator.saveProfile()
        runCurrent()
        harness.saveCalls[1].response.complete(Result.success(Unit))
        runCurrent()
        assertTrue((coordinator.state.value as ProfileEditorState.Ready).saved)
    }

    @Test
    fun `onSaved返回false时保留草稿并提示会话变化`() = runTest {
        val harness = Harness(uid = 7L, onSavedResult = false)
        val coordinator = loadedCoordinator(harness, this)

        coordinator.editNickname("新昵称")
        coordinator.saveProfile()
        runCurrent()
        harness.saveCalls.single().response.complete(Result.success(Unit))
        runCurrent()

        val ready = coordinator.state.value as ProfileEditorState.Ready
        assertEquals("账号状态已变化，请刷新后重试", ready.error)
        assertEquals("原昵称", ready.profile.nickname)
        assertFalse(ready.saved)
    }

    @Test
    fun `账号切换或reset会使旧保存回调失效`() = runTest {
        val harness = Harness(uid = 7L)
        val coordinator = loadedCoordinator(harness, this)

        coordinator.editSignature("新签名")
        coordinator.saveProfile()
        runCurrent()
        harness.uid = 9L
        coordinator.onAccountChanged()
        harness.saveCalls.single().response.complete(Result.success(Unit))
        runCurrent()

        assertTrue(harness.savedProfiles.isEmpty())
        assertEquals(ProfileEditorState.Loading, coordinator.state.value)
    }

    private suspend fun loadedCoordinator(harness: Harness, scope: TestScope): ProfileEditorCoordinator {
        val coordinator = harness.coordinator(scope)
        coordinator.loadProfile()
        scope.runCurrent()
        harness.loads.single().complete(Result.success(profile()))
        scope.runCurrent()
        return coordinator
    }

    private class Harness(
        var uid: Long?,
        var onSavedResult: Boolean = true
    ) {
        data class SaveCall(
            val profile: UserProfileDetails,
            val nickname: String,
            val signature: String,
            val response: CompletableDeferred<Result<Unit>>
        )

        val loads = mutableListOf<CompletableDeferred<Result<UserProfileDetails>>>()
        val saveCalls = mutableListOf<SaveCall>()
        val savedProfiles = mutableListOf<UserProfileDetails>()
        var loadCalls = 0

        fun coordinator(scope: CoroutineScope): ProfileEditorCoordinator = ProfileEditorCoordinator(
            scope = scope,
            currentUserId = { uid },
            load = {
                loadCalls += 1
                CompletableDeferred<Result<UserProfileDetails>>().also { loads += it }.await()
            },
            save = { updated, nickname, signature ->
                val response = CompletableDeferred<Result<Unit>>()
                saveCalls += SaveCall(updated, nickname, signature, response)
                response.await()
            },
            onSaved = { updated ->
                savedProfiles += updated
                onSavedResult
            }
        )
    }

    private companion object {
        fun profile(
            nickname: String = "原昵称",
            signature: String = "原签名"
        ) = UserProfileDetails(
            userId = 7L,
            nickname = nickname,
            avatarUrl = "头像",
            signature = signature,
            gender = 2,
            birthday = 123L,
            province = 110000,
            city = 110101
        )
    }
}
