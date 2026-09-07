package com.lin0721.linmusic.core.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Reopens a cache-backed request once when a cache span disappears before it can be read.
 *
 * Media3's CacheDataSource deliberately remembers cache read failures and bypasses the cache on a
 * subsequent open when FLAG_IGNORE_CACHE_ON_ERROR is enabled. This wrapper turns that second open
 * into a local, bounded recovery while preserving the original request position for read errors.
 */
@OptIn(UnstableApi::class)
internal class CacheReadRecoveryDataSource(
    private val delegate: DataSource,
    private val recoveryState: CacheReadRecoveryState = CacheReadRecoveryState(),
) : DataSource {
    private var originalDataSpec: DataSpec? = null
    private var needsClose = false

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        originalDataSpec = dataSpec
        recoveryState.beginRequest()
        needsClose = true

        return try {
            delegate.open(dataSpec)
        } catch (error: IOException) {
            claimRecoveryOffset(error) ?: throw error
            closeDelegateAfterFailure(error)
            // CacheDataSource keeps its seen-cache-error bit across close(), so the second open
            // bypasses the stale cache span and uses the upstream data source.
            try {
                delegate.open(dataSpec)
            } catch (retryError: IOException) {
                if (retryError !== error) retryError.addSuppressed(error)
                throw retryError
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val request = checkNotNull(originalDataSpec) { "DataSource is not open" }
        return try {
            readAndTrack(buffer, offset, length)
        } catch (error: IOException) {
            val recoveryOffset = claimRecoveryOffset(error) ?: throw error
            if (request.length != C.LENGTH_UNSET.toLong() && recoveryOffset >= request.length) {
                throw error
            }

            closeDelegateAfterFailure(error)
            val recoverySpec = request.subrange(recoveryOffset)
            try {
                delegate.open(recoverySpec)
                readAndTrack(buffer, offset, length)
            } catch (retryError: IOException) {
                if (retryError !== error) retryError.addSuppressed(error)
                throw retryError
            }
        }
    }

    private fun readAndTrack(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = delegate.read(buffer, offset, length)
        if (bytesRead > 0) {
            recoveryState.recordBytesRead(bytesRead)
        }
        return bytesRead
    }

    private fun claimRecoveryOffset(error: IOException): Long? {
        if (!isMissingCacheFile(error)) return null
        return recoveryState.claimRecoveryOffset()
    }

    private fun closeDelegateAfterFailure(error: IOException) {
        try {
            delegate.close()
        } catch (closeError: IOException) {
            if (closeError !== error) error.addSuppressed(closeError)
        }
    }

    override fun getUri(): Uri? = delegate.getUri()

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.getResponseHeaders()

    override fun close() {
        if (!needsClose) return
        try {
            delegate.close()
        } finally {
            needsClose = false
            originalDataSpec = null
            recoveryState.reset()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun isMissingCacheFile(error: IOException): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is FileDataSource.FileDataSourceException &&
            cause.reason == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}
