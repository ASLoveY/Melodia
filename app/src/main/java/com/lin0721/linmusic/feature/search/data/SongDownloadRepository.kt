package com.lin0721.linmusic.feature.search.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.data.SongUrlItem
import com.lin0721.linmusic.feature.local.domain.LocalMusicRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Foreground, cancellable downloads. CDN requests deliberately carry no account cookies. */
class SongDownloadRepository(
    private val context: Context,
    private val localMusic: LocalMusicRepository,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).build()
) {
    private val mutex = Mutex()
    private val completed = context.getSharedPreferences("song_downloads", Context.MODE_PRIVATE)

    suspend fun download(song: Track, source: SongUrlItem, progress: (Int?) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            completed.getString(song.id.toString(), null)?.let { saved ->
                val uri = Uri.parse(saved)
                val readable = runCatching { context.contentResolver.openInputStream(uri)?.use { true } == true }.getOrDefault(false)
                if (readable) {
                    localMusic.registerDownload(uri, song)
                    return@withContext
                }
            }
            val extension = source.type?.lowercase()?.takeIf { it in setOf("mp3", "flac", "m4a", "wav", "aac", "ogg") } ?: "mp3"
            val fileName = "${song.id}.$extension"
            val temp = File.createTempFile("song-${song.id}-", ".part", context.cacheDir)
            var published: Uri? = null
            try {
                // Upgrade the provider's legacy HTTP CDN URL; never downgrade HTTPS on failure.
                val url = requireNotNull(source.url).toHttpUrl().newBuilder().scheme("https").build()
                val call = client.newCall(Request.Builder().url(url).build())
                coroutineScope {
                    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("下载失败（HTTP ${response.code}）")
                            val body = response.body ?: throw IOException("下载内容为空")
                            val length = body.contentLength().takeIf { it > 0 } ?: source.size
                            var count = 0L
                            var lastPercent = -1
                            val md5 = MessageDigest.getInstance("MD5")
                            body.byteStream().use { input -> temp.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    count += size
                                    if (count > 1024L * 1024 * 1024) throw IOException("歌曲文件过大")
                                    output.write(buffer, 0, size)
                                    md5.update(buffer, 0, size)
                                    val percent = if (length > 0) (count * 100 / length).toInt().coerceAtMost(99) else null
                                    if (percent != lastPercent) { progress(percent); lastPercent = percent ?: -1 }
                                }
                            } }
                            if (count == 0L || (length > 0 && count != length) || (source.size > 0 && count != source.size)) {
                                throw IOException("文件下载不完整，请重试")
                            }
                            val checksum = md5.digest().joinToString("") { "%02x".format(it) }
                            if (!source.md5.isNullOrBlank() && !checksum.equals(source.md5, ignoreCase = true)) {
                                throw IOException("文件校验失败，请重试")
                            }
                        }
                    } finally { cancellation.cancelAndJoin() }
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(temp.absolutePath)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    if (duration <= 0 || (song.dt > 0 && duration + 3000 < song.dt)) throw IOException("下载内容不是完整歌曲")
                } finally { retriever.release() }
                ensureActive()
                // Publishing and registration form one non-cancellable commit; rollback on any failure.
                withContext(NonCancellable) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, "${song.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)}-$fileName")
                            put(MediaStore.Audio.Media.MIME_TYPE, if (extension == "flac") "audio/flac" else if (extension == "mp3") "audio/mpeg" else "audio/$extension")
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Melodia")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        published = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("无法保存歌曲")
                        context.contentResolver.openOutputStream(published!!)?.use { output -> temp.inputStream().use { it.copyTo(output) } }
                            ?: throw IOException("无法写入歌曲")
                        context.contentResolver.update(published!!, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                    } else {
                        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Melodia").apply { mkdirs() }
                        val target = File.createTempFile("${song.id}-", ".$extension", directory)
                        published = Uri.fromFile(target)
                        temp.copyTo(target, overwrite = true)
                    }
                    localMusic.registerDownload(published!!, song)
                    completed.edit().putString(song.id.toString(), published.toString()).apply()
                }
            } catch (error: Throwable) {
                published?.let { uri ->
                    if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
                    else context.contentResolver.delete(uri, null, null)
                }
                throw error
            } finally { temp.delete() }
        }
    }
}
