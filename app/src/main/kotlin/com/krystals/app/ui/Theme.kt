package com.krystals.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val DarkScheme = darkColorScheme(
    primary = Color(AppPalette.BRAND_LIGHT),
    secondary = Color(AppPalette.BRAND_MID),
    tertiary = Color(0xFFE4C6FF),
)
private val LightScheme = lightColorScheme(
    primary = Color(AppPalette.BRAND_DEEP),
    secondary = Color(AppPalette.BRAND_MID),
    tertiary = Color(0xFF61308F),
)

@Composable
fun KrystalsTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = MaterialTheme.typography, content = content)
}
