package com.lin0721.linmusic.feature.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import com.lin0721.linmusic.feature.account.data.ProfileRepository
import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AccountViewModel(
    private val repository: ProfileRepository,
    private val preferences: UserPreferences,
    private val resourceProvider: ResourceProvider
) : ViewModel() {
    private var activeUserId: Long? = null
    private var loadedSession: Pair<Long, Long>? = null
    private val editor = ProfileEditorCoordinator(
        scope = viewModelScope,
        currentUserId = { activeUserId },
        load = ::loadProfile,
        save = ::saveProfile,
        onSaved = ::saveLocalProfile
    )
    val state = editor.state

    init {
        viewModelScope.launch {
            preferences.userProfile.map { it?.uid }.distinctUntilChanged().collect { uid ->
                activeUserId = uid
                loadedSession = null
                editor.loadProfile()
            }
        }
    }

    fun refresh() = editor.loadProfile()
    fun editNickname(value: String) = editor.editNickname(value)
    fun editSignature(value: String) = editor.editSignature(value)
    fun save() = editor.saveProfile()
    fun leave() {
        editor.reset()
        loadedSession = null
    }

    private suspend fun loadProfile(uid: Long): Result<UserProfileDetails> {
        val revision = preferences.sessionRevisionForUser(uid) ?: return sessionChanged()
        val result = repository.getProfile(uid).firstOrNull()
            ?: Result.failure(IllegalStateException("未能读取资料，请重试"))
        if (preferences.sessionRevisionForUser(uid) != revision) return sessionChanged()
        loadedSession = uid to revision
        return result.userFacing()
    }

    private suspend fun saveProfile(profile: UserProfileDetails, nickname: String, signature: String): Result<Unit> {
        val session = loadedSession ?: return sessionChanged()
        if (session.first != profile.userId || preferences.sessionRevisionForUser(session.first) != session.second) {
            return sessionChanged()
        }
        return (repository.updateProfile(profile, nickname, signature).firstOrNull()
            ?: Result.failure(IllegalStateException("保存未完成，请重试"))).userFacing()
    }

    private suspend fun saveLocalProfile(profile: UserProfileDetails): Boolean {
        val session = loadedSession ?: return false
        if (session.first != profile.userId) return false
        val saved = preferences.saveProfileForSession(
            session.first,
            session.second,
            UserProfile(profile.userId, profile.nickname, profile.avatarUrl, profile.signature)
        )
        if (saved) {
            loadedSession = preferences.sessionRevisionForUser(profile.userId)?.let { profile.userId to it }
        }
        return saved
    }

    private fun <T> sessionChanged(): Result<T> =
        Result.failure(IllegalStateException("账号或资料已变化，请刷新后重试"))

    private fun <T> Result<T>.userFacing(): Result<T> = fold(
        onSuccess = { Result.success(it) },
        onFailure = {
            Result.failure(IllegalStateException(
                if (it is AppError) it.toUserMessage(resourceProvider) else it.message ?: "操作失败，请重试"
            ))
        }
    )
}
