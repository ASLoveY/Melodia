package com.lin0721.linmusic.core.player

/**
 * Tracks the portion of a data request that has already been returned to the caller.
 *
 * A cache file can disappear after a data source has selected it. If that happens, the request can
 * be reopened at this offset against the upstream source. Recovery is intentionally single-use so
 * a persistent I/O error cannot turn into an unbounded retry loop.
 */
internal class CacheReadRecoveryState {
    var bytesRead: Long = 0L
        private set

    private var recoveryAttempted = false

    fun beginRequest() {
        bytesRead = 0L
        recoveryAttempted = false
    }

    fun recordBytesRead(count: Int) {
        require(count >= 0) { "count must be non-negative" }
        bytesRead = Math.addExact(bytesRead, count.toLong())
    }

    /** Returns the caller-visible offset, or null after the one recovery has been claimed. */
    fun claimRecoveryOffset(): Long? {
        if (recoveryAttempted) return null
        recoveryAttempted = true
        return bytesRead
    }

    fun reset() {
        bytesRead = 0L
        recoveryAttempted = false
    }
}
