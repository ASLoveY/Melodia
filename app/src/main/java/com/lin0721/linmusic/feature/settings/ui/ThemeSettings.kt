package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.preferences.AppThemeMode

internal fun AppThemeMode.displayName(): String = when (this) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色主题"
    AppThemeMode.DARK -> "深色主题"
}

@Composable
fun ThemeSettingsContent(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    SettingsGroupCard("外观与主题") {
        Column(Modifier.selectableGroup()) {
            AppThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().testTag("theme_${mode.name}")
                        .selectable(selected = selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == mode, onClick = null)
                    Text(mode.displayName(), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
