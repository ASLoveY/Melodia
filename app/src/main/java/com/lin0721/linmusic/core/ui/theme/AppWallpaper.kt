package com.lin0721.linmusic.core.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.lin0721.linmusic.core.preferences.BackgroundRepository
import com.lin0721.linmusic.core.preferences.BackgroundSettings
import org.koin.compose.koinInject
import java.io.File
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

private val LocalWallpaperVisible = staticCompositionLocalOf { false }
internal const val WALLPAPER_SCRIM_ALPHA = .35f
val isAppWallpaperVisible: Boolean @Composable @ReadOnlyComposable get() = LocalWallpaperVisible.current

// The lighter scrim needs stronger foregrounds, rather than hiding the picture again.
internal fun wallpaperColors(colors: ColorScheme): ColorScheme = colors.copy(
    onSurfaceVariant = colors.onSurface,
    primary = colors.onPrimaryContainer
)
val AppPageBackground: Color @Composable @ReadOnlyComposable get() =
    if (LocalWallpaperVisible.current) Color.Transparent else MaterialTheme.colorScheme.background

@Composable
fun AppWallpaper(enabled: Boolean, hazeState: HazeState? = null, overlay: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val repository: BackgroundRepository = koinInject()
    val settings by repository.background.collectAsStateWithLifecycle(BackgroundSettings())
    WallpaperContent(settings, enabled, hazeState, overlay, content)
}

@Composable
internal fun WallpaperContent(settings: BackgroundSettings, enabled: Boolean, hazeState: HazeState? = null, overlay: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val seed by produceState<Color?>(null, settings.imagePath) {
        value = null
        val imagePath = settings.imagePath ?: return@produceState
        value = try { wallpaperSeed(context, imagePath) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    val path = settings.imagePath.takeIf { enabled }
    val painter = rememberAsyncImagePainter(path?.let(::File))
    val visible = path != null && painter.state is coil.compose.AsyncImagePainter.State.Success
    CompositionLocalProvider(LocalWallpaperVisible provides visible) {
        val base = if (visible) wallpaperColors(MaterialTheme.colorScheme) else MaterialTheme.colorScheme
        MaterialTheme(colorScheme = if (visible && seed != null) wallpaperScheme(base, seed!!) else base) {
            Box(Modifier.fillMaxSize()) {
                // Capture only page content: navigation labels must never blur themselves.
                Box(Modifier.fillMaxSize().then(hazeState?.let { Modifier.haze(it) } ?: Modifier)
                    .background(MaterialTheme.colorScheme.background)) {
                    if (path != null) {
                        Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                            alpha = 1f - settings.transparency.coerceIn(0, 100) / 100f,
                            modifier = Modifier.fillMaxSize().then(if (visible) Modifier.testTag("app_wallpaper") else Modifier))
                        if (visible) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = WALLPAPER_SCRIM_ALPHA)))
                    }
                    content()
                }
                overlay()
            }
        }
    }
}
