package com.krystals.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Purple = Color(0xFF9966CC)
private val DarkScheme = darkColorScheme(primary = Color(0xFFCFA7F5), secondary = Purple, tertiary = Color(0xFFE4C6FF))
private val LightScheme = lightColorScheme(primary = Color(0xFF7542A5), secondary = Purple, tertiary = Color(0xFF61308F))

@Composable
fun KrystalsTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = MaterialTheme.typography, content = content)
}
