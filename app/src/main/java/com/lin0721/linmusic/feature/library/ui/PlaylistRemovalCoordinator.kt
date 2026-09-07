package com.lin0721.linmusic.feature.library.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The minimum library metadata needed to authorize a playlist removal action. */
data class PlaylistRemovalTarget(
    val id: Long,
    val title: String,
    val ownerId: Long,
    val isLikedSongs: Boolean = false
)

enum class PlaylistRemovalKind {
    DELETE,
    UNSUBSCRIBE
}

sealed interface PlaylistRemovalState {
    data object Hidden : PlaylistRemovalState

    data class Confirm(
        val target: PlaylistRemovalTarget,
        val kind: PlaylistRemovalKind,
        val isSubmitting: Boolean = false,
        val error: String? = null
    ) : PlaylistRemovalState
}

/**
 * Owns authorization and the one-shot lifecycle of removing a playlist from the library.
 *
 * The coordinator deliberately does not mutate a library snapshot. The caller removes the
 * confirmed id through [onRemoved], so a later refresh remains the source of truth.
 */
class PlaylistRemovalCoordinator(
    private val scope: CoroutineScope,
    private val currentUserId: () -> Long?,
    private val remove: suspend (Long, PlaylistRemovalKind) -> Result<Unit>,
    private val onRemoved: (Long) -> Unit
) {

    private val _state = MutableStateFlow<PlaylistRemovalState>(PlaylistRemovalState.Hidden)
    val state: StateFlow<PlaylistRemovalState> = _state.asStateFlow()
    val uiState: StateFlow<PlaylistRemovalState> = state

    private data class PendingRequest(
        val target: PlaylistRemovalTarget,
        val kind: PlaylistRemovalKind,
        val accountId: Long,
        val generation: Long
    )

    private var pending: PendingRequest? = null
    private var activeJob: Job? = null
    private var generation = 0L

    /** Show confirmation only for an actionable playlist in the currently logged-in account. */
    fun request(target: PlaylistRemovalTarget) {
        invalidateActiveRequest()

        val accountId = currentUserId()?.takeIf { it > 0L }
        if (accountId == null || target.id <= 0L || target.ownerId <= 0L || target.isLikedSongs) {
            _state.value = PlaylistRemovalState.Hidden
            return
        }

        val kind = if (target.ownerId == accountId) {
            PlaylistRemovalKind.DELETE
        } else {
            PlaylistRemovalKind.UNSUBSCRIBE
        }
        val requestGeneration = generation
        pending = PendingRequest(target, kind, accountId, requestGeneration)
        _state.value = PlaylistRemovalState.Confirm(target, kind)
    }

    /** Cancel confirmation or an in-flight removal without scheduling another request. */
    fun dismiss() {
        invalidateActiveRequest()
        _state.value = PlaylistRemovalState.Hidden
    }

    /** Reset all pending work when the account changes or the screen leaves the library. */
    fun reset() {
        dismiss()
    }

    /** Execute the currently confirmed action. Repeated calls while submitting are ignored. */
    fun confirm() {
        val visible = _state.value as? PlaylistRemovalState.Confirm ?: return
        if (visible.isSubmitting) return

        val request = pending
        val accountId = currentUserId()?.takeIf { it > 0L }
        if (request == null || accountId == null || accountId != request.accountId ||
            request.target.id <= 0L || request.target.ownerId <= 0L || request.target.isLikedSongs
        ) {
            reset()
            return
        }

        val requestGeneration = ++generation
        pending = request.copy(generation = requestGeneration)
        _state.value = visible.copy(isSubmitting = true, error = null)
        activeJob = scope.launch {
            val result = try {
                remove(request.target.id, request.kind)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }

            if (!isCurrent(requestGeneration, request.target.id, request.accountId)) return@launch

            // A reset() is the normal account-switch path. Recheck here as a second guard for an
            // account change that happened while the repository call was in flight.
            if (currentUserId() != request.accountId) {
                pending = null
                _state.value = PlaylistRemovalState.Hidden
                return@launch
            }

            if (result.isSuccess) {
                onRemoved(request.target.id)
                if (!isCurrent(requestGeneration, request.target.id, request.accountId)) return@launch
                pending = null
                _state.value = PlaylistRemovalState.Hidden
            } else {
                _state.value = PlaylistRemovalState.Confirm(
                    target = request.target,
                    kind = request.kind,
                    isSubmitting = false,
                    error = removalErrorMessage(result.exceptionOrNull())
                )
            }
            activeJob = null
        }
    }

    private fun invalidateActiveRequest() {
        generation += 1L
        activeJob?.cancel()
        activeJob = null
        pending = null
    }

    private fun isCurrent(requestGeneration: Long, targetId: Long, accountId: Long): Boolean {
        val request = pending
        return generation == requestGeneration &&
            request?.generation == requestGeneration &&
            request.target.id == targetId &&
            request.accountId == accountId
    }

    private fun removalErrorMessage(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
}
