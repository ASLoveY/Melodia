package com.lin0721.linmusic.feature.local.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin0721.linmusic.feature.local.domain.LocalImportResult
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
                importUrisLocked(uris)
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

    private suspend fun importUrisLocked(uris: List<Uri>): LocalImportResult {
        if (uris.isEmpty()) return LocalImportResult(imported = 0, duplicates = 0, failed = 0)

        val existing = tracks.first()
        val existingUris = existing.mapTo(HashSet(existing.size)) { it.uri }
        val acceptedUris = HashSet<String>(uris.size)
        val importedTracks = ArrayList<LocalTrack>(uris.size)
        var duplicates = 0
        var failed = 0

        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val uriString = uri.toString()
            if (uriString.isBlank()) {
                failed++
                continue
            }
            try {
                // ACTION_OPEN_DOCUMENT grants this persistable permission. If the provider does
                // not support it, do not add or count a record that cannot be reopened.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                verifyReadable(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed URI is not kept in the catalog and can be retried in the same batch.
                failed++
                continue
            }

            // Authorization/readability is checked before this branch so re-selecting an
            // existing URI can repair a revoked persisted grant before being counted duplicate.
            if (!acceptedUris.add(uriString) || existingUris.contains(uriString)) {
                duplicates++
                continue
            }

            val metadata = readMetadata(uri)
            val fileName = queryDisplayName(uri)
            importedTracks += LocalTrack(
                id = uriString,
                uri = uriString,
                title = metadata.title ?: fileName,
                artist = metadata.artist ?: UNKNOWN_ARTIST,
                album = metadata.album ?: UNKNOWN_ALBUM,
                durationMs = metadata.durationMs ?: 0L,
                addedAt = now()
            )
        }

        currentCoroutineContext().ensureActive()
        if (importedTracks.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            context.localMusicDataStore.edit { prefs ->
                val current = decode(prefs[KEY_TRACKS])
                val merged = LocalMusicCatalog.merge(current, importedTracks)
                prefs[KEY_TRACKS] = json.encodeToString(merged)
            }
        }

        return LocalImportResult(
            imported = importedTracks.size,
            duplicates = duplicates,
            failed = failed
        )
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
