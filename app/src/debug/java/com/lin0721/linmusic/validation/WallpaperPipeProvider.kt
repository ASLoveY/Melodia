package com.lin0721.linmusic.validation

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** Single-read, fragmented provider used only by image import instrumentation tests. */
class WallpaperPipeProvider : ContentProvider() {
    companion object {
        var bytes = byteArrayOf()
        val reads = AtomicInteger()
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || reads.incrementAndGet() != 1) throw FileNotFoundException("This image is delivered only once")
        val pipe = ParcelFileDescriptor.createPipe()
        val data = bytes.copyOf()
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    for (offset in data.indices step 127) output.write(data, offset, minOf(127, data.size - offset))
                }
            }
        }.start()
        return pipe[0]
    }
}
