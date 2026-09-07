package com.lin0721.linmusic.core.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "UserPreferences"

private val Context.userDataStore by preferencesDataStore(name = "user_prefs")

// 用户基本信息模型
@Serializable
data class UserProfile(
    val uid: Long,
    val nickname: String,
    val avatarUrl: String,
    val signature: String = ""
)

// 登录同步期间需要以一次原子会话写入为边界，避免失败回滚覆盖后续登录。
data class LoginSessionAttempt(
    val cookies: String,
    val previousCookies: String?,
    val previousProfile: UserProfile?,
    val previousRevision: Long,
    val revision: Long
)

interface LoginSessionStore {

    // 写入本次 Cookie，并返回可用于条件提交/回滚的会话快照。
    suspend fun beginLogin(cookies: String): LoginSessionAttempt

    // 只有 Cookie 和 revision 都仍属于本次登录时才写入资料。
    suspend fun saveUserProfileIfCurrent(
        attempt: LoginSessionAttempt,
        profile: UserProfile
    ): Boolean

    // 仅当当前会话仍是本次登录时恢复旧会话；新登录已经开始则跳过。
    suspend fun rollbackLogin(attempt: LoginSessionAttempt)
}

// 用户信息持久化管理 (DataStore + JSON)
class UserPreferences(private val context: Context) : LoginSessionStore {

    companion object {
        private val KEY_USER_PROFILE = stringPreferencesKey("user_profile_json")
        private val KEY_COOKIES = stringPreferencesKey("user_cookies")
        private val KEY_SESSION_REVISION = longPreferencesKey("user_session_revision")
        private val KEY_BLOCKED_ARTIST_IDS = stringSetPreferencesKey("blocked_artist_ids")
    }

    private val json = Json { ignoreUnknownKeys = true }

    // 读取用户信息
    val userProfile: Flow<UserProfile?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_USER_PROFILE]?.let { jsonStr ->
            runCatching { json.decodeFromString<UserProfile>(jsonStr) }
                .onFailure { AppLogger.w(TAG, "用户信息反序列化失败", it) }
                .getOrNull()
        }
    }

    // 读取 Cookie
    val cookies: Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_COOKIES]
    }

    // 读取屏蔽艺人 ID 列表
    val blockedArtistIds: Flow<Set<Long>> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BLOCKED_ARTIST_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    // 屏蔽/取消屏蔽艺人
    suspend fun toggleBlockArtist(artistId: Long) {
        context.userDataStore.edit { prefs ->
            val current = prefs[KEY_BLOCKED_ARTIST_IDS] ?: emptySet()
            val newSet = if (current.contains(artistId.toString())) {
                current - artistId.toString()
            } else {
                current + artistId.toString()
            }
            prefs[KEY_BLOCKED_ARTIST_IDS] = newSet
        }
    }

    // 保存用户信息
    suspend fun saveUserProfile(profile: UserProfile) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_USER_PROFILE] = json.encodeToString(profile)
            prefs[KEY_SESSION_REVISION] = nextRevision(prefs[KEY_SESSION_REVISION])
        }
    }

    // 保存 Cookie
    suspend fun saveCookies(cookies: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_COOKIES] = cookies
            prefs[KEY_SESSION_REVISION] = nextRevision(prefs[KEY_SESSION_REVISION])
        }
    }

    // 清除用户信息与 Cookie（退出登录）
    suspend fun clearUserProfile() {
        context.userDataStore.edit { prefs ->
            prefs.remove(KEY_USER_PROFILE)
            prefs.remove(KEY_COOKIES)
            prefs[KEY_SESSION_REVISION] = nextRevision(prefs[KEY_SESSION_REVISION])
        }
    }

    /**
     * Begin a login in one DataStore transaction. The old profile is kept
     * until the account request succeeds so a failed login can restore the
     * complete previous session.
     */
    override suspend fun beginLogin(cookies: String): LoginSessionAttempt {
        var attempt: LoginSessionAttempt? = null
        context.userDataStore.edit { prefs ->
            val previousRevision = prefs[KEY_SESSION_REVISION] ?: 0L
            val revision = nextRevision(previousRevision)
            attempt = LoginSessionAttempt(
                cookies = cookies,
                previousCookies = prefs[KEY_COOKIES],
                previousProfile = decodeProfile(prefs[KEY_USER_PROFILE]),
                previousRevision = previousRevision,
                revision = revision
            )
            prefs[KEY_COOKIES] = cookies
            prefs[KEY_SESSION_REVISION] = revision
        }
        return checkNotNull(attempt)
    }

    /** Atomically commit the profile only if this login still owns the session. */
    override suspend fun saveUserProfileIfCurrent(
        attempt: LoginSessionAttempt,
        profile: UserProfile
    ): Boolean {
        val encodedProfile = json.encodeToString(profile)
        var saved = false
        context.userDataStore.edit { prefs ->
            if (prefs[KEY_COOKIES] == attempt.cookies &&
                prefs[KEY_SESSION_REVISION] == attempt.revision
            ) {
                prefs[KEY_USER_PROFILE] = encodedProfile
                saved = true
            }
        }
        return saved
    }

    /** Restore only this attempt's session; a newer revision is left intact. */
    override suspend fun rollbackLogin(attempt: LoginSessionAttempt) {
        context.userDataStore.edit { prefs ->
            if (prefs[KEY_COOKIES] != attempt.cookies ||
                prefs[KEY_SESSION_REVISION] != attempt.revision
            ) {
                return@edit
            }

            if (attempt.previousCookies == null) {
                prefs.remove(KEY_COOKIES)
            } else {
                prefs[KEY_COOKIES] = attempt.previousCookies
            }
            if (attempt.previousProfile == null) {
                prefs.remove(KEY_USER_PROFILE)
            } else {
                prefs[KEY_USER_PROFILE] = json.encodeToString(attempt.previousProfile)
            }
            // 不复用本次 attempt 的 revision，防止旧回调在回滚后通过 ABA
            // 重新命中同一个会话标识。
            prefs[KEY_SESSION_REVISION] = nextRevision(prefs[KEY_SESSION_REVISION])
        }
    }

    suspend fun sessionRevisionForUser(uid: Long): Long? {
        val prefs = context.userDataStore.data.first()
        if (decodeProfile(prefs[KEY_USER_PROFILE])?.uid != uid || prefs[KEY_COOKIES].isNullOrBlank()) return null
        return prefs[KEY_SESSION_REVISION] ?: 0L
    }

    // 编辑页只提交仍属于原账号及原资料版本的结果，避免迟到保存覆盖新登录。
    suspend fun saveProfileForSession(uid: Long, revision: Long, updated: UserProfile): Boolean {
        require(updated.uid == uid)
        var saved = false
        context.userDataStore.edit { prefs ->
            val current = decodeProfile(prefs[KEY_USER_PROFILE])
            if (current?.uid == uid && (prefs[KEY_SESSION_REVISION] ?: 0L) == revision &&
                !prefs[KEY_COOKIES].isNullOrBlank()
            ) {
                prefs[KEY_USER_PROFILE] = json.encodeToString(
                    updated.copy(avatarUrl = updated.avatarUrl.ifBlank { current.avatarUrl })
                )
                prefs[KEY_SESSION_REVISION] = nextRevision(revision)
                saved = true
            }
        }
        return saved
    }

    private fun decodeProfile(encoded: String?): UserProfile? = encoded?.let { jsonStr ->
        runCatching { json.decodeFromString<UserProfile>(jsonStr) }
            .onFailure { AppLogger.w(TAG, "用户信息反序列化失败", it) }
            .getOrNull()
    }

    private fun nextRevision(current: Long?): Long = (current ?: 0L) + 1L
}
