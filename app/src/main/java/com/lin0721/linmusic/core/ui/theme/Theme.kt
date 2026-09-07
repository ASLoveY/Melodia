package com.lin0721.linmusic.core.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.lin0721.linmusic.core.ui.interaction.MelodiaPressIndication

internal val MelodiaDarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB), onPrimary = Color(0xFF5E1013),
    primaryContainer = Color(0xFF792028), onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB8C9E0), onSecondary = Color(0xFF203047),
    secondaryContainer = Color(0xFF34455D), onSecondaryContainer = Color(0xFFD9E5F7),
    tertiary = Color(0xFFC6C0EF), onTertiary = Color(0xFF302953),
    background = Color(0xFF101318), onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF1B2028), onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF303945), onSurfaceVariant = Color(0xFFCAD2DF),
    surfaceDim = Color(0xFF101318), surfaceBright = Color(0xFF39414C),
    surfaceContainerLowest = Color(0xFF0B0E13), surfaceContainerLow = Color(0xFF171C23),
    surfaceContainer = Color(0xFF202731), surfaceContainerHigh = Color(0xFF29323E),
    surfaceContainerHighest = Color(0xFF333E4B),
    outline = Color(0xFF98A5B7), outlineVariant = Color(0xFF526073),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4E9F0), inverseOnSurface = Color(0xFF202731),
    inversePrimary = Color(0xFFA91621), surfaceTint = Color.Transparent
)

internal val MelodiaLightColors = lightColorScheme(
    primary = Color(0xFFA91621), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF68000B),
    secondary = Color(0xFF3D5677), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7F7), onSecondaryContainer = Color(0xFF172E4C),
    tertiary = Color(0xFF5A477C), onTertiary = Color.White,
    background = Color(0xFFF5F7FB), onBackground = Color(0xFF17212F),
    surface = Color.White, onSurface = Color(0xFF17212F),
    surfaceVariant = Color(0xFFE4EAF2), onSurfaceVariant = Color(0xFF435267),
    surfaceDim = Color(0xFFD6DDE8), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFEBEFF6), surfaceContainerHigh = Color(0xFFE3E9F2),
    surfaceContainerHighest = Color(0xFFDCE3ED),
    outline = Color(0xFF65758B), outlineVariant = Color(0xFFB8C3D2),
    error = Color(0xFFAA1220), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF68000B),
    inverseSurface = Color(0xFF29323E), inverseOnSurface = Color(0xFFF2F4F8),
    inversePrimary = Color(0xFFFFB4AB), surfaceTint = Color.Transparent
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) MelodiaDarkColors else MelodiaLightColors
    MaterialTheme(colorScheme = colors, typography = Typography, shapes = MelodiaShapes) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalIndication provides MelodiaPressIndication.Default,
            LocalRippleConfiguration provides null,
            content = content
        )
    }
}
