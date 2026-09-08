package com.lin0721.linmusic.core.preferences

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
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
            // Picker providers may return pipes. Read once to completion before bounds, EXIF or
            // decoding, so a decoder cannot silently accept a partially delivered PNG.
            val snapshot = File.createTempFile("wallpaper-source-", ".tmp", context.cacheDir)
            var bitmap: Bitmap? = null
            val target = File(File(context.filesDir, "backgrounds").apply { mkdirs() }, "${UUID.randomUUID()}.png")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    snapshot.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 128L * 1024 * 1024) throw IOException("图片文件过大，请选择小于 128 MB 的图片")
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("无法读取此图片，请选择其他图片")
                val decoded = if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(snapshot)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                            decoder.setOnPartialImageListener { false }
                            val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                        }
                    } catch (error: ImageDecoder.DecodeException) {
                        throw IOException("图片内容不完整或无法解码，请重新选择或更换图片", error)
                    }
                } else {
                    decodeLegacyImage(snapshot)
                }
                bitmap = decoded
                target.outputStream().use { if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("图片保存失败") }
                ensureActive()
                val previous = preferences.background.first().imagePath
                withContext(NonCancellable) {
                    preferences.saveBackgroundImage(target.absolutePath)
                    deleteOwned(previous)
                }
            } catch (error: Throwable) { target.delete(); throw error }
            finally { bitmap?.recycle(); snapshot.delete() }
        }
    }
    private fun decodeLegacyImage(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法识别此图片")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
        }) ?: throw IOException("图片解码失败")
        try {
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            val matrix = Matrix().apply {
                if (exif?.isFlipped == true) postScale(-1f, 1f)
                postRotate(exif?.rotationDegrees?.toFloat() ?: 0f)
                val scale = minOf(1f, 2048f / maxOf(bitmap.width, bitmap.height))
                postScale(scale, scale)
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (error: Throwable) { bitmap.recycle(); throw error }
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
