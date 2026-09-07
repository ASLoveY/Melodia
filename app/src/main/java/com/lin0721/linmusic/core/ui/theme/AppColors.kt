package com.lin0721.linmusic.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

val AppBackground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val AppSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
val AppSurfaceRaised: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
val AppText: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
val AppTextSecondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val AppAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val AppHeaderTint: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
val AppSelected: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondaryContainer
