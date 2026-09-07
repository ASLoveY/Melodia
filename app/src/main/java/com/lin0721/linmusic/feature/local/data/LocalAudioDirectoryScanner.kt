package com.lin0721.linmusic.feature.local.data

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

private const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

/**
 * A document returned by a document provider while walking a directory tree.
 *
 * This type deliberately contains no platform classes so that the traversal rules can be tested
 * on the JVM. The repository is responsible for translating provider rows into this value.
 */
internal data class LocalDocument(
    val id: String,
    val name: String,
    val mimeType: String
)

internal data class DirectoryScanSummary(
    val audioFiles: Int,
    val failedDirectories: Int
)

/**
 * Walks a document-provider directory tree without recursion.
 *
 * Every document id is visited at most once. This both avoids duplicate imports when a provider
 * returns the same row more than once and prevents malformed providers from creating cycles. A
 * directory that cannot be read is counted and skipped so that sibling directories can still be
 * imported. Cancellation and callback failures intentionally escape to the caller.
 */
internal suspend fun scanAudioDocuments(
    rootId: String,
    listChildren: suspend (String) -> List<LocalDocument>,
    onAudio: suspend (LocalDocument) -> Unit
): DirectoryScanSummary {
    val pendingDirectories = ArrayDeque<String>()
    val visitedDocuments = HashSet<String>()
    pendingDirectories.addLast(rootId)
    visitedDocuments.add(rootId)

    var audioFiles = 0
    var failedDirectories = 0
    var visitedSinceYield = 0

    while (pendingDirectories.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val directoryId = pendingDirectories.removeFirst()
        val children = try {
            listChildren(directoryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failedDirectories++
            continue
        }

        for (document in children) {
            currentCoroutineContext().ensureActive()
            if (document.id.isBlank()) continue
            if (!visitedDocuments.add(document.id)) continue

            if (document.isDirectory()) {
                pendingDirectories.addLast(document.id)
            } else if (document.isAudio()) {
                // Do not catch this callback: persistence failures and cancellation must be
                // visible to the repository instead of producing a misleading partial result.
                onAudio(document)
                audioFiles++
            }

            visitedSinceYield++
            if (visitedSinceYield >= YIELD_INTERVAL) {
                visitedSinceYield = 0
                yield()
            }
        }
    }

    return DirectoryScanSummary(
        audioFiles = audioFiles,
        failedDirectories = failedDirectories
    )
}

private const val YIELD_INTERVAL = 64

private fun LocalDocument.isDirectory(): Boolean =
    mimeType.substringBefore(';').trim().equals(DIRECTORY_MIME_TYPE, ignoreCase = true)

private fun LocalDocument.isAudio(): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    if (normalizedMime.startsWith("audio/", ignoreCase = true)) return true

    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
    return extension in COMMON_AUDIO_EXTENSIONS
}

private val COMMON_AUDIO_EXTENSIONS = setOf(
    "aac",
    "ac3",
    "aif",
    "aiff",
    "alac",
    "amr",
    "ape",
    "caf",
    "dts",
    "flac",
    "m4a",
    "m4b",
    "mid",
    "midi",
    "mp3",
    "oga",
    "ogg",
    "opus",
    "wav",
    "weba",
    "wma"
)
