package com.krystals.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krystals.app.ui.ThemeMode
import kotlinx.coroutines.launch

/**
 * Per v0.8.28: user preferences dialog. Window style matches the Appearance dialog
 * (large window, bold group headings with dividers); enum options use text dropdowns
 * ([DropdownField]) instead of chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    settings: SettingsValues,
    onChange: (SettingsValues) -> Unit,
    onDismiss: () -> Unit,
    onRestoreDefaults: (() -> Unit)? = null,
) {
    // Per v0.8.30: settings are frozen on open; only Save applies them.
    var draft by remember { mutableStateOf(settings) }
    val apply = { onChange(draft) }
    // Label mapping helpers (localized() is @Composable, so these live in composition).
    val languageLabels = listOf(localized("中文", "Chinese"), "EN")
    val themeLabels = ThemeMode.entries.map { mode -> when (mode) {
        ThemeMode.SYSTEM -> localized("跟随系统", "System")
        ThemeMode.DARK -> localized("深色", "Dark")
        ThemeMode.LIGHT -> localized("浅色", "Light")
    } }
    val bondRuleLabels = listOf(
        localized("自动", "Auto"), localized("智能离子", "Smart-Ionic"),
        localized("共价半径", "Bonding"), localized("vdW半径", "vdW"),
    )
    val extendLabels = listOf(localized("全部", "All"), localized("仅金属", "Metals only"), localized("从不", "Never"))
    val codModeLabels = listOf(
        localized("由 Krystals 决定", "Krystals auto"), localized("固定节点", "Fixed mirror"), localized("自定义节点", "Custom mirror"),
    )
    val qualityLabels = listOf(localized("高", "High"), localized("低", "Low"))

    fun languageLabel(code: String): String {
        val effective = if (code == "auto") (if (java.util.Locale.getDefault().language == "zh") "zh" else "en") else code
        return if (effective == "zh") languageLabels[0] else languageLabels[1]
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                Text(localized("偏好设置", "Preferences"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                Column(Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState())) {
                    // ══ 常规 / General ══
                    Text(localized("常规", "General"), fontWeight = FontWeight.Bold)
                    DropdownField(localized("语言", "Language"), languageLabel(draft.language), languageLabels) { label ->
                        draft = draft.copy(language = if (label == languageLabels[0]) "zh" else "en")
                    }
                    DropdownField(localized("主题", "Theme"), themeLabels[ThemeMode.entries.indexOf(draft.theme).coerceIn(0, themeLabels.lastIndex)], themeLabels) { label ->
                        draft = draft.copy(theme = ThemeMode.entries[themeLabels.indexOf(label)])
                    }
                    Tog(draft.autoCheckUpdate, { draft = draft.copy(autoCheckUpdate = it) }, localized("自动检查更新", "Auto Check Update"))
                    LabeledSliderSetting(localized("悬浮球收起透明度", "Ball Collapsed Alpha"), draft.ballCollapsedAlpha, 0f, 1f) { draft = draft.copy(ballCollapsedAlpha = it) }
                    Tog(draft.showLockButton, { draft = draft.copy(showLockButton = it) }, localized("显示锁定按键", "Show Lock Button"))
                    Tog(draft.showLegend, { draft = draft.copy(showLegend = it) }, localized("显示图例", "Show Legend"))
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ 文件处理 / File Handling ══
                    Text(localized("文件处理", "File Handling"), fontWeight = FontWeight.Bold)
                    Tog(draft.autoBondRules, { draft = draft.copy(autoBondRules = it) }, localized("自动计算键规则", "Auto Compute Bond Rules"))
                    if (draft.autoBondRules) {
                        DropdownField(localized("自动应用的键规则", "Bond Rule Mode"), bondRuleLabels[draft.bondRuleMode.ordinal], bondRuleLabels) { label ->
                            draft = draft.copy(bondRuleMode = BondRuleMode.entries[bondRuleLabels.indexOf(label)])
                        }
                    }
                    Tog(draft.autoConvertCell, { draft = draft.copy(autoConvertCell = it) }, localized("素晶胞自动转正当晶胞", "Auto Convert Cell"))
                    Tog(draft.defaultShowBonds, { draft = draft.copy(defaultShowBonds = it) }, localized("默认显示所有化学键", "Default Show Bonds"))
                    DropdownField(localized("默认延伸化学键", "Default Extend Bonds"), extendLabels[draft.defaultExtendBonds.ordinal], extendLabels) { label ->
                        draft = draft.copy(defaultExtendBonds = ExtendBondsDefault.entries[extendLabels.indexOf(label)])
                    }
                    DropdownField(localized("默认显示多面体", "Default Polyhedra"), extendLabels[draft.defaultPolyhedra.ordinal], extendLabels) { label ->
                        draft = draft.copy(defaultPolyhedra = PolyhedraDefault.entries[extendLabels.indexOf(label)])
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ 网络 / Network ══
                    Text(localized("网络", "Network"), fontWeight = FontWeight.Bold)
                    DropdownField(localized("COD 下载节点", "COD Mirror"), codModeLabels[draft.codMirrorMode.ordinal], codModeLabels) { label ->
                        draft = draft.copy(codMirrorMode = CodMirrorMode.entries[codModeLabels.indexOf(label)])
                    }
                    if (draft.codMirrorMode == CodMirrorMode.FIXED) {
                        val mirrors = CrystallographyOpenDatabase.MIRRORS
                        val mirrorLabels = mirrors.map { it.testUrl.removePrefix("https://").removePrefix("http://").trimEnd('/') }
                        DropdownField(
                            localized("固定节点", "Fixed Mirror"),
                            mirrorLabels[draft.codFixedIndex.coerceIn(0, mirrorLabels.lastIndex)],
                            mirrorLabels,
                        ) { label ->
                            draft = draft.copy(codFixedIndex = mirrorLabels.indexOf(label).coerceAtLeast(0))
                        }
                    }
                    if (draft.codMirrorMode == CodMirrorMode.CUSTOM) {
                        var url by remember(settings) { mutableStateOf(draft.codCustomUrl) }
                        var testing by remember { mutableStateOf(false) }
                        var testResult by remember { mutableStateOf<Boolean?>(null) }
                        val scope = rememberCoroutineScope()
                        OutlinedTextField(url, { url = it; draft = draft.copy(codCustomUrl = it); testResult = null }, label = { Text(localized("自定义节点 URL", "Custom Mirror URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ 显示 / Display ══
                    Text(localized("显示", "Display"), fontWeight = FontWeight.Bold)
                    DropdownField(localized("导出图片质量", "Export Quality"), qualityLabels[draft.exportQuality.ordinal], qualityLabels) { label ->
                        draft = draft.copy(exportQuality = ExportQuality.entries[qualityLabels.indexOf(label)])
                    }
                    Tog(draft.exportShowAxes, { draft = draft.copy(exportShowAxes = it) }, localized("导出时显示坐标轴", "Export Show Axes"))
                    Tog(draft.exportShowMeasurements, { draft = draft.copy(exportShowMeasurements = it) }, localized("导出时显示测量结果", "Export Show Measurements"))
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ Restore Defaults / Actions ══
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { draft = SettingsValues.defaults() }) {
                            Text(localized("恢复默认设置", "Restore Defaults"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // Per v0.8.30: frozen draft — only Save applies the changes.
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(localized("取消", "Cancel")) }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { apply(); onDismiss() }) { Text(localized("保存", "Save")) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
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
