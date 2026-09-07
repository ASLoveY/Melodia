package com.lin0721.linmusic.feature.local.data

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalAudioDirectoryScannerTest {

    @Test
    fun `递归扫描多层目录并按音频MIME或扩展名导入`() = runTest {
        val tree = mapOf(
            "root" to listOf(
                directory("albums"),
                file("root-song", "root-song.MP3", "application/octet-stream"),
                file("cover", "cover.jpg", "image/jpeg"),
                file("audio-mime", "recording.bin", "audio/x-custom")
            ),
            "albums" to listOf(
                directory("artist"),
                file("ignore", "notes.txt", "text/plain")
            ),
            "artist" to listOf(
                file("nested", "nested.flac", "application/octet-stream")
            )
        )
        val imported = mutableListOf<String>()

        val summary = scanAudioDocuments(
            rootId = "root",
            listChildren = { tree[it].orEmpty() },
            onAudio = { imported += it.id }
        )

        assertEquals(3, summary.audioFiles)
        assertEquals(0, summary.failedDirectories)
        assertEquals(listOf("root-song", "audio-mime", "nested"), imported)
    }

    @Test
    fun `重复文档和目录循环只访问一次`() = runTest {
        val tree = mapOf(
            "root" to listOf(directory("one"), directory("one"), file("song", "song.mp3")),
            "one" to listOf(directory("root"), file("song", "song.mp3"))
        )
        val imported = mutableListOf<String>()

        val summary = scanAudioDocuments(
            rootId = "root",
            listChildren = { tree[it].orEmpty() },
            onAudio = { imported += it.id }
        )

        assertEquals(1, summary.audioFiles)
        assertEquals(listOf("song"), imported)
    }

    @Test
    fun `读取子目录失败时计数并继续扫描其他目录`() = runTest {
        val tree = mapOf(
            "root" to listOf(directory("broken"), directory("good")),
            "good" to listOf(file("song", "song.ogg"))
        )
        val imported = mutableListOf<String>()

        val summary = scanAudioDocuments(
            rootId = "root",
            listChildren = { id ->
                if (id == "broken") error("provider unavailable")
                tree[id].orEmpty()
            },
            onAudio = { imported += it.id }
        )

        assertEquals(1, summary.audioFiles)
        assertEquals(1, summary.failedDirectories)
        assertEquals(listOf("song"), imported)
    }

    @Test
    fun `根目录读取失败也计入失败目录`() = runTest {
        val summary = scanAudioDocuments(
            rootId = "root",
            listChildren = { error("root unavailable") },
            onAudio = { error("must not be called") }
        )

        assertEquals(0, summary.audioFiles)
        assertEquals(1, summary.failedDirectories)
    }

    @Test
    fun `取消异常不会被当成目录读取失败吞掉`() = runTest {
        try {
            scanAudioDocuments(
                rootId = "root",
                listChildren = { throw CancellationException("cancelled") },
                onAudio = {}
            )
            fail("取消异常应传递给调用方")
        } catch (cancelled: CancellationException) {
            assertTrue(cancelled.message == "cancelled")
        }
    }

    @Test
    fun `音频回调异常会传递给调用方`() = runTest {
        val failure = IllegalStateException("catalog write failed")

        try {
            scanAudioDocuments(
                rootId = "root",
                listChildren = { listOf(file("song", "song.mp3")) },
                onAudio = { throw failure }
            )
            fail("音频回调异常应传递给调用方")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    private fun directory(id: String) =
        LocalDocument(id = id, name = id, mimeType = "vnd.android.document/directory")

    private fun file(id: String, name: String, mimeType: String = "audio/mpeg") =
        LocalDocument(id = id, name = name, mimeType = mimeType)
}
