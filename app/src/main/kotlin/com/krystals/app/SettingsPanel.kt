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
import kotlinx.coroutines.launch

/**
 * Per v0.8.26: user preferences panel with four groups.
 */
@Composable
fun SettingsPanel(
    settings: SettingsValues,
    onChange: (SettingsValues) -> Unit,
    onDismiss: () -> Unit,
    onRestoreDefaults: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(top = 48.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(localized("偏好设置", "Preferences"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
            }
            HorizontalDivider()

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // ══ General ══
                SectionTitle(localized("常规", "General"))

                Text(localized("语言", "Language"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("zh" to "中文", "en" to "EN").forEach { (code, label) ->
                        FilterChip(selected = settings.language != "auto" && settings.language == code, onClick = { onChange(settings.copy(language = code)) }, label = { Text(label) })
                    }
                }
                Text(localized("主题", "Theme"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(ThemeMode.SYSTEM, Icons.Default.BrightnessAuto, localized("跟随系统", "System")), Triple(ThemeMode.LIGHT, Icons.Default.LightMode, localized("浅色", "Light")), Triple(ThemeMode.DARK, Icons.Default.DarkMode, localized("深色", "Dark")))
                        .forEach { (mode, icon, label) ->
                            IconButton(onClick = { onChange(settings.copy(theme = mode)) }) { Icon(icon, null, tint = if (settings.theme == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                }

                Tog(settings.autoCheckUpdate, { onChange(settings.copy(autoCheckUpdate = it)) }, localized("自动检查更新", "Auto Check Update"))
                LabeledSliderSetting(localized("悬浮球收起透明度", "Ball Collapsed Alpha"), settings.ballCollapsedAlpha, 0f, 1f) { onChange(settings.copy(ballCollapsedAlpha = it)) }
                Tog(settings.showLockButton, { onChange(settings.copy(showLockButton = it)) }, localized("显示锁定按键", "Show Lock Button"))
                Tog(settings.showLegend, { onChange(settings.copy(showLegend = it)) }, localized("显示图例", "Show Legend"))

                // ══ File Handling ══
                SectionTitle(localized("文件处理", "File Handling"))

                Tog(settings.autoBondRules, { onChange(settings.copy(autoBondRules = it)) }, localized("自动计算键规则", "Auto Compute Bond Rules"))
                if (settings.autoBondRules) {
                    Text(localized("键规则模式", "Bond Rule Mode"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BondRuleMode.values().toList().forEach { mode ->
                            FilterChip(selected = settings.bondRuleMode == mode, onClick = { onChange(settings.copy(bondRuleMode = mode)) }, label = {
                                Text(when (mode) {
                                    BondRuleMode.AUTO -> localized("自动", "Auto")
                                    BondRuleMode.SMART_IONIC -> localized("智能离子", "Smart-Ionic")
                                    BondRuleMode.BONDING -> localized("共价半径", "Bonding")
                                    BondRuleMode.VDW -> localized("vdW半径", "vdW")
                                })
                            })
                        }
                    }
                }
                Tog(settings.autoConvertCell, { onChange(settings.copy(autoConvertCell = it)) }, localized("素晶胞自动转正当晶胞", "Auto Convert Cell"))
                Tog(settings.defaultShowBonds, { onChange(settings.copy(defaultShowBonds = it)) }, localized("默认显示所有化学键", "Default Show Bonds"))
                SegSetting(localized("默认延伸化学键", "Default Extend Bonds"), settings.defaultExtendBonds, ExtendBondsDefault.values().toList(), { onChange(settings.copy(defaultExtendBonds = it)) })
                SegSetting(localized("默认显示多面体", "Default Polyhedra"), settings.defaultPolyhedra, PolyhedraDefault.values().toList(), { onChange(settings.copy(defaultPolyhedra = it)) })

                // ══ Network ══
                SectionTitle(localized("网络", "Network"))
                SegSetting(localized("COD 下载节点", "COD Mirror"), settings.codMirrorMode, CodMirrorMode.values().toList(), { onChange(settings.copy(codMirrorMode = it)) })
                if (settings.codMirrorMode == CodMirrorMode.FIXED) {
                    Text(localized("固定节点", "Fixed Mirror"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CrystallographyOpenDatabase.MIRRORS.forEachIndexed { index, mirror ->
                            val host = mirror.testUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
                            FilterChip(
                                selected = settings.codFixedIndex == index,
                                onClick = { onChange(settings.copy(codFixedIndex = index)) },
                                label = { Text(host, maxLines = 1) },
                            )
                        }
                    }
                }
                if (settings.codMirrorMode == CodMirrorMode.CUSTOM) {
                    var url by remember(settings) { mutableStateOf(settings.codCustomUrl) }
                    var testing by remember { mutableStateOf(false) }
                    var testResult by remember { mutableStateOf<Boolean?>(null) }
                    val scope = rememberCoroutineScope()
                    OutlinedTextField(url, { url = it; onChange(settings.copy(codCustomUrl = it)); testResult = null }, label = { Text(localized("自定义节点 URL", "Custom Mirror URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(
                            enabled = !testing && url.isNotBlank(),
                            onClick = {
                                testing = true
                                testResult = null
                                scope.launch {
                                    testResult = CrystallographyOpenDatabase.testCustomMirror(url)
                                    testing = false
                                }
                            },
                        ) { Text(if (testing) localized("测试中...", "Testing...") else localized("测试", "Test")) }
                        testResult?.let { ok ->
                            Text(
                                if (ok) localized("✓ 节点可用，已沿用", "✓ Mirror reachable") else localized("✗ 节点不可用", "✗ Mirror unreachable"),
                                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // ══ Display ══
                SectionTitle(localized("显示", "Display"))
                SegSetting(localized("导出图片质量", "Export Quality"), settings.exportQuality, ExportQuality.values().toList(), { onChange(settings.copy(exportQuality = it)) })
                Tog(settings.exportShowAxes, { onChange(settings.copy(exportShowAxes = it)) }, localized("导出时显示坐标轴", "Export Show Axes"))
                Tog(settings.exportShowMeasurements, { onChange(settings.copy(exportShowMeasurements = it)) }, localized("导出时显示测量结果", "Export Show Measurements"))

                // ══ Restore Defaults ══
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                TextButton(onClick = onRestoreDefaults ?: {}, modifier = Modifier.fillMaxWidth()) {
                    Text(localized("恢复默认设置", "Restore Defaults"), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable private fun Tog(value: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable private fun LabeledSliderSetting(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = onChange, valueRange = min..max)
    Text("%.0f%%".format(value * 100f), style = MaterialTheme.typography.bodySmall)
}

@Composable private fun <T : Enum<T>> SegSetting(label: String, value: T, entries: Collection<T>, onChange: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { entry ->
            FilterChip(selected = value == entry, onClick = { onChange(entry) }, label = { Text(entry.name, style = MaterialTheme.typography.labelSmall) })
        }
    }
}
