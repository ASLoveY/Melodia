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
import com.lin0721.linmusic.feature.local.domain.LocalMusicDirectory
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
private const val DIRECTORY_SCHEMA_VERSION = "1"

private val Context.localMusicDataStore by preferencesDataStore(name = DATASTORE_NAME)

/** A document has the same identity whether selected alone or through a directory grant. */
internal fun localDocumentKey(uri: Uri): String = runCatching {
    if (uri.scheme == "content" && "document" in uri.pathSegments) {
        DocumentsContract.buildDocumentUri(requireNotNull(uri.authority), DocumentsContract.getDocumentId(uri)).toString()
    } else uri.toString()
}.getOrDefault(uri.toString())

/** Stable id for an ACTION_OPEN_DOCUMENT_TREE grant. */
internal fun localDirectoryId(uri: Uri): String {
    val authority = uri.authority
    val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    return if (authority != null && treeDocumentId != null) {
        DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId).toString()
    } else {
        uri.toString()
    }
}

/**
 * Tests whether a persisted document URI belongs to a tree root.
 *
 * External storage document ids are hierarchical, so the separator is checked explicitly. For
 * other providers the tree id embedded in the URI must match exactly; a provider-specific path
 * prefix is not enough to infer ownership.
 */
internal fun localDocumentBelongsToDirectory(rootUri: Uri, documentUri: Uri): Boolean {
    if (rootUri.scheme != "content" || documentUri.scheme != "content") return false
    if (rootUri.authority != documentUri.authority) return false
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
        ?: return false

    if (rootUri.authority == "com.android.externalstorage.documents") {
        val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
            ?: return false
        val descendantPrefix = if (rootId.endsWith(":")) rootId else "$rootId/"
        return documentId == rootId || documentId.startsWith(descendantPrefix)
    }

    return treeIdFromUri(documentUri) == rootId
}

private fun treeIdFromUri(uri: Uri): String? {
    val segments = uri.pathSegments
    val treeIndex = segments.indexOfFirst { it == "tree" }
    return segments.getOrNull(treeIndex + 1)?.takeIf { treeIndex >= 0 }
}

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
        val KEY_DIRECTORIES = stringPreferencesKey("directories_json")
        val KEY_DIRECTORY_SCHEMA = stringPreferencesKey("directories_schema")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val catalogMutex = Mutex()

    private val snapshotFlow: Flow<CatalogSnapshot> = context.localMusicDataStore.data
        .map { prefs -> normalizeSnapshot(prefs) }
        .flowOn(ioDispatcher)

    override val tracks: Flow<List<LocalTrack>> = snapshotFlow.map { it.tracks }

    override val directories: Flow<List<LocalMusicDirectory>> = snapshotFlow.map { it.directories }

    override suspend fun importUris(uris: List<Uri>): LocalImportResult =
        withContext(ioDispatcher) {
            catalogMutex.withLock {
                val batch = ImportBatch(readSnapshot())
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

    override suspend fun registerDownload(uri: Uri, song: com.lin0721.linmusic.core.model.Track) =
        withContext(ioDispatcher) {
            catalogMutex.withLock {
                verifyReadable(uri)
                val duration = readMetadata(uri).durationMs?.takeIf { it > 0 }
                    ?: throw IOException("下载的文件不是有效音频")
                val snapshot = readSnapshot()
                val track = LocalTrack(
                    id = uri.toString(), uri = uri.toString(), title = song.name,
                    artist = song.ar.joinToString(" / ") { it.name }.ifBlank { UNKNOWN_ARTIST },
                    album = song.al.name.ifBlank { UNKNOWN_ALBUM }, durationMs = duration, addedAt = now()
                )
                persistSnapshot(snapshot.tracks.filterNot { it.uri == track.uri } + track, snapshot.directories)
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
            val batch = ImportBatch(readSnapshot())
            batch.ensureDirectory(
                LocalMusicDirectory(
                    id = localDirectoryId(uri),
                    uri = uri.toString(),
                    name = queryDirectoryDisplayName(uri, rootId),
                    addedAt = now()
                )
            )
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
                            minDurationMs,
                            sourceDirectoryId = localDirectoryId(uri)
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
        removeAll(setOf(id))
    }

    override suspend fun removeAll(ids: Set<String>): Int = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext 0
        catalogMutex.withLock {
            val snapshot = readSnapshot()
            val updated = LocalMusicCatalog.removeAll(snapshot.tracks, ids)
            val removed = snapshot.tracks.size - updated.size
            if (removed > 0 || snapshot.needsMigration) {
                persistSnapshot(updated, snapshot.directories)
            }
            removed
        }
    }

    override suspend fun removeDirectory(id: String): Int = withContext(ioDispatcher) {
        if (id.isBlank()) return@withContext 0
        catalogMutex.withLock {
            val snapshot = readSnapshot()
            if (snapshot.directories.none { it.id == id }) return@withLock 0
            val updated = LocalMusicCatalog.removeDirectory(snapshot.tracks, id)
            val removed = snapshot.tracks.size - updated.size
            val directories = snapshot.directories.filterNot { it.id == id }
            persistSnapshot(updated, directories)
            removed
        }
    }

    private suspend fun readSnapshot(): CatalogSnapshot =
        normalizeSnapshot(context.localMusicDataStore.data.first())

    private suspend fun persistSnapshot(
        tracks: List<LocalTrack>,
        directories: List<LocalMusicDirectory>
    ) {
        currentCoroutineContext().ensureActive()
        context.localMusicDataStore.edit { prefs ->
            currentCoroutineContext().ensureActive()
            if (tracks.isEmpty()) prefs.remove(KEY_TRACKS)
            else prefs[KEY_TRACKS] = json.encodeToString(tracks)
            if (directories.isEmpty()) prefs.remove(KEY_DIRECTORIES)
            else prefs[KEY_DIRECTORIES] = json.encodeToString(directories)
            prefs[KEY_DIRECTORY_SCHEMA] = DIRECTORY_SCHEMA_VERSION
        }
    }

    private fun normalizeSnapshot(prefs: androidx.datastore.preferences.core.Preferences): CatalogSnapshot {
        val tracks = decode(prefs[KEY_TRACKS])
        val directories = decodeDirectories(prefs[KEY_DIRECTORIES])
        if (prefs[KEY_DIRECTORY_SCHEMA] == DIRECTORY_SCHEMA_VERSION) {
            return CatalogSnapshot(tracks, directories, needsMigration = false)
        }
        return recoverLegacyDirectories(tracks, directories)
    }

    /**
     * v1.2.1 stored only tracks. Recover roots from persisted tree grants while the grant still
     * exists, and use document ids rather than display names to avoid foo/foobar collisions.
     */
    private fun recoverLegacyDirectories(
        tracks: List<LocalTrack>,
        existingDirectories: List<LocalMusicDirectory>
    ): CatalogSnapshot {
        if (tracks.isEmpty()) return CatalogSnapshot(tracks, existingDirectories, true)
        val recovered = existingDirectories.toMutableList()
        val recoveredById = recovered.associateBy { it.id }.toMutableMap()
        val recoveredTracks = tracks.toMutableList()
        val candidateRoots = linkedMapOf<String, Uri>()
        runCatching { context.contentResolver.persistedUriPermissions }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { DocumentsContract.isTreeUri(it) }
            .forEach { rootUri ->
                candidateRoots.putIfAbsent(localDirectoryId(rootUri), rootUri)
            }

        // A revoked grant is no longer listed in persistedUriPermissions, but a v1.2.1 track
        // imported through that grant still embeds the exact tree root in its URI. Keep a
        // directory entry so the user can remove the stale catalog records.
        recoveredTracks.asSequence()
            .map { it.uri.toUri() }
            .mapNotNull { documentUri ->
                val authority = documentUri.authority ?: return@mapNotNull null
                val treeId = treeIdFromUri(documentUri) ?: return@mapNotNull null
                runCatching { DocumentsContract.buildTreeDocumentUri(authority, treeId) }.getOrNull()
            }
            .forEach { rootUri ->
                candidateRoots.putIfAbsent(localDirectoryId(rootUri), rootUri)
            }

        candidateRoots.values.asSequence()
            .forEach { rootUri ->
                val matchingIndexes = recoveredTracks.indices.filter { index ->
                    localDocumentBelongsToDirectory(rootUri, recoveredTracks[index].uri.toUri())
                }
                if (matchingIndexes.isEmpty()) return@forEach

                val directoryId = localDirectoryId(rootUri)
                val directory = recoveredById.getOrPut(directoryId) {
                    LocalMusicDirectory(
                        id = directoryId,
                        uri = rootUri.toString(),
                        name = queryDirectoryDisplayName(
                            rootUri,
                            runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrDefault("")
                        ),
                        addedAt = matchingIndexes.minOf { recoveredTracks[it].addedAt }
                    )
                }
                matchingIndexes.forEach { index ->
                    val track = recoveredTracks[index]
                    if (directory.id !in track.sourceDirectoryIds) {
                        recoveredTracks[index] = track.copy(
                            sourceDirectoryIds = track.sourceDirectoryIds + directory.id
                        )
                    }
                }
            }

        return CatalogSnapshot(recoveredTracks, recoveredById.values.toList(), true)
    }

    /** Stage a complete import under catalogMutex, so cancellation never commits a partial scan. */
    private inner class ImportBatch(snapshot: CatalogSnapshot) {
        private val staged = snapshot.tracks.toMutableList()
        private val stagedDirectories = snapshot.directories.toMutableList()
        private val indices = HashMap<String, Int>().apply {
            snapshot.tracks.forEachIndexed { index, track ->
                putIfAbsent(localDocumentKey(track.uri.toUri()), index)
            }
        }
        private var changed = snapshot.needsMigration
        var imported = 0
            private set
        var duplicates = 0
            private set
        var skippedShort = 0
            private set
        var failed = 0

        fun add(
            uri: Uri,
            displayName: String? = null,
            minDurationMs: Long? = null,
            sourceDirectoryId: String? = null
        ) {
            verifyReadable(uri)
            val key = localDocumentKey(uri)
            val index = indices[key]
            if (index != null) {
                if (minDurationMs != null) {
                    val duration = readMetadata(uri).durationMs
                        ?: throw IOException("无法识别音频时长")
                    if (duration < minDurationMs) {
                        skippedShort++
                        return
                    }
                }
                // Keep the record's stable id. Repair its access URI only if the old grant no
                // longer works (e.g. importing the same document through a newly granted tree).
                val oldTrack = staged[index]
                if (oldTrack.uri != uri.toString() && runCatching { verifyReadable(oldTrack.uri.toUri()) }.isFailure) {
                    staged[index] = oldTrack.copy(uri = uri.toString())
                    changed = true
                }
                if (sourceDirectoryId != null && sourceDirectoryId !in oldTrack.sourceDirectoryIds) {
                    staged[index] = staged[index].copy(
                        sourceDirectoryIds = staged[index].sourceDirectoryIds + sourceDirectoryId
                    )
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
                addedAt = now(),
                sourceDirectoryIds = sourceDirectoryId?.let(::setOf).orEmpty()
            )
            imported++
            changed = true
        }

        fun ensureDirectory(directory: LocalMusicDirectory) {
            val index = stagedDirectories.indexOfFirst { it.id == directory.id }
            if (index < 0) {
                stagedDirectories += directory
                changed = true
            } else {
                val current = stagedDirectories[index]
                val updated = current.copy(uri = directory.uri, name = directory.name)
                if (updated != current) {
                    stagedDirectories[index] = updated
                    changed = true
                }
            }
        }

        suspend fun commit() {
            currentCoroutineContext().ensureActive()
            if (changed) {
                persistSnapshot(staged, stagedDirectories)
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

    private fun queryDirectoryDisplayName(uri: Uri, rootId: String): String = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(uri, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex)
            else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: rootId.substringAfterLast(':')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }
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

    private fun decodeDirectories(raw: String?): List<LocalMusicDirectory> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<LocalMusicDirectory>>(raw)
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

    private data class CatalogSnapshot(
        val tracks: List<LocalTrack>,
        val directories: List<LocalMusicDirectory>,
        val needsMigration: Boolean
    )
}
