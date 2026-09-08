package com.lin0721.linmusic.core.preferences

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackgroundRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = SettingsPreferences(context)
    private val repository = BackgroundRepository(context, prefs)

    private fun patternedPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 512, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256 * 512) { i -> android.graphics.Color.rgb(i % 251, (i / 256) % 251, (i * 37) % 251) }
        bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 512)
        return java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
        }
    }

    @Test fun fragmentedSingleReadProviderImportsAllRows() = runBlocking {
        val before = prefs.background.first()
        prefs.saveBackgroundImage(null)
        try {
            com.lin0721.linmusic.validation.WallpaperPipeProvider.bytes = patternedPng()
            com.lin0721.linmusic.validation.WallpaperPipeProvider.reads.set(0)
            repository.importImage(Uri.parse("content://${context.packageName}.wallpaper-validation/image"))
            val saved = BitmapFactory.decodeFile(prefs.background.first().imagePath)
            assertEquals(256, saved.width); assertEquals(512, saved.height)
            for (y in listOf(0, 128, 256, 511)) assertEquals(255, android.graphics.Color.alpha(saved.getPixel(128, y)))
            assertEquals(1, com.lin0721.linmusic.validation.WallpaperPipeProvider.reads.get())
            saved.recycle()
        } finally { repository.reset(); prefs.saveBackgroundImage(before.imagePath) }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 28)
    @Test fun truncatedPngCannotReplaceAnExistingBackground() = runBlocking {
        val before = prefs.background.first()
        prefs.saveBackgroundImage(null)
        val source = File(context.cacheDir, "wallpaper-truncated.png")
        try {
            val full = patternedPng()
            source.writeBytes(full)
            repository.importImage(Uri.fromFile(source))
            val valid = prefs.background.first().imagePath
            source.writeBytes(full.copyOf(full.size / 2))
            assertTrue(runCatching { repository.importImage(Uri.fromFile(source)) }.isFailure)
            assertEquals(valid, prefs.background.first().imagePath)
        } finally { repository.reset(); prefs.saveBackgroundImage(before.imagePath); source.delete() }
    }

    @Test fun imageCopySurvivesSourceRemovalReplacementAndInvalidImport() = runBlocking {
        val before = prefs.background.first()
        // Preserve any existing user's saved image while this test exercises its own copy.
        prefs.saveBackgroundImage(null)
        val source = File(context.cacheDir, "wallpaper-test.png")
        try {
            val bitmap = Bitmap.createBitmap(4200, 2100, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            repository.importImage(Uri.fromFile(source))
            val saved = prefs.background.first().imagePath!!
            source.delete()
            val decoded = BitmapFactory.decodeFile(saved)
            assertEquals(2048, decoded.width); assertEquals(1024, decoded.height); decoded.recycle()
            assertTrue(File(saved).exists())
            source.writeText("broken image")
            assertTrue(runCatching { repository.importImage(Uri.fromFile(source)) }.isFailure)
            assertEquals(saved, prefs.background.first().imagePath)
            repository.reset()
            assertFalse(File(saved).exists())
            assertTrue(source.exists())
        } finally { repository.reset(); prefs.saveBackgroundImage(before.imagePath); source.delete() }
    }
    @Test fun settingsPersistAndTransparencyPreviewDoesNotWriteUntilReleased() = runBlocking {
        val before = prefs.background.first()
        val effects = prefs.playbackEffects.first()
        try {
            prefs.saveBackgroundTransparency(70)
            repository.previewTransparency(0)
            assertEquals(0, repository.background.first().transparency)
            assertEquals(70, prefs.background.first().transparency)
            repository.saveTransparency(100)
            assertEquals(100, SettingsPreferences(context).background.first().transparency)
            prefs.savePlaybackEffects(PlaybackEffectsSettings(false, 12, false))
            assertEquals(PlaybackEffectsSettings(false, 12, false), SettingsPreferences(context).playbackEffects.first())
        } finally { prefs.saveBackgroundTransparency(before.transparency); prefs.savePlaybackEffects(effects) }
    }

    @Test fun portraitExifRotationIsAppliedToTheSavedCopy() = runBlocking {
        val before = prefs.background.first()
        prefs.saveBackgroundImage(null)
        val source = File(context.cacheDir, "wallpaper-rotated-test.jpg")
        try {
            val bitmap = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            androidx.exifinterface.media.ExifInterface(source).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            repository.importImage(Uri.fromFile(source))
            val saved = BitmapFactory.decodeFile(prefs.background.first().imagePath)
            assertEquals(120, saved.width); assertEquals(60, saved.height); saved.recycle()
            assertTrue(source.exists())
        } finally { repository.reset(); prefs.saveBackgroundImage(before.imagePath); source.delete() }
    }
}
