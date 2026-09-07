package com.lin0721.linmusic.feature.account.ui

import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProfileEditorState {
    data object Loading : ProfileEditorState
    data object SignedOut : ProfileEditorState
    data class Error(val message: String) : ProfileEditorState

    data class Ready(
        val profile: UserProfileDetails,
        val nickname: String,
        val signature: String,
        val isSaving: Boolean = false,
        val error: String? = null,
        val saved: Boolean = false
    ) : ProfileEditorState {
        val hasChanges: Boolean
            get() = nickname != profile.nickname || signature != profile.signature
    }
}

/**
 * Coordinates loading and editing the editable portion of a user profile.
 *
 * The original profile remains the baseline for every save. Only nickname and signature are
 * replaced in the copied model, so optional location, gender, birthday and avatar fields survive
 * a save unchanged. Account persistence and cache invalidation are delegated to [onSaved].
 */
class ProfileEditorCoordinator(
    private val scope: CoroutineScope,
    private val currentUserId: () -> Long?,
    private val load: suspend (Long) -> Result<UserProfileDetails>,
    private val save: suspend (UserProfileDetails, String, String) -> Result<Unit>,
    private val onSaved: suspend (UserProfileDetails) -> Boolean
) {

    private val _state = MutableStateFlow<ProfileEditorState>(ProfileEditorState.Loading)
    val state: StateFlow<ProfileEditorState> = _state.asStateFlow()
    val uiState: StateFlow<ProfileEditorState> = state

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var generation = 0L

    fun loadProfile() {
        invalidateRequests()
        val uid = currentUserId()?.takeIf { it > 0L }
        if (uid == null) {
            _state.value = ProfileEditorState.SignedOut
            return
        }

        val requestGeneration = generation
        _state.value = ProfileEditorState.Loading
        loadJob = scope.launch {
            val result = try {
                load(uid)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }

            if (!isCurrent(requestGeneration)) return@launch
            if (result.isFailure) {
                _state.value = ProfileEditorState.Error(errorMessage(result.exceptionOrNull()))
                loadJob = null
                return@launch
            }

            val profile = result.getOrThrow()
            if (profile.userId != uid || profile.userId <= 0L) {
                _state.value = ProfileEditorState.Error("账号资料与当前账号不一致，请刷新")
            } else {
                _state.value = ProfileEditorState.Ready(
                    profile = profile,
                    nickname = profile.nickname,
                    signature = profile.signature,
                    isSaving = false,
                    error = null,
                    saved = false
                )
            }
            loadJob = null
        }
    }

    fun editNickname(value: String) {
        val ready = _state.value as? ProfileEditorState.Ready ?: return
        if (ready.isSaving) return
        _state.value = ready.copy(nickname = value, error = null, saved = false)
    }

    fun editSignature(value: String) {
        val ready = _state.value as? ProfileEditorState.Ready ?: return
        if (ready.isSaving) return
        _state.value = ready.copy(signature = value, error = null, saved = false)
    }

    fun saveProfile() {
        val ready = _state.value as? ProfileEditorState.Ready ?: return
        if (ready.isSaving) return

        val nickname = ready.nickname.trim()
        if (nickname.isEmpty()) {
            _state.value = ready.copy(isSaving = false, saved = false, error = "昵称不能为空")
            return
        }
        if (!ready.profile.isEditable) {
            _state.value = ready.copy(isSaving = false, saved = false, error = incompleteProfileMessage)
            return
        }
        if (!ready.hasChanges) return

        val uid = currentUserId()?.takeIf { it > 0L }
        if (uid == null) {
            _state.value = ProfileEditorState.SignedOut
            return
        }
        if (uid != ready.profile.userId) {
            _state.value = ready.copy(isSaving = false, saved = false, error = sessionChangedMessage)
            return
        }
        val updated = ready.profile.copy(
            nickname = nickname,
            signature = ready.signature
        )
        val requestGeneration = ++generation
        saveJob?.cancel()
        _state.value = ready.copy(
            nickname = nickname,
            isSaving = true,
            error = null,
            saved = false
        )

        saveJob = scope.launch {
            val result = try {
                save(updated, nickname, ready.signature)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }

            if (!isCurrent(requestGeneration) || currentUserId() != uid) return@launch
            if (result.isFailure) {
                _state.value = ProfileEditorState.Ready(
                    profile = ready.profile,
                    nickname = nickname,
                    signature = ready.signature,
                    isSaving = false,
                    error = errorMessage(result.exceptionOrNull()),
                    saved = false
                )
                saveJob = null
                return@launch
            }

            val persisted = try {
                onSaved(updated)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }

            if (!isCurrent(requestGeneration) || currentUserId() != uid) return@launch
            if (!persisted) {
                _state.value = ProfileEditorState.Ready(
                    profile = ready.profile,
                    nickname = nickname,
                    signature = ready.signature,
                    isSaving = false,
                    error = sessionChangedMessage,
                    saved = false
                )
            } else {
                _state.value = ProfileEditorState.Ready(
                    profile = updated,
                    nickname = nickname,
                    signature = ready.signature,
                    isSaving = false,
                    error = null,
                    saved = true
                )
            }
            saveJob = null
        }
    }

    /** Invalidate callbacks when the account changes or the editor leaves the account scope. */
    fun onAccountChanged() {
        reset()
    }

    fun reset() {
        invalidateRequests()
        _state.value = if (currentUserId()?.takeIf { it > 0L } == null) {
            ProfileEditorState.SignedOut
        } else {
            ProfileEditorState.Loading
        }
    }

    private fun invalidateRequests() {
        generation += 1L
        loadJob?.cancel()
        saveJob?.cancel()
        loadJob = null
        saveJob = null
    }

    private fun isCurrent(requestGeneration: Long): Boolean = generation == requestGeneration

    private fun errorMessage(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() } ?: "保存失败，请重试"

    private companion object {
        const val sessionChangedMessage = "账号状态已变化，请刷新后重试"
        const val incompleteProfileMessage = "资料不完整，暂时无法保存"
    }
}
