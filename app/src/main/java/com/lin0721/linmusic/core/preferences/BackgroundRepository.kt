package com.lin0721.linmusic.core.preferences

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BackgroundRepository(private val context: Context, private val preferences: SettingsPreferences) {
    private val mutex = Mutex()
    private val preview = MutableStateFlow<Int?>(null)
    val background: Flow<BackgroundSettings> = combine(preferences.background, preview) { settings, transparency ->
        settings.copy(transparency = transparency ?: settings.transparency)
    }
    fun previewTransparency(value: Int) { preview.value = value.coerceIn(0, 100) }
    suspend fun saveTransparency(value: Int) {
        try { preferences.saveBackgroundTransparency(value) } finally { preview.value = null }
    }

    suspend fun importImage(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法读取此图片，请选择其他图片")
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
            }
            var bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("图片解码失败")
            val target = File(File(context.filesDir, "backgrounds").apply { mkdirs() }, "${UUID.randomUUID()}.png")
            try {
                val exif = context.contentResolver.openInputStream(uri)?.use { runCatching { ExifInterface(it) }.getOrNull() }
                val matrix = Matrix().apply {
                    if (exif?.isFlipped == true) postScale(-1f, 1f)
                    postRotate(exif?.rotationDegrees?.toFloat() ?: 0f)
                    val scale = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
                    postScale(scale, scale)
                }
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (transformed !== bitmap) { bitmap.recycle(); bitmap = transformed }
                target.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("图片保存失败") }
                ensureActive()
                val previous = preferences.background.first().imagePath
                withContext(NonCancellable) {
                    preferences.saveBackgroundImage(target.absolutePath)
                    deleteOwned(previous)
                }
            } catch (error: Throwable) { target.delete(); throw error }
            finally { bitmap.recycle() }
        }
    }
    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = preferences.background.first().imagePath
            withContext(NonCancellable) { preferences.saveBackgroundImage(null); deleteOwned(previous) }
        }
    }
    private fun deleteOwned(path: String?) {
        if (path == null) return
        val file = File(path).canonicalFile
        if (file.parentFile == File(context.filesDir, "backgrounds").canonicalFile) file.delete()
    }
}

internal fun imageSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > 4096) sample *= 2
    return sample
}
