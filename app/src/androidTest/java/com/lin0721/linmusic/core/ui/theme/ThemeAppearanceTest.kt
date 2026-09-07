package com.lin0721.linmusic.core.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.lin0721.linmusic.core.preferences.AppThemeMode
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.feature.settings.ui.ThemeSettingsContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plainTextColorAndSettingsUpdateWithThemeWithoutRecreatingTheScreen() {
        var mode by mutableStateOf(AppThemeMode.LIGHT)
        var content = Color.Unspecified
        compose.setContent {
            MelodiaTheme(darkTheme = mode == AppThemeMode.DARK) {
                content = LocalContentColor.current
                ThemeSettingsContent(mode) { mode = it }
            }
        }
        compose.runOnIdle { assertEquals(MelodiaLightColors.onBackground, content) }
        compose.onNodeWithTag("theme_DARK").performClick()
        compose.runOnIdle {
            assertEquals(AppThemeMode.DARK, mode)
            assertEquals(MelodiaDarkColors.onBackground, content)
        }
        compose.onNodeWithTag("theme_LIGHT").performClick()
        compose.runOnIdle { assertEquals(MelodiaLightColors.onBackground, content) }
    }

    @Test fun themeChoiceSurvivesNewPreferencesInstance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = SettingsPreferences(context)
        val original = preferences.themeMode.first()
        try {
            preferences.saveThemeMode(AppThemeMode.DARK)
            assertEquals(AppThemeMode.DARK, SettingsPreferences(context).themeMode.first())
            preferences.saveThemeMode(AppThemeMode.LIGHT)
            assertEquals(AppThemeMode.LIGHT, SettingsPreferences(context).themeMode.first())
            preferences.saveThemeMode(AppThemeMode.SYSTEM)
            assertEquals(AppThemeMode.SYSTEM, SettingsPreferences(context).themeMode.first())
        } finally { preferences.saveThemeMode(original) }
    }
}
