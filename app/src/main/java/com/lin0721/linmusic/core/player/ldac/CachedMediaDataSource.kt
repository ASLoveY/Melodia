package com.lin0721.linmusic.core.player.ldac

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Random-access bridge retaining the same cache, headers and local URI handling as ExoPlayer. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CachedMediaDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri,
    private val cacheKey: String? = null
) : MediaDataSource() {
    private var source: DataSource? = null
    private var cursor = 0L
    private var length = C.LENGTH_UNSET.toLong()
    private var closed = false

    private fun open(position: Long) {
        if (closed) throw IOException("Media source is closed")
        if (source != null && cursor == position) return
        source?.close()
        source = null
        var next: DataSource? = null
        try {
            next = factory.createDataSource()
            val remaining = next.open(DataSpec.Builder().setUri(uri).setPosition(position).setKey(cacheKey).build())
            if (remaining != C.LENGTH_UNSET.toLong()) length = position + remaining
            cursor = position
            source = next
        } catch (failure: Exception) {
            runCatching { next?.close() }
            throw IOException("Unable to open playback source", failure)
        }
    }

    @Synchronized override fun getSize(): Long {
        if (closed) throw IOException("Media source is closed")
        if (length == C.LENGTH_UNSET.toLong() && source == null) open(0)
        return length
    }

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("Media source is closed")
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) throw IOException("Invalid read range")
        if (size == 0) return 0
        if (length != C.LENGTH_UNSET.toLong() && position >= length) return -1
        open(position)
        val read = checkNotNull(source).read(buffer, offset, size)
        if (read > 0) cursor += read
        return read
    }

    @Synchronized override fun close() {
        closed = true
        try { source?.close() } finally { source = null }
    }
}
