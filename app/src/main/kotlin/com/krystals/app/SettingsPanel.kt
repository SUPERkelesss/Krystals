package com.krystals.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krystals.app.ui.ThemeMode
import kotlinx.coroutines.launch

/**
 * Per v0.7.0: user preferences dialog. Window style matches the Appearance dialog
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
    // Per v0.7.0: settings are frozen on open; only Save applies them.
    var draft by remember { mutableStateOf(settings) }
    val apply = { onChange(draft) }
    // Per v0.7.0: restore-defaults needs confirmation before it touches the frozen draft.
    var resetConfirmOpen by remember { mutableStateOf(false) }
    // Per v0.7.0: custom-mirror test state lives at panel level so Save can validate it;
    // Save is blocked (with a hint line) until a CUSTOM mirror passes its test.
    var customTestOk by remember { mutableStateOf<Boolean?>(null) }
    var saveHint by remember { mutableStateOf<String?>(null) }
    // Label mapping helpers (localized() is @Composable, so these live in composition).
    // Per v0.7.0: language option labels follow the UI language (Chinese UI shows 中文,
    // English UI shows Chinese) — was hardcoded to Chinese which leaked into English UI.
    val languageLabels = listOf(localized("中文", "Chinese"), "English")
    val themeLabels = ThemeMode.entries.map { mode -> when (mode) {
        ThemeMode.SYSTEM -> localized("跟随系统", "System")
        ThemeMode.DARK -> localized("深色", "Dark")
        ThemeMode.LIGHT -> localized("浅色", "Light")
    } }
    val bondRuleLabels = listOf(
        // Per v0.7.0: vdW-radius option removed.
        localized("自动", "Auto"), localized("智能离子", "Ionic"),
        localized("键合半径", "Bonding"),
    )
    val extendLabels = listOf(localized("全部", "All"), localized("仅金属", "Metals"), localized("从不", "Never"))
    // Per v0.7.0: shortened option labels.
    val codModeLabels = listOf(
        localized("自动", "Auto"), localized("固定节点", "Fixed"), localized("自定义", "Custom"),
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
                    // Per v0.7.0: only Language and Theme keep icons; the rest are plain.
                    SegmentedSetting(Icons.Default.Translate, localized("语言", "Language"), languageLabels, if (languageLabel(draft.language) == languageLabels[0]) 0 else 1) { index ->
                        draft = draft.copy(language = if (index == 0) "zh" else "en")
                    }
                    SegmentedSetting(Icons.Default.DarkMode, localized("主题", "Theme"), themeLabels, ThemeMode.entries.indexOf(draft.theme).coerceIn(0, themeLabels.lastIndex)) { index ->
                        draft = draft.copy(theme = ThemeMode.entries[index])
                    }
                    Tog(draft.autoCheckUpdate, { draft = draft.copy(autoCheckUpdate = it) }, localized("自动检查更新", "Auto Check Update"))
                    LabeledSliderSetting(localized("悬浮球收起不透明度", "Collapsed FAB Transparency"), draft.ballCollapsedAlpha) { draft = draft.copy(ballCollapsedAlpha = it) }
                    Tog(draft.showLockButton, { draft = draft.copy(showLockButton = it) }, localized("显示锁定按键", "Show Lock Button"))
                    Tog(draft.showLegend, { draft = draft.copy(showLegend = it) }, localized("显示图例", "Show Legend"))
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ 文件处理 / File Handling ══
                    Text(localized("文件处理", "File Handling"), fontWeight = FontWeight.Bold)
                    Tog(draft.autoBondRules, { draft = draft.copy(autoBondRules = it) }, localized("自动计算键规则", "Auto Compute Bond Rules"))
                    if (draft.autoBondRules) {
                        SegmentedSetting(null, localized("自动应用的键规则", "Auto Bond Rule Mode"), bondRuleLabels, draft.bondRuleMode.ordinal.coerceIn(0, bondRuleLabels.lastIndex)) { index ->
                            draft = draft.copy(bondRuleMode = BondRuleMode.entries[index])
                        }
                        // Per v0.7.0: 自动计算氢键仅随"自动计算键规则"显示。
                        Tog(draft.autoComputeHbonds, { draft = draft.copy(autoComputeHbonds = it) }, localized("自动计算氢键", "Auto Compute H-Bonds"))
                    }
                    Tog(draft.autoConvertCell, { draft = draft.copy(autoConvertCell = it) }, localized("素晶胞自动转正当晶胞", "Auto Convert Cell"))
                    Tog(draft.defaultShowBonds, { draft = draft.copy(defaultShowBonds = it) }, localized("默认显示所有化学键", "Default Show Bonds"))
                    if (draft.defaultShowBonds) {
                        // Per v0.7.0: 以下三项仅随"默认显示所有化学键"关联显示。
                        Tog(draft.defaultShowHbonds, { draft = draft.copy(defaultShowHbonds = it) }, localized("默认显示所有氢键", "Default Show H-Bonds"))
                        SegmentedSetting(null, localized("默认延伸化学键", "Default Extend Bonds"), extendLabels, draft.defaultExtendBonds.ordinal) { index ->
                            draft = draft.copy(defaultExtendBonds = ExtendBondsDefault.entries[index])
                        }
                        Tog(draft.defaultMoleculeExtend, { draft = draft.copy(defaultMoleculeExtend = it) }, localized("分子晶体默认按分子延伸", "Molecule Extend by Default"))
                    }
                    SegmentedSetting(null, localized("默认显示多面体", "Default Polyhedra"), extendLabels, draft.defaultPolyhedra.ordinal) { index ->
                        draft = draft.copy(defaultPolyhedra = PolyhedraDefault.entries[index])
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ 网络 / Network ══
                    Text(localized("网络", "Network"), fontWeight = FontWeight.Bold)
                    SegmentedSetting(null, localized("COD 下载节点", "COD Mirror"), codModeLabels, draft.codMirrorMode.ordinal) { index ->
                        draft = draft.copy(codMirrorMode = CodMirrorMode.entries[index])
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
                        val scope = rememberCoroutineScope()
                        // Per v0.7.0: test button sits on the right of the URL field, and is a
                        // highlighted Button; the result feeds the panel-level customTestOk gate.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(url, { url = it; draft = draft.copy(codCustomUrl = it); customTestOk = null; saveHint = null }, label = { Text(localized("自定义节点 URL", "Custom Mirror URL")) }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !testing && url.isNotBlank(),
                                onClick = {
                                    testing = true
                                    customTestOk = null
                                    saveHint = null
                                    scope.launch {
                                        customTestOk = CrystallographyOpenDatabase.testCustomMirror(url)
                                        testing = false
                                    }
                                },
                            ) { Text(if (testing) localized("测试中...", "Testing...") else localized("测试", "Test")) }
                        }
                        customTestOk?.let { ok ->
                            Text(
                                if (ok) localized("✓ 节点可用，已沿用", "✓ Mirror reachable") else localized("✗ 节点不可用", "✗ Mirror unreachable"),
                                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    // ══ 显示 / Display ══
                    Text(localized("显示", "Display"), fontWeight = FontWeight.Bold)
                    Tog(
                        draft.showSecondaryExtendBonds,
                        { draft = draft.copy(showSecondaryExtendBonds = it) },
                        localized("拓展键时显示二级成键", "Show Secondary Bonds When Extending"),
                    )
                    SegmentedSetting(null, localized("导出图片质量", "Export Quality"), qualityLabels, draft.exportQuality.ordinal) { index ->
                        draft = draft.copy(exportQuality = ExportQuality.entries[index])
                    }
                    ExportBackgroundSetting(
                        background = draft.exportBackground,
                        customArgb = draft.exportCustomBackgroundArgb,
                        onBackgroundChange = { draft = draft.copy(exportBackground = it) },
                        onCustomColorChange = { draft = draft.copy(exportBackground = ExportBackground.CUSTOM, exportCustomBackgroundArgb = it) },
                    )
                    Tog(draft.exportShowAxes, { draft = draft.copy(exportShowAxes = it) }, localized("导出时显示坐标轴", "Export Show Axes"))
                    Tog(draft.exportShowMeasurements, { draft = draft.copy(exportShowMeasurements = it) }, localized("导出时显示测量结果", "Export Show Measurements"))
                    Spacer(Modifier.height(4.dp))
                }
                // Per v0.7.0: footer pinned below the scroll area (Cancel/Save stay visible).
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { resetConfirmOpen = true }) {
                        Text(localized("恢复默认设置", "Restore Defaults"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val hintTestFailed = localized("自定义节点测试未通过", "Custom mirror test failed")
                val hintNotConfigured = localized("自定义节点未配置！", "Custom mirror not configured!")
                Row(Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(localized("取消", "Cancel")) }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        // Per v0.7.0: a CUSTOM mirror must have passed its test before saving.
                        if (draft.codMirrorMode == CodMirrorMode.CUSTOM && customTestOk != true) {
                            saveHint = if (customTestOk == false) hintTestFailed else hintNotConfigured
                        } else {
                            saveHint = null
                            onDismiss(); apply()
                        }
                    }) { Text(localized("保存", "Save")) }
                }
                saveHint?.let { hint ->
                    Text(
                        hint,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
    // Per v0.7.0: confirm before restoring defaults (mirrors the Appearance dialog pattern).
    if (resetConfirmOpen) {
        AlertDialog(
            onDismissRequest = { resetConfirmOpen = false },
            title = { Text(localized("确认", "Confirm")) },
            text = { Text(localized("确认将偏好设置恢复为默认吗？", "Reset all preferences to defaults?")) },
            confirmButton = { TextButton(onClick = { resetConfirmOpen = false; onRestoreDefaults?.invoke(); draft = SettingsValues.defaults() }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { resetConfirmOpen = false }) { Text(localized("取消", "Cancel")) } },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExportBackgroundSetting(
    background: ExportBackground,
    customArgb: Long,
    onBackgroundChange: (ExportBackground) -> Unit,
    onCustomColorChange: (Long) -> Unit,
) {
    var colorPickerOpen by remember { mutableStateOf(false) }
    Text(localized("图片导出背景色", "Export Background"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow(verticalArrangement = Arrangement.Center) {
        ExportBackgroundOption(
            label = localized("透明", "Transparent"),
            selected = background == ExportBackground.TRANSPARENT,
            onClick = { onBackgroundChange(ExportBackground.TRANSPARENT) },
        ) {
            Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
                val cell = size.minDimension / 4f
                for (x in 0..3) for (y in 0..3) {
                    drawRect(if ((x + y) % 2 == 0) Color.White else Color.LightGray, topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell), size = androidx.compose.ui.geometry.Size(cell, cell))
                }
            }
        }
        ExportBackgroundOption(
            label = localized("跟随显示", "Follow Display"),
            selected = background == ExportBackground.FOLLOW_DISPLAY,
            onClick = { onBackgroundChange(ExportBackground.FOLLOW_DISPLAY) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Color.Black, 90f, 180f, true, size = size)
                drawArc(Color.White, 270f, 180f, true, size = size)
            }
        }
        listOf(
            ExportBackground.BLACK to (Color.Black to localized("黑色", "Black")),
            ExportBackground.WHITE to (Color.White to localized("白色", "White")),
        ).forEach { (option, colorAndLabel) ->
            ExportBackgroundOption(colorAndLabel.second, background == option, { onBackgroundChange(option) }) {
                Box(Modifier.fillMaxSize().background(colorAndLabel.first, CircleShape))
            }
        }
        ExportBackgroundOption(
            label = localized("自定义", "Custom"),
            selected = background == ExportBackground.CUSTOM,
            onClick = { colorPickerOpen = true },
        ) {
            Box(Modifier.fillMaxSize().background(Brush.sweepGradient(RAINBOW_SWEEP_COLORS), CircleShape))
        }
    }
    if (colorPickerOpen) {
        ColorPickerDialog(
            initialArgb = customArgb,
            onDismiss = { colorPickerOpen = false },
            onColorSelected = { color -> onCustomColorChange(color); colorPickerOpen = false },
        )
    }
}

@Composable
private fun ExportBackgroundOption(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Box(
            Modifier.size(40.dp)
                .then(if (selected) Modifier.border(2.dp, Color(com.krystals.app.ui.AppPalette.BRAND_DEEP), CircleShape) else Modifier)
                .clickable(onClick = onClick),
        ) { content() }
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable private fun Tog(value: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable private fun LabeledSliderSetting(label: String, value: Float, onChange: (Float) -> Unit) {
    // Per v0.7.0: value merged into the label line: "label: 45%". Per v0.7.0: no stray '$'
    // and the slider range is fixed to 0..1 (the only caller).
    Text("$label: ${"%.0f%%".format(value * 100f)}", style = MaterialTheme.typography.bodyMedium)
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
}

/** Per v0.7.0: single-choice setting as a segmented button row; icon optional (Language/Theme only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedSetting(
    icon: ImageVector?,
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(option, maxLines = 1) }
        }
    }
}
