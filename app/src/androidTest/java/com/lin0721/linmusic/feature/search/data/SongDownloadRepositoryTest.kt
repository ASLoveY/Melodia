package com.lin0721.linmusic.feature.search.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.data.SongUrlItem
import com.lin0721.linmusic.feature.local.data.LocalMusicRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SongDownloadRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val local = LocalMusicRepositoryImpl(context)
    private fun wav(): ByteArray {
        val dataSize = 16000 * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
    private fun repository(bytes: ByteArray, requested: () -> Unit = {}) = SongDownloadRepository(context, local,
        OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Cookie"))
            assertTrue(chain.request().url.isHttps)
            requested()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build())

    @Test fun downloadsVerifiedAudioRegistersMetadataAndReusesTheFileAfterLibraryRemoval() = runBlocking {
        val song = Track(System.nanoTime(), "下载测试", dt = 1000)
        val bytes = wav()
        var requests = 0
        val repository = repository(bytes) { requests++ }
        val source = SongUrlItem(id = song.id, url = "http://example.com/audio", size = bytes.size.toLong(), type = "wav")
        val before = local.tracks.first().map { it.id }.toSet()
        try {
            repository.download(song, source) {}
            val added = local.tracks.first().single { it.title == song.name && it.id !in before }
            assertTrue(added.durationMs >= 1000)
            context.contentResolver.openInputStream(android.net.Uri.parse(added.uri))!!.use { assertArrayEquals(bytes, it.readBytes()) }
            local.remove(added.id)
            repository.download(song, source) {}
            assertEquals(1, requests)
            assertTrue(local.tracks.first().any { it.id == added.id })
        } finally {
            local.tracks.first().filter { it.id !in before }.forEach {
                local.remove(it.id)
                context.contentResolver.delete(android.net.Uri.parse(it.uri), null, null)
            }
        }
    }

    @Test fun corruptOrIncompleteDownloadsNeverEnterTheLibrary() = runBlocking {
        val before = local.tracks.first()
        val song = Track(System.nanoTime(), "失败测试", dt = 1000)
        val bytes = wav()
        val source = SongUrlItem(id = song.id, url = "https://example.com/audio", size = bytes.size.toLong(), type = "wav")
        assertTrue(runCatching { repository(bytes).download(song, source.copy(size = source.size + 1)) {} }.isFailure)
        assertTrue(runCatching { repository(bytes).download(song, source.copy(md5 = "invalid")) {} }.isFailure)
        assertTrue(runCatching { repository("not audio".toByteArray()).download(song, source.copy(size = 0)) {} }.isFailure)
        assertEquals(before, local.tracks.first())
    }

    @Test fun cancellationRemovesTemporaryBytesAndDoesNotPublish() = runBlocking {
        val before = local.tracks.first()
        val tempBefore = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val song = Track(System.nanoTime(), "取消测试", dt = 1000)
        try {
            repository(wav()).download(song, SongUrlItem(id = song.id, url = "https://example.com/audio", type = "wav")) {
                throw CancellationException("cancel download")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(before, local.tracks.first())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("song-${song.id}-") && it.name !in tempBefore })
    }
}
