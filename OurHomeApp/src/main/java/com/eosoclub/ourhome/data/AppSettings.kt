package com.eosoclub.ourhome.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { Dark("Dark"), Light("Light"), System("System default") }

/** Device-local UI preferences. */
class AppSettings(private val prefs: SharedPreferences) {

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME, null)
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            ?: ThemeMode.Dark,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}
