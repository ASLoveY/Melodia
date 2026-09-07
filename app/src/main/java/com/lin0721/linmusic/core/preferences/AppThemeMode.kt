package com.lin0721.linmusic.core.preferences

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK;

    fun usesDarkTheme(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): AppThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
