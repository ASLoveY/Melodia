package com.lin0721.linmusic.core.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.Screen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

@Composable
fun MelodiaNavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onCreateClick: () -> Unit,
    isCreateMenuOpen: Boolean,
    showCreateEntry: Boolean = true,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    val shape = RoundedCornerShape(30.dp)
    val glass = if (hazeState != null && Build.VERSION.SDK_INT >= 31) {
        Modifier.hazeChild(hazeState, shape, HazeStyle(
            tint = colors.surface.copy(alpha = if (dark) .64f else .60f),
            blurRadius = 28.dp, noiseFactor = .025f
        ))
    } else Modifier.background(colors.surface.copy(alpha = .94f), shape)

    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().shadow(12.dp, shape).clip(shape)
                .then(glass)
                .background(Brush.linearGradient(listOf(
                    Color.White.copy(alpha = if (dark) .12f else .30f),
                    Color.White.copy(alpha = .02f),
                    colors.primary.copy(alpha = .08f)
                )))
                .border(1.dp, Brush.linearGradient(listOf(
                    Color.White.copy(alpha = .65f), colors.primary.copy(alpha = .12f),
                    Color.White.copy(alpha = .25f)
                )), shape)
                .padding(6.dp).selectableGroup().testTag("glass_navigation"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val items = buildList {
                add(Triple("主页", Icons.Default.Home, Screen.Home))
                add(Triple("搜索", Icons.Default.Search, Screen.Search))
                add(Triple("音乐库", Icons.Default.LibraryMusic, Screen.Library))
                if (showCreateEntry) add(Triple("创建", if (isCreateMenuOpen) Icons.Rounded.Close else Icons.Default.AddBox, null))
            }
            items.forEach { (label, icon, target) ->
                val selected = if (target == null) isCreateMenuOpen else currentScreen == target
                val fill by animateColorAsState(
                    if (selected) colors.primaryContainer else Color.Transparent,
                    tween(220), label = "navigation_selection"
                )
                val ink = if (selected) colors.onPrimaryContainer else colors.onSurface
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(fill)
                        .selectable(selected, role = Role.Tab, onClick = {
                            if (target != null) onNavigate(target) else onCreateClick()
                        }).heightIn(min = 52.dp).padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(23.dp))
                    Text(label, color = ink, fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
