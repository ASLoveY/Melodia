package com.lin0721.linmusic.feature.search.ui

import com.lin0721.linmusic.feature.search.data.SearchRepository
import com.lin0721.linmusic.feature.search.domain.SearchPageResult
import com.lin0721.linmusic.feature.search.domain.SearchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Coordinates one active cloud-search request and the per-type result state.
 *
 * A result is only allowed to update state when its generation is still current. This matters
 * even though the HTTP flow is normally cancellable: a repository implementation may finish a
 * response at the same time that the UI starts another query.
 */
internal class SearchResultsCoordinator(
    private val repository: SearchRepository,
    private val scope: CoroutineScope,
    private val errorMessage: (Throwable) -> String,
    private val onToast: suspend (String) -> Unit
) {

    private val mutableResultsByType: Map<SearchType, MutableStateFlow<SearchResultsUiState>> =
        SearchType.entries.associateWith { MutableStateFlow(SearchResultsUiState.Idle) }

    val resultsByType: Map<SearchType, StateFlow<SearchResultsUiState>> =
        mutableResultsByType.mapValues { (_, state) -> state.asStateFlow() }

    // offset 按接口实际返回条数推进；每次关键词变更时全部失效。
    private val offsetByType = mutableMapOf<SearchType, Int>()
    private var activeJob: Job? = null
    private var activeRequest: ActiveRequest? = null
    private var requestGeneration = 0L

    private data class ActiveRequest(
        val generation: Long,
        val type: SearchType,
        val isLoadMore: Boolean,
        val previousState: SearchResultsUiState,
        val previousOffset: Int?
    )

    /** Cancel the active request and invalidate every cached category result. */
    fun clear() {
        cancelActiveRequest(restorePreviousState = false)
        offsetByType.clear()
        mutableResultsByType.values.forEach { it.value = SearchResultsUiState.Idle }
    }

    /** Start an initial search or load the next page for [type]. */
    fun search(keyword: String, type: SearchType, isLoadMore: Boolean) {
        if (keyword.isBlank()) return

        val stateFlow = mutableResultsByType.getValue(type)
        val current = stateFlow.value
        val loadMoreState = current as? SearchResultsUiState.Success
        if (isLoadMore && (loadMoreState == null || loadMoreState.isLoadingMore || !loadMoreState.hasMore)) {
            return
        }

        // Invalidate before cancelling. A cancelled request may still run its finally block or
        // deliver a late repository result; the generation check keeps both from touching state.
        cancelActiveRequest(restorePreviousState = true)
        val generation = ++requestGeneration

        // cancelActiveRequest may have restored the previous request's state. Take the snapshot
        // now, rather than using the pre-cancel Loading state as the rollback value for this
        // replacement request.
        val previousState = stateFlow.value
        val previousOffset = offsetByType[type]
        val previousLoadMoreState = previousState as? SearchResultsUiState.Success
        if (isLoadMore && (previousLoadMoreState == null || previousLoadMoreState.isLoadingMore || !previousLoadMoreState.hasMore)) {
            return
        }

        val offset = if (isLoadMore) offsetByType[type] ?: 0 else 0
        if (isLoadMore) {
            stateFlow.value = previousLoadMoreState!!.copy(isLoadingMore = true)
        } else {
            offsetByType[type] = 0
            stateFlow.value = SearchResultsUiState.Loading
        }

        activeRequest = ActiveRequest(generation, type, isLoadMore, previousState, previousOffset)
        activeJob = scope.launch {
            var handled = false
            try {
                val result = repository.search(keyword, type, offset = offset).firstOrNull()
                if (!isCurrent(generation)) return@launch

                if (result == null) {
                    // A Repository flow is expected to emit one Result. Avoid leaving the UI in
                    // a spinner if an implementation completes without emitting anything.
                    if (isLoadMore) {
                        (stateFlow.value as? SearchResultsUiState.Success)?.let {
                            stateFlow.value = it.copy(isLoadingMore = false)
                        }
                    } else {
                        stateFlow.value = SearchResultsUiState.Empty
                    }
                } else if (result.isSuccess) {
                    applySuccess(generation, type, offset, isLoadMore, result.getOrThrow())
                } else {
                    applyFailure(generation, type, isLoadMore, result.exceptionOrNull()!!)
                }
                handled = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Repository implementations normally map exceptions to Result.failure, but a
                // custom implementation should not be able to strand the UI in Loading either.
                if (isCurrent(generation)) {
                    applyFailure(generation, type, isLoadMore, error)
                    handled = true
                }
            } finally {
                if (!handled && isCurrent(generation)) {
                    // This branch covers a caller-side cancellation that did not go through
                    // clear()/search(). Restore a retryable state instead of a permanent spinner.
                    if (isLoadMore) {
                        (stateFlow.value as? SearchResultsUiState.Success)?.let {
                            stateFlow.value = it.copy(isLoadingMore = false)
                        }
                    } else {
                        stateFlow.value = SearchResultsUiState.Idle
                    }
                }
                if (isCurrent(generation)) {
                    activeRequest = null
                    activeJob = null
                }
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean = requestGeneration == generation

    private fun cancelActiveRequest(restorePreviousState: Boolean) {
        // Increment first so that the cancelled coroutine cannot restore or publish after the
        // replacement request has started.
        requestGeneration += 1
        val request = activeRequest
        activeRequest = null
        activeJob?.cancel()
        activeJob = null

        if (restorePreviousState && request != null) {
            val stateFlow = mutableResultsByType.getValue(request.type)
            val current = stateFlow.value
            val wasLoading = if (request.isLoadMore) {
                (current as? SearchResultsUiState.Success)?.isLoadingMore == true
            } else {
                current === SearchResultsUiState.Loading
            }
            if (wasLoading) {
                stateFlow.value = request.previousState
                if (request.previousOffset == null) {
                    offsetByType.remove(request.type)
                } else {
                    offsetByType[request.type] = request.previousOffset
                }
            }
        }
    }

    private fun applySuccess(
        generation: Long,
        type: SearchType,
        offset: Int,
        isLoadMore: Boolean,
        page: SearchPageResult
    ) {
        if (!isCurrent(generation)) return

        offsetByType[type] = offset + page.rawFetchedCount
        val stateFlow = mutableResultsByType.getValue(type)
        val mergedItems = if (isLoadMore) {
            (stateFlow.value as? SearchResultsUiState.Success)?.items.orEmpty() + page.items
        } else {
            page.items
        }

        stateFlow.value = if (mergedItems.isEmpty()) {
            SearchResultsUiState.Empty
        } else {
            SearchResultsUiState.Success(
                items = mergedItems,
                totalCount = page.totalCount,
                hasMore = page.hasMore,
                isLoadingMore = false
            )
        }
    }

    private suspend fun applyFailure(
        generation: Long,
        type: SearchType,
        isLoadMore: Boolean,
        error: Throwable
    ) {
        if (!isCurrent(generation)) return

        val stateFlow = mutableResultsByType.getValue(type)
        val current = stateFlow.value
        if (isLoadMore && current is SearchResultsUiState.Success) {
            // Keep the already visible page and leave hasMore intact so the same action can retry.
            stateFlow.value = current.copy(isLoadingMore = false)
            onToast(errorMessage(error))
        } else {
            stateFlow.value = SearchResultsUiState.Error(errorMessage(error))
        }
    }
}
