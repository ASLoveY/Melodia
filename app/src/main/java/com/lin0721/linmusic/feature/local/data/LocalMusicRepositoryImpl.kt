package com.lin0721.linmusic.feature.local.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin0721.linmusic.feature.local.domain.LocalImportResult
import com.lin0721.linmusic.feature.local.domain.LocalImportProgress
import com.lin0721.linmusic.feature.local.domain.LocalMusicCatalog
import com.lin0721.linmusic.feature.local.domain.LocalMusicRepository
import com.lin0721.linmusic.feature.local.domain.LocalTrack
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DATASTORE_NAME = "local_music_prefs"
private const val UNKNOWN_ARTIST = "未知歌手"
private const val UNKNOWN_ALBUM = "未知专辑"
private const val UNKNOWN_TITLE = "未知歌曲"

private val Context.localMusicDataStore by preferencesDataStore(name = DATASTORE_NAME)

/** A document has the same identity whether selected alone or through a directory grant. */
internal fun localDocumentKey(uri: Uri): String = runCatching {
    if (uri.scheme == "content" && "document" in uri.pathSegments) {
        DocumentsContract.buildDocumentUri(requireNotNull(uri.authority), DocumentsContract.getDocumentId(uri)).toString()
    } else uri.toString()
}.getOrDefault(uri.toString())

/**
 * URI-backed local music catalog. The repository never copies, renames, or deletes source files.
 */
class LocalMusicRepositoryImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.currentTimeMillis() }
) : LocalMusicRepository {

    private companion object {
        val KEY_TRACKS = stringPreferencesKey("tracks_json")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val catalogMutex = Mutex()

    override val tracks: Flow<List<LocalTrack>> = context.localMusicDataStore.data.map { prefs ->
        decode(prefs[KEY_TRACKS])
    }.flowOn(ioDispatcher)

    override suspend fun importUris(uris: List<Uri>): LocalImportResult =
        withContext(ioDispatcher) {
            catalogMutex.withLock {
                val batch = ImportBatch(tracks.first())
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        batch.add(uri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        batch.failed++
                    }
                }
                batch.commit()
                batch.result()
            }
        }

    override suspend fun importDirectory(
        uri: Uri,
        minDurationMs: Long,
        onProgress: (LocalImportProgress) -> Unit
    ): LocalImportResult = withContext(ioDispatcher) {
        require(minDurationMs >= 0L)
        require(DocumentsContract.isTreeUri(uri)) { "请选择音乐所在的目录" }
        catalogMutex.withLock {
            // One persisted tree grant covers all descendants; individual child grants are
            // neither necessary nor issued by ACTION_OPEN_DOCUMENT_TREE.
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val rootId = DocumentsContract.getTreeDocumentId(uri)
            val rootChildren = listChildren(uri, rootId)
            val batch = ImportBatch(tracks.first())
            var scanned = 0
            onProgress(LocalImportProgress(0, 0, 0))
            val summary = scanAudioDocuments(
                rootId = rootId,
                listChildren = { if (it == rootId) rootChildren else listChildren(uri, it) },
                onAudio = { document ->
                    currentCoroutineContext().ensureActive()
                    scanned++
                    try {
                        batch.add(
                            DocumentsContract.buildDocumentUriUsingTree(uri, document.id),
                            document.name,
                            minDurationMs
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        batch.failed++
                    }
                    onProgress(LocalImportProgress(scanned, batch.imported, batch.skippedShort))
                }
            )
            batch.failed += summary.failedDirectories
            currentCoroutineContext().ensureActive()
            onProgress(LocalImportProgress(scanned, batch.imported, batch.skippedShort, isSaving = true))
            batch.commit()
            batch.result()
        }
    }

    private suspend fun listChildren(treeUri: Uri, parentId: String): List<LocalDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("无法读取目录")
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(projection[0])
            val nameIndex = it.getColumnIndexOrThrow(projection[1])
            val mimeIndex = it.getColumnIndexOrThrow(projection[2])
            buildList {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    add(LocalDocument(it.getString(idIndex), it.getString(nameIndex).orEmpty(), it.getString(mimeIndex).orEmpty()))
                }
            }
        }
    }

    override suspend fun remove(id: String) {
        withContext(ioDispatcher) {
            catalogMutex.withLock {
                context.localMusicDataStore.edit { prefs ->
                    val updated = LocalMusicCatalog.remove(decode(prefs[KEY_TRACKS]), id)
                    if (updated.isEmpty()) {
                        prefs.remove(KEY_TRACKS)
                    } else {
                        prefs[KEY_TRACKS] = json.encodeToString(updated)
                    }
                }
            }
        }
    }

    /** Stage a complete import under catalogMutex, so cancellation never commits a partial scan. */
    private inner class ImportBatch(existing: List<LocalTrack>) {
        private val staged = existing.toMutableList()
        private val indices = HashMap<String, Int>().apply {
            existing.forEachIndexed { index, track -> putIfAbsent(localDocumentKey(track.uri.toUri()), index) }
        }
        private var changed = false
        var imported = 0
            private set
        var duplicates = 0
            private set
        var skippedShort = 0
            private set
        var failed = 0

        fun add(uri: Uri, displayName: String? = null, minDurationMs: Long? = null) {
            verifyReadable(uri)
            val key = localDocumentKey(uri)
            val index = indices[key]
            if (index != null) {
                // Keep the record's stable id. Repair its access URI only if the old grant no
                // longer works (e.g. importing the same document through a newly granted tree).
                val oldTrack = staged[index]
                if (oldTrack.uri != uri.toString() && runCatching { verifyReadable(oldTrack.uri.toUri()) }.isFailure) {
                    staged[index] = oldTrack.copy(uri = uri.toString())
                    changed = true
                }
                duplicates++
                return
            }
            val metadata = readMetadata(uri)
            if (minDurationMs != null) {
                val duration = metadata.durationMs ?: throw IOException("无法识别音频时长")
                if (duration < minDurationMs) {
                    skippedShort++
                    return
                }
            }
            val uriString = uri.toString()
            indices[key] = staged.size
            staged += LocalTrack(
                id = uriString,
                uri = uriString,
                title = metadata.title ?: displayName?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri),
                artist = metadata.artist ?: UNKNOWN_ARTIST,
                album = metadata.album ?: UNKNOWN_ALBUM,
                durationMs = metadata.durationMs ?: 0L,
                addedAt = now()
            )
            imported++
            changed = true
        }

        suspend fun commit() {
            currentCoroutineContext().ensureActive()
            if (changed) {
                context.localMusicDataStore.edit { prefs ->
                    currentCoroutineContext().ensureActive()
                    prefs[KEY_TRACKS] = json.encodeToString(staged)
                }
            }
        }

        fun result() = LocalImportResult(imported, duplicates, failed, skippedShort)
    }

    private fun verifyReadable(uri: Uri) {
        // Opening the stream is the validity check. Do not copy or consume the source file.
        context.contentResolver.openInputStream(uri)?.use { } ?: throw IOException("无法打开文件")
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex)
            else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: UNKNOWN_TITLE

    private fun readMetadata(uri: Uri): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            ExtractedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it >= 0L }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Metadata is optional; a readable file still imports with fallback labels.
            ExtractedMetadata(null, null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decode(raw: String?): List<LocalTrack> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<LocalTrack>>(raw)
        } catch (error: Exception) {
            throw LocalMusicCatalogCorruptException(error)
        }
    }

    private class LocalMusicCatalogCorruptException(cause: Throwable) :
        IllegalStateException("本地音乐库数据损坏，原记录未修改", cause)

    private data class ExtractedMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?
    )
}
