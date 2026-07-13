@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.krystals.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krystals.core.AtomSite
import com.krystals.core.BondColorMode
import com.krystals.core.BondRule
import com.krystals.core.CrystalEditor
import com.krystals.core.CrystalStructure
import com.krystals.core.EditCommand
import com.krystals.core.Expansion
import com.krystals.core.ExpressionParser
import com.krystals.core.FrameMode
import com.krystals.core.LineStyle
import com.krystals.core.PeriodicTable
import com.krystals.core.SpaceGroupCatalog
import com.krystals.core.UnitCell
import com.krystals.core.Vec3
import kotlin.math.ceil
import kotlin.math.max

private enum class EditorTab { BASIC, ATOMS, BONDS, EXPANSION }

@Composable
fun EditorPanel(
    tab: DocumentTab,
    onDismiss: () -> Unit,
    onStructure: (CrystalStructure) -> Unit,
    onMessage: (String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(if (tab.editingSiteId != null) EditorTab.ATOMS else EditorTab.BASIC) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
            ),
        )
        Surface(
            tonalElevation = 8.dp,
            modifier = if (landscape) Modifier.fillMaxHeight().width(430.dp).align(Alignment.CenterEnd)
            else Modifier.fillMaxWidth().fillMaxHeight(0.62f).align(Alignment.BottomCenter),
        ) {
            Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        EditorTab.BASIC to stringResource(R.string.basic_info), EditorTab.ATOMS to stringResource(R.string.atoms),
                        EditorTab.BONDS to stringResource(R.string.bonds), EditorTab.EXPANSION to stringResource(R.string.expand_cell),
                    ).forEach { (kind, label) -> FilterChip(selectedTab == kind, onClick = { selectedTab = kind }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                    Spacer(Modifier.weight(1f)); IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }
                when (selectedTab) {
                    EditorTab.BASIC -> BasicEditor(tab, onStructure, onMessage)
                    EditorTab.ATOMS -> AtomEditor(tab, onDismiss, onStructure, onMessage)
                    EditorTab.BONDS -> BondEditor(tab, onStructure, onMessage)
                    EditorTab.EXPANSION -> ExpansionEditor(tab, onMessage)
                }
            }
        }
    }
}

@Composable
private fun BasicEditor(tab: DocumentTab, onStructure: (CrystalStructure) -> Unit, onMessage: (String) -> Unit) {
    val currentGroup = SpaceGroupCatalog.find(tab.structure.spaceGroupName) ?: SpaceGroupCatalog.all.first()
    var system by remember(tab.structure.spaceGroupName) { mutableStateOf(currentGroup.crystalSystem) }
    var pointGroup by remember(tab.structure.spaceGroupName) { mutableStateOf(currentGroup.pointGroup) }
    val systems = SpaceGroupCatalog.all.map { it.crystalSystem }.distinct()
    val points = SpaceGroupCatalog.all.filter { it.crystalSystem == system }.map { it.pointGroup }.distinct()
    val groups = SpaceGroupCatalog.all.filter { it.crystalSystem == system && it.pointGroup == pointGroup }
    val cell = tab.structure.cell
    var a by remember(cell) { mutableStateOf(cell.a.toString()) }; var b by remember(cell) { mutableStateOf(cell.b.toString()) }
    var c by remember(cell) { mutableStateOf(cell.c.toString()) }; var alpha by remember(cell) { mutableStateOf(cell.alpha.toString()) }
    var beta by remember(cell) { mutableStateOf(cell.beta.toString()) }; var gamma by remember(cell) { mutableStateOf(cell.gamma.toString()) }
    val systemName = currentGroup.crystalSystem
    val lockB = systemName in setOf("Tetragonal", "Trigonal", "Hexagonal", "Cubic")
    val lockC = systemName == "Cubic"
    val lockAlpha = systemName != "Triclinic"
    val lockBeta = systemName !in setOf("Triclinic", "Monoclinic")
    val lockGamma = systemName !in setOf("Triclinic")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedTextField(tab.name, onValueChange = { tab.name = it; tab.dirty = true }, label = { Text("File name / 文件名") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        DropdownField("Crystal system / 晶系", system, systems) { selected -> system = selected; pointGroup = SpaceGroupCatalog.all.first { it.crystalSystem == selected }.pointGroup }
        DropdownField("Point group / 点群", pointGroup, points) { pointGroup = it }
        DropdownField("Space group / 空间群", tab.structure.spaceGroupName, groups.map { "${it.number}  ${it.symbol}" }) { selection ->
            val symbol = selection.substringAfter("  ")
            runCatching { CrystalEditor.apply(tab.structure, EditCommand.SetSpaceGroup(symbol)).structure }.onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid space group") }
        }
        Text("Cell parameters / 晶胞参数", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row { CellField("a (Å)", a, { a = it }, true, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); CellField("b (Å)", b, { b = it }, !lockB, Modifier.weight(1f)) }
        Row { CellField("c (Å)", c, { c = it }, !lockC, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); CellField("α (°)", alpha, { alpha = it }, !lockAlpha, Modifier.weight(1f)) }
        Row { CellField("β (°)", beta, { beta = it }, !lockBeta, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); CellField("γ (°)", gamma, { gamma = it }, !lockGamma, Modifier.weight(1f)) }
        Button(onClick = {
            runCatching {
                UnitCell(eval(a), eval(if (lockB) a else b), eval(if (lockC) a else c), eval(alpha), eval(beta), eval(gamma))
            }.mapCatching { CrystalEditor.apply(tab.structure, EditCommand.SetCell(it)).structure }
                .onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid cell parameters") }
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Apply cell / 应用晶胞") }
    }
}

@Composable
private fun CellField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = modifier.padding(vertical = 3.dp))
}

@Composable
private fun AtomEditor(tab: DocumentTab, onDismiss: () -> Unit, onStructure: (CrystalStructure) -> Unit, onMessage: (String) -> Unit) {
    var atomDialog by remember { mutableStateOf<AtomSite?>(null) }
    var newElement by remember { mutableStateOf<String?>(null) }
    var periodicOpen by remember { mutableStateOf(false) }
    var transformOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tab.editingSiteId) { tab.editingSiteId?.let { id -> atomDialog = tab.structure.sites.firstOrNull { it.id == id }; tab.editingSiteId = null } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { periodicOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(" New / 新建") }
            OutlinedButton(onClick = { tab.atomEditMode = AtomEditMode.MODIFY_NEXT; onDismiss() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Edit, null); Text(" Modify / 修改") }
            OutlinedButton(onClick = { tab.atomEditMode = AtomEditMode.DELETE_NEXT; onDismiss() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Text(" Delete / 删除") }
        }
        OutlinedButton(onClick = { transformOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("3×3 Transform / 变换矩阵") }
        Text("Choose Modify/Delete, then tap an atom in the viewer. / 选择模式后点击原子。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(tab.structure.sites, key = { it.id }) { site ->
                Row(Modifier.fillMaxWidth().clickable { atomDialog = site }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).background(colorFromArgb(PeriodicTable.vestaArgb(site.element)), androidx.compose.foundation.shape.CircleShape))
                    Text("${site.label}  ${site.element}   (${fmt(site.fractional.x)}, ${fmt(site.fractional.y)}, ${fmt(site.fractional.z)})", modifier = Modifier.weight(1f).padding(start = 10.dp))
                    IconButton(onClick = { onStructure(CrystalEditor.apply(tab.structure, EditCommand.DeleteAtom(site.id)).structure) }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }
    if (periodicOpen) PeriodicTableDialog(onDismiss = { periodicOpen = false }) { element -> periodicOpen = false; newElement = element }
    newElement?.let { element -> AtomDialog(null, element, onDismiss = { newElement = null }) { label, chosen, frac, occupancy ->
        runCatching { CrystalEditor.apply(tab.structure, EditCommand.AddAtom(chosen, label, frac, occupancy)) }
            .onSuccess { onStructure(it.structure); it.warnings.forEach(onMessage); newElement = null }.onFailure { onMessage(it.message ?: "Invalid atom") }
    } }
    atomDialog?.let { site -> AtomDialog(site, site.element, onDismiss = { atomDialog = null }) { label, chosen, frac, occupancy ->
        runCatching { CrystalEditor.apply(tab.structure, EditCommand.UpdateAtom(site.id, chosen, label, frac, occupancy)) }
            .onSuccess { onStructure(it.structure); it.warnings.forEach(onMessage); atomDialog = null }.onFailure { onMessage(it.message ?: "Invalid atom") }
    } }
    if (transformOpen) TransformDialog(onDismiss = { transformOpen = false }) { rows ->
        runCatching { CrystalEditor.apply(tab.structure, EditCommand.Transform(rows)).structure }
            .onSuccess { onStructure(it); transformOpen = false }.onFailure { onMessage(it.message ?: "Invalid transformation") }
    }
}

@Composable
private fun TransformDialog(onDismiss: () -> Unit, onApply: (List<List<Int>>) -> Unit) {
    val values = remember { List(9) { index -> mutableStateOf(if (index % 4 == 0) "1" else "0") } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("3×3 Transform / 变换矩阵") },
        text = { Column { repeat(3) { row -> Row { repeat(3) { column ->
            OutlinedTextField(values[row * 3 + column].value, { values[row * 3 + column].value = it }, singleLine = true, modifier = Modifier.weight(1f).padding(3.dp))
        } } } } },
        confirmButton = { TextButton(onClick = {
            val rows = List(3) { r -> List(3) { c ->
                val number = eval(values[r * 3 + c].value)
                require(kotlin.math.abs(number - kotlin.math.round(number)) < 1e-9) { "Matrix entries must be integers" }
                number.toInt()
            } }
            onApply(rows)
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AtomDialog(site: AtomSite?, initialElement: String, onDismiss: () -> Unit, onApply: (String, String, Vec3, Double) -> Unit) {
    var label by remember { mutableStateOf(site?.label ?: initialElement) }; var element by remember { mutableStateOf(initialElement) }
    var x by remember { mutableStateOf(site?.fractional?.x?.toString() ?: "0") }; var y by remember { mutableStateOf(site?.fractional?.y?.toString() ?: "0") }
    var z by remember { mutableStateOf(site?.fractional?.z?.toString() ?: "0") }; var occupancy by remember { mutableStateOf(site?.occupancy?.toString() ?: "1") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (site == null) "New atom / 新建原子" else "Modify atom / 修改原子") }, text = { Column {
        OutlinedTextField(element, { element = it }, label = { Text("Element / 元素") }); OutlinedTextField(label, { label = it }, label = { Text("Label / 标签") })
        Row { CellField("x", x, { x = it }, true, Modifier.weight(1f)); CellField("y", y, { y = it }, true, Modifier.weight(1f)); CellField("z", z, { z = it }, true, Modifier.weight(1f)) }
        OutlinedTextField(occupancy, { occupancy = it }, label = { Text("Occupancy / 占据率") })
    } }, confirmButton = { TextButton(onClick = { runCatching { onApply(label, element, Vec3(eval(x), eval(y), eval(z)), eval(occupancy)) } }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PeriodicTableDialog(onDismiss: () -> Unit, onElement: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Periodic table / 元素周期表") }, text = {
        LazyVerticalGrid(GridCells.Fixed(8), modifier = Modifier.fillMaxWidth().height(390.dp)) {
            items(PeriodicTable.symbols) { symbol -> TextButton(onClick = { onElement(symbol) }, modifier = Modifier.size(48.dp)) { Text(symbol) } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun BondEditor(tab: DocumentTab, onStructure: (CrystalStructure) -> Unit, onMessage: (String) -> Unit) {
    val sites = tab.structure.sites
    if (sites.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Add atoms first / 请先添加原子") }; return }
    var a by remember(tab.structure) { mutableStateOf(sites.first().id) }; var b by remember(tab.structure) { mutableStateOf(sites.first().id) }
    var minDistance by remember { mutableFloatStateOf(0.1f) }; var maxDistance by remember { mutableFloatStateOf(2.5f) }
    val key = remember(a, b) { listOf(a, b).sorted().joinToString("\u0000") }
    LaunchedEffect(key, tab.structure.bondRules) {
        val rule = tab.structure.bondRules.firstOrNull { it.key == key }
        val siteA = sites.first { it.id == a }; val siteB = sites.first { it.id == b }
        minDistance = rule?.minAngstrom?.toFloat() ?: 0.1f
        maxDistance = rule?.maxAngstrom?.toFloat() ?: (PeriodicTable.covalentRadius(siteA.element) + PeriodicTable.covalentRadius(siteB.element) + 0.45).toFloat()
    }
    val sliderMax = max(5f, ceil(maxDistance + 1f))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Unordered site-pair rule / 无序晶位对", fontWeight = FontWeight.Bold)
        DropdownField("Site A / 晶位 A", sites.first { it.id == a }.label, sites.map { it.label }) { label -> a = sites.first { it.label == label }.id }
        DropdownField("Site B / 晶位 B", sites.first { it.id == b }.label, sites.map { it.label }) { label -> b = sites.first { it.label == label }.id }
        DistanceControl("Minimum / 最小 (Å)", minDistance, 0f..sliderMax) { minDistance = it.coerceAtMost(maxDistance) }
        DistanceControl("Maximum / 最大 (Å)", maxDistance, 0f..sliderMax) { maxDistance = it.coerceAtLeast(minDistance) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching { CrystalEditor.apply(tab.structure, EditCommand.SetBondRule(BondRule(a, b, minDistance.toDouble(), maxDistance.toDouble()))).structure }
                    .onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid bond rule") }
            }, modifier = Modifier.weight(1f)) { Text("Save rule / 保存规则") }
            OutlinedButton(onClick = { onStructure(CrystalEditor.apply(tab.structure, EditCommand.RemoveBondRule(key)).structure) }, modifier = Modifier.weight(1f)) { Text("Auto / 自动") }
        }
        Text("Custom rules are stored in _krystals_bond_rule_* CIF tags.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun DistanceControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.3f".format(value)) }
    Text(label, modifier = Modifier.padding(top = 12.dp)); OutlinedTextField(text, { input -> text = input; input.toFloatOrNull()?.let(onValue) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Slider(value, onValue, valueRange = range)
}

@Composable
private fun ExpansionEditor(tab: DocumentTab, onMessage: (String) -> Unit) {
    var x by remember(tab.expansion) { mutableStateOf(tab.expansion.x.toString()) }; var y by remember(tab.expansion) { mutableStateOf(tab.expansion.y.toString()) }; var z by remember(tab.expansion) { mutableStateOf(tab.expansion.z.toString()) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Display supercell / 显示扩胞", fontWeight = FontWeight.Bold)
        Row { CellField("x", x, { x = it }, true, Modifier.weight(1f)); CellField("y", y, { y = it }, true, Modifier.weight(1f)); CellField("z", z, { z = it }, true, Modifier.weight(1f)) }
        Button(onClick = {
            runCatching {
                val expansion = Expansion(eval(x).toInt(), eval(y).toInt(), eval(z).toInt())
                val base = com.krystals.core.CrystalEngine.expandAsymmetricUnit(tab.structure).size
                require(base.toLong() * expansion.multiplier <= com.krystals.core.CrystalEngine.MAX_RENDERED_ATOMS) { "100,000 atom limit exceeded" }
                tab.expansion = expansion
            }.onFailure { onMessage(it.message ?: "Invalid expansion") }
        }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Apply / 应用") }
    }
}

@Composable
fun AppearanceDialog(tab: DocumentTab, onDismiss: () -> Unit, onApplied: (com.krystals.core.ViewerAppearance) -> Unit = {}) {
    var appearance by remember { mutableStateOf(tab.appearance) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Appearance / 调整外观") }, text = {
        Column(Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState())) {
            Text("Background / 背景色", fontWeight = FontWeight.Bold)
            FlowRow { listOf(0xFF101014, 0xFFFFFFFF, 0xFF000000, 0xFF2B183A, 0xFFEEEEF2).forEach { argb -> Box(Modifier.padding(5.dp).size(38.dp).background(colorFromArgb(argb), androidx.compose.foundation.shape.CircleShape).clickable { appearance = appearance.copy(backgroundArgb = argb) }) } }
            ToggleRow("Reflection / 原子反光", appearance.reflectionEnabled) { appearance = appearance.copy(reflectionEnabled = it) }
            if (appearance.reflectionEnabled) {
                LabeledSlider("Azimuth / 角度", appearance.lightAzimuth, 0f..360f) { appearance = appearance.copy(lightAzimuth = it) }
                LabeledSlider("Elevation / 高度", appearance.lightElevation, -90f..90f) { appearance = appearance.copy(lightElevation = it) }
                LabeledSlider("Intensity / 强度", appearance.lightIntensity, 0f..1f) { appearance = appearance.copy(lightIntensity = it) }
                LabeledSlider("Diffusion / 扩散", appearance.diffusion, 0f..1f) { appearance = appearance.copy(diffusion = it) }
            }
            Text("Frame / 框线", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            DropdownField("Mode / 模式", appearance.frameMode.name, FrameMode.entries.map { it.name }) { appearance = appearance.copy(frameMode = FrameMode.valueOf(it)) }
            DropdownField("Line / 线型", appearance.lineStyle.name, LineStyle.entries.map { it.name }) { appearance = appearance.copy(lineStyle = LineStyle.valueOf(it)) }
            LabeledSlider("Bond radius / 键半径", appearance.bondRadius, 0.02f..0.4f) { appearance = appearance.copy(bondRadius = it) }
            DropdownField("Bond color / 键颜色", appearance.bondColorMode.name, BondColorMode.entries.map { it.name }) { appearance = appearance.copy(bondColorMode = BondColorMode.valueOf(it)) }
            if (appearance.bondColorMode == BondColorMode.UNICOLOR) FlowRow { listOf(0xFF9A90A0, 0xFF9966CC, 0xFFFFFFFF, 0xFF333333).forEach { argb -> Box(Modifier.padding(5.dp).size(38.dp).background(colorFromArgb(argb), androidx.compose.foundation.shape.CircleShape).clickable { appearance = appearance.copy(uniformBondArgb = argb) }) } }
        }
    }, confirmButton = { TextButton(onClick = { tab.appearance = appearance; onApplied(appearance); onDismiss() }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); androidx.compose.material3.Switch(checked, onChecked) } }
@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) { Text("$label  ${"%.2f".format(value)}"); Slider(value, onValue, valueRange = range) }

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().clickable { open = true })
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onValue(option) }) } }
    }
}

private fun eval(value: String) = ExpressionParser(value).evaluate()
private fun fmt(value: Double) = "%.4f".format(value)
private fun colorFromArgb(value: Long) = Color(value)
