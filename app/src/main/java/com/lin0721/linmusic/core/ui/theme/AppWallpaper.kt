package com.lin0721.linmusic.core.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.lin0721.linmusic.core.preferences.BackgroundRepository
import com.lin0721.linmusic.core.preferences.BackgroundSettings
import org.koin.compose.koinInject
import java.io.File

private val LocalWallpaperVisible = staticCompositionLocalOf { false }
val AppPageBackground: Color @Composable @ReadOnlyComposable get() =
    if (LocalWallpaperVisible.current) Color.Transparent else MaterialTheme.colorScheme.background

@Composable
fun AppWallpaper(enabled: Boolean, content: @Composable () -> Unit) {
    val repository: BackgroundRepository = koinInject()
    val settings by repository.background.collectAsStateWithLifecycle(BackgroundSettings())
    WallpaperContent(settings, enabled, content)
}

@Composable
internal fun WallpaperContent(settings: BackgroundSettings, enabled: Boolean, content: @Composable () -> Unit) {
    val path = settings.imagePath.takeIf { enabled }
    val painter = rememberAsyncImagePainter(path?.let(::File))
    val visible = path != null && painter.state is coil.compose.AsyncImagePainter.State.Success
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (path != null) {
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = 1f - settings.transparency.coerceIn(0, 100) / 100f,
                modifier = Modifier.fillMaxSize().then(if (visible) Modifier.testTag("app_wallpaper") else Modifier))
            // One shared readability layer prevents multiple nested page backgrounds from accumulating.
            if (visible) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .9f)))
        }
        CompositionLocalProvider(LocalWallpaperVisible provides visible, content = content)
    }
}
