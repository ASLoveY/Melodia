package com.lin0721.linmusic.core.preferences

import org.junit.Assert.*
import org.junit.Test

class AppThemeModeTest {
    @Test fun explicitThemeOverridesSystemAppearance() {
        assertTrue(AppThemeMode.DARK.usesDarkTheme(false))
        assertFalse(AppThemeMode.LIGHT.usesDarkTheme(true))
        assertTrue(AppThemeMode.SYSTEM.usesDarkTheme(true))
        assertFalse(AppThemeMode.SYSTEM.usesDarkTheme(false))
    }
    @Test fun unknownOrMissingPreferenceFollowsSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored("old-value"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStored("DARK"))
    }
}
