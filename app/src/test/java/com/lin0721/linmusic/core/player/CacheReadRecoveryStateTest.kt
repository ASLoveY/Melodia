package com.lin0721.linmusic.core.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class CacheReadRecoveryStateTest {
    @Test
    fun `recovery uses only bytes returned to caller and is single use`() {
        val state = CacheReadRecoveryState()
        state.beginRequest()
        state.recordBytesRead(7)
        state.recordBytesRead(5)

        assertEquals(12L, state.claimRecoveryOffset())
        assertNull(state.claimRecoveryOffset())
    }

    @Test
    fun `beginning a new request resets offset and retry budget`() {
        val state = CacheReadRecoveryState()
        state.beginRequest()
        state.recordBytesRead(12)
        state.claimRecoveryOffset()

        state.beginRequest()

        assertEquals(0L, state.bytesRead)
        assertEquals(0L, state.claimRecoveryOffset())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative read counts are rejected`() {
        val state = CacheReadRecoveryState()
        state.recordBytesRead(-1)
    }

    @Test
    fun `only missing local cache files are recoverable`() {
        val missingFile = FileDataSource.FileDataSourceException(
            IOException("missing"),
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        val wrappedMissingFile = IOException("wrapped", missingFile)
        val networkFailure = IOException("network")

        assertTrue(isMissingCacheFile(missingFile))
        assertTrue(isMissingCacheFile(wrappedMissingFile))
        assertFalse(isMissingCacheFile(networkFailure))
    }
}
