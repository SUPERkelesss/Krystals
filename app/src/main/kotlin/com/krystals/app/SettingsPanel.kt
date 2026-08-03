package com.krystals.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krystals.app.ui.ThemeMode

/**
 * Per v0.8.26: user preferences panel with four groups:
 *   General, File Handling, Network, Display.
 */
@Composable
fun SettingsPanel(
    settings: SettingsValues,
    onChange: (SettingsValues) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    localized("偏好设置", "Preferences"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
            }
            HorizontalDivider()

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // ── General ──
                SectionTitle(localized("常规", "General"))

                // Language
                Text(localized("语言", "Language"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("zh" to "中文", "en" to "EN").forEach { (code, label) ->
                        FilterChip(
                            selected = when { settings.language == "auto" -> false; else -> settings.language == code },
                            onClick = { onChange(settings.copy(language = code)) },
                            label = { Text(label) },
                        )
                    }
                }

                // Theme
                Text(localized("主题", "Theme"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple(ThemeMode.SYSTEM, Icons.Default.BrightnessAuto, localized("跟随系统", "System")),
                        Triple(ThemeMode.LIGHT, Icons.Default.LightMode, localized("浅色", "Light")),
                        Triple(ThemeMode.DARK, Icons.Default.DarkMode, localized("深色", "Dark")),
                    ).forEach { (mode, icon, label) ->
                        IconButton(onClick = { onChange(settings.copy(theme = mode)) }) {
                            Icon(
                                icon, null,
                                tint = if (settings.theme == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ── File Handling ──
                SectionTitle(localized("文件处理", "File Handling"))

                // ── Network ──
                SectionTitle(localized("网络", "Network"))

                // ── Display ──
                SectionTitle(localized("显示", "Display"))

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}
