package com.lin0721.linmusic.core.player.effects

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val LOUDNESS_SOURCE_KEY = "loudness_source_key"
fun MediaItem.withLoudnessKey(key: String): MediaItem = buildUpon().setMediaMetadata(mediaMetadata.buildUpon()
    .setExtras(Bundle(mediaMetadata.extras ?: Bundle()).apply { putString(LOUDNESS_SOURCE_KEY, key) }).build()).build()

suspend fun localAudioFingerprint(context: Context, uriString: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            "local:$uriString:${file.length()}:${file.lastModified()}"
        } else {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use ""
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it >= 0 } ?: cursor.getColumnIndex("date_modified")
                if (sizeIndex < 0 || modifiedIndex < 0 || cursor.isNull(sizeIndex) || cursor.isNull(modifiedIndex)) ""
                else "local:$uriString:${cursor.getLong(sizeIndex)}:${cursor.getLong(modifiedIndex)}"
            }.orEmpty()
        }
    }.getOrDefault("")
}
