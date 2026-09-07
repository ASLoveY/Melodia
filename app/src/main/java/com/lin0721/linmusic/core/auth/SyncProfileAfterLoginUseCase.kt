package com.lin0721.linmusic.core.auth

import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "SyncProfileAfterLogin"

// 登录成功后的账号同步，供 home/library/artist/playlist 等域共用
class SyncProfileAfterLoginUseCase(
    private val userPreferences: LoginSessionStore,
    private val authRepository: AuthRepository
) {

    // Koin 以单例提供本用例；串行化 begin -> 请求 -> commit/rollback，
    // 避免后一个登录把前一个登录的临时 Cookie 当成可恢复快照。
    private val loginMutex = Mutex()

    // 保存 Cookie 并拉取账号信息落库。失败/取消时只回滚仍属于本次登录的会话。
    suspend operator fun invoke(cookies: String): UserProfile? = loginMutex.withLock {
        // 确保 Cookie 已写入且 attempt 已拿到后再响应取消，否则可能留下
        // 没有快照可回滚的临时会话。
        val attempt = withContext(NonCancellable) {
            userPreferences.beginLogin(cookies)
        }
        var committed = false
        try {
            val response = authRepository.getAccountInfo().firstOrNull()?.getOrNull()
                ?: return null
            val remoteProfile = response.profile ?: return null
            val profile = UserProfile(
                uid = remoteProfile.userId,
                nickname = remoteProfile.nickname,
                avatarUrl = remoteProfile.avatarUrl
            )

            // DataStore 内以 Cookie + revision 原子核对，旧请求不能写入新会话。
            if (!userPreferences.saveUserProfileIfCurrent(attempt, profile)) return null
            committed = true
            return profile
        } finally {
            if (!committed) {
                // 取消时仍完成条件回滚，但不把取消异常改写成普通失败。
                withContext(NonCancellable) {
                    runCatching { userPreferences.rollbackLogin(attempt) }
                        .onFailure { AppLogger.w(TAG, "登录会话回滚失败", it) }
                }
            }
        }
    }
}
