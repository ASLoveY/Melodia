package com.lin0721.linmusic.core.player

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One tail request at a time; late responses cannot replace a user's edited queue. */
class SimilarRoamingController(
    private val scope: CoroutineScope,
    private val repository: PlaybackRepository,
    private val queue: PlaybackQueue,
    private val autoPlayEnabled: suspend () -> Boolean,
    private val persistQueue: () -> Unit,
    private val persistMode: (PlayMode) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        const val CONTEXT_ROAMING = "similar_roaming"
        private const val PREFETCH_THRESHOLD_MS = 15000L
    }
    private var roamingJob: Job? = null
    private var generation = 0L
    private var failedSeed: Long? = null
    private var retryAt = 0L
    val isRoaming: Boolean get() = queue.playContext.value == CONTEXT_ROAMING

    fun cancel() { generation++; roamingJob?.cancel(); roamingJob = null }
    fun prepare() {
        cancel()
        if (!isRoaming) queue.takeSnapshot()
        failedSeed = null
        queue.setPlayMode(PlayMode.LIST_LOOP)
        persistMode(PlayMode.LIST_LOOP)
    }
    fun prefetchOnPlay(songId: Long, index: Int) {
        if (isRoaming && index == queue.size - 1) requestTail(songId, index)
    }
    suspend fun onProgressTick(songId: Long, remainingMs: Long) {
        val index = queue.currentIndex.value
        if (songId <= 0 || remainingMs !in 1..PREFETCH_THRESHOLD_MS || index != queue.size - 1 || queue.playMode.value == PlayMode.SINGLE_LOOP) return
        if (!autoPlayEnabled()) return
        if (!isRoaming) { prepare(); queue.setPlayContext(CONTEXT_ROAMING) }
        requestTail(songId, index)
    }
    fun disable() {
        if (!isRoaming) return
        cancel(); failedSeed = null
        queue.setPlayContext(null)
        queue.restoreSnapshot()
        persistMode(queue.playMode.value)
        persistQueue()
    }
    private fun requestTail(songId: Long, index: Int) {
        if (songId <= 0 || roamingJob?.isActive == true || (failedSeed == songId && nowMs() < retryAt)) return
        val snapshot = queue.items.value
        val mode = queue.playMode.value
        val seed = queue.itemAt(index) ?: return
        if (seed.songId != songId || seed.isLocal) return
        val token = ++generation
        roamingJob = scope.launch {
            try {
                val songs = repository.getSimilarSongs(songId).first().getOrThrow()
                if (token != generation || !isRoaming || queue.items.value !== snapshot || queue.playMode.value != mode ||
                    queue.currentIndex.value != index || queue.currentItem()?.stableKey != seed.stableKey) return@launch
                val seen = snapshot.map { it.stableKey }.toHashSet()
                val items = songs.filter { it.id > 0 }.map {
                    QueueItem(it.id, it.name, it.ar.joinToString("/") { artist -> artist.name }, it.al.picUrl)
                }.filter { seen.add(it.stableKey) }
                if (items.isEmpty()) { failedSeed = songId; retryAt = nowMs() + 30000; return@launch }
                queue.appendAfter(index, items)
                failedSeed = null
                persistQueue()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == generation) { failedSeed = songId; retryAt = nowMs() + 30000 }
                AppLogger.w("SimilarRoamingController", "Unable to extend roaming queue", error)
            }
        }
    }
}
