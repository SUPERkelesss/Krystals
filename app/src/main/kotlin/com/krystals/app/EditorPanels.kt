@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.ExpressionParser
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.style.RenderPalette
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.BondColorMode
import com.krystals.renderer.core.style.FrameMode
import com.krystals.renderer.core.style.LineStyle
import com.krystals.renderer.core.style.ViewerAppearance
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditorTab { BASIC, ATOMS, BONDS, EXPANSION }

@Composable
fun EditorPanel(
    tab: DocumentTab,
    onDismiss: () -> Unit,
    onStructure: (EditResult) -> Unit,
    onMessage: (String) -> Unit,
    onRunBondComputation: ((suspend () -> EditResult?) -> Unit)? = null,
) {
    var selectedTab by remember { mutableStateOf(if (tab.editingSiteId != null) EditorTab.ATOMS else EditorTab.BASIC) }
    // Per v0.2.3: resizable panel (mirrors DisplayPanel).
    var panelRatio by remember { mutableStateOf(0.62f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss,
            ),
        )
        val panelModifier = if (landscape) {
            Modifier.fillMaxHeight().fillMaxWidth(panelRatio).align(Alignment.CenterEnd)
        } else {
            Modifier.fillMaxWidth().fillMaxHeight(panelRatio).align(Alignment.BottomCenter)
        }
        Surface(
            tonalElevation = 8.dp,
            modifier = panelModifier,
        ) {
            // Per v0.3.3: in landscape the drag handle is a vertical bar on the left, so the panel
            // content must sit beside it in a Row (a Column with a fillMaxHeight first child would
            // leave no height for the tabs/content — the cause of the blank landscape panel).
            val handleModifier = if (landscape) {
                Modifier.fillMaxHeight().width(12.dp)
            } else {
                Modifier.fillMaxWidth().height(12.dp)
            }
            val handle = @Composable {
                Box(
                    Modifier
                        .pointerInput(landscape) {
                            detectDragGestures { change, amount ->
                                change.consume()
                                if (landscape) {
                                    panelRatio = (panelRatio - amount.x / widthPx).coerceIn(0.2f, 0.95f)
                                } else {
                                    panelRatio = (panelRatio - amount.y / heightPx).coerceIn(0.2f, 0.95f)
                                }
                            }
                        }
                        .then(handleModifier)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            val content = @Composable {
                Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            EditorTab.BASIC to stringResource(R.string.basic_info), EditorTab.ATOMS to stringResource(R.string.atoms),
                            EditorTab.BONDS to stringResource(R.string.bonds), EditorTab.EXPANSION to stringResource(R.string.expand_cell),
                        ).forEach { (kind, label) -> FilterChip(selectedTab == kind, onClick = { selectedTab = kind }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                        Spacer(Modifier.weight(1f)); IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    }
                    when (selectedTab) {
                        EditorTab.BASIC -> BasicEditor(tab, onStructure, onMessage, onRunBondComputation)
                        EditorTab.ATOMS -> AtomEditor(tab, onDismiss, onStructure, onMessage, onRunBondComputation)
                        EditorTab.BONDS -> BondEditor(tab, onStructure, onMessage)
                        EditorTab.EXPANSION -> ExpansionEditor(tab, onMessage, onRunBondComputation)
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    handle()
                    Column(Modifier.weight(1f)) { content() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    handle()
                    content()
                }
            }
        }
    }
}

@Composable
private fun BasicEditor(tab: DocumentTab, onStructure: (EditResult) -> Unit, onMessage: (String) -> Unit, onRunBondComputation: ((suspend () -> EditResult?) -> Unit)? = null) {
    val currentGroup = SpaceGroupCatalog.find(tab.structure.spaceGroup.symbol) ?: SpaceGroupCatalog.all.first()
    var system by remember(tab.structure.spaceGroup.symbol) { mutableStateOf(currentGroup.crystalSystem.orEmpty()) }
    var pointGroup by remember(tab.structure.spaceGroup.symbol) { mutableStateOf(currentGroup.pointGroup.orEmpty()) }
    val systems = SpaceGroupCatalog.all.mapNotNull { it.crystalSystem }.distinct()
    val systemLabels = systems.associateWith { value -> when (value) {
        "Triclinic" -> localized("三斜", "Triclinic")
        "Monoclinic" -> localized("单斜", "Monoclinic")
        "Orthorhombic" -> localized("正交", "Orthorhombic")
        "Tetragonal" -> localized("四方", "Tetragonal")
        "Trigonal" -> localized("三方", "Trigonal")
        "Hexagonal" -> localized("六方", "Hexagonal")
        else -> localized("立方", "Cubic")
    } }
    val points = SpaceGroupCatalog.all.filter { it.crystalSystem == system }.mapNotNull { it.pointGroup }.distinct()
    val groups = SpaceGroupCatalog.all.filter { it.crystalSystem == system && it.pointGroup == pointGroup }
    val cell = tab.structure.lattice
    var a by remember(cell) { mutableStateOf(cell.a.toString()) }; var b by remember(cell) { mutableStateOf(cell.b.toString()) }
    var c by remember(cell) { mutableStateOf(cell.c.toString()) }; var alpha by remember(cell) { mutableStateOf(cell.alpha.toString()) }
    var beta by remember(cell) { mutableStateOf(cell.beta.toString()) }; var gamma by remember(cell) { mutableStateOf(cell.gamma.toString()) }
    val systemName = currentGroup.crystalSystem.orEmpty()
    val lockB = systemName in setOf("Tetragonal", "Trigonal", "Hexagonal", "Cubic")
    val lockC = systemName == "Cubic"
    val lockAlpha = systemName != "Triclinic"
    val lockBeta = systemName !in setOf("Triclinic", "Monoclinic")
    val lockGamma = systemName !in setOf("Triclinic")
    var transformOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedTextField(tab.name, onValueChange = { tab.name = it; tab.dirty = true }, label = { Text(localized("文件名", "File name")) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        DropdownField(localized("晶系", "Crystal system"), systemLabels.getValue(system), systems.map { systemLabels.getValue(it) }) { selectedLabel ->
            val selected = systemLabels.entries.first { it.value == selectedLabel }.key
            // Per v0.3.5: cascading the crystal system resets the point group to that system's first
            // and applies the first space group of the new (system, point group), so cell/sg stay
            // consistent instead of leaving the old space group under a new system.
            system = selected
            val newPointGroup = SpaceGroupCatalog.all.first { it.crystalSystem == selected }.pointGroup.orEmpty()
            pointGroup = newPointGroup
            val newSymbol = SpaceGroupCatalog.all.first { it.crystalSystem == selected && it.pointGroup == newPointGroup }.symbol
            runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetSpaceGroup(newSymbol)) }
                .onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid space group") }
        }
        DropdownField(localized("点群", "Point group"), pointGroup, points) { picked ->
            // Per v0.3.5: changing the point group applies the first space group of the new point
            // group so the displayed space group follows the point group selection.
            pointGroup = picked
            val newSymbol = SpaceGroupCatalog.all.first { it.crystalSystem == system && it.pointGroup == picked }.symbol
            runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetSpaceGroup(newSymbol)) }
                .onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid space group") }
        }
        DropdownField(localized("空间群", "Space group"), tab.structure.spaceGroup.symbol, groups.map { "${it.number}  ${it.symbol}" }) { selection ->
            val symbol = selection.substringAfter("  ")
            runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetSpaceGroup(symbol)) }.onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid space group") }
        }
        Text(localized("晶胞参数", "Cell parameters"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row { CellField("a (Å)", a, { a = it }, true, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); CellField("b (Å)", b, { b = it }, !lockB, Modifier.weight(1f)) }
        Row { CellField("c (Å)", c, { c = it }, !lockC, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); CellField("α (°)", alpha, { alpha = it }, !lockAlpha, Modifier.weight(1f)) }
        Row { CellField("β (°)", beta, { beta = it }, !lockBeta, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); CellField("γ (°)", gamma, { gamma = it }, !lockGamma, Modifier.weight(1f)) }
        Button(onClick = {
            runCatching {
                Lattice(eval(a), eval(if (lockB) a else b), eval(if (lockC) a else c), eval(alpha), eval(beta), eval(gamma))
            }.mapCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetLattice(it)) }
                .onSuccess(onStructure).onFailure { onMessage(it.message ?: "Invalid cell parameters") }
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(localized("应用晶胞", "Apply cell")) }
        if (SpaceGroupCatalog.isRhombohedral(tab.structure.spaceGroup.symbol)) {
            // γ ≈ 120 indicates the hexagonal setting; otherwise treat as already rhombohedral.
            val toRhom = tab.structure.lattice.gamma > 100.0
            OutlinedButton(onClick = {
                runCatching { CrystalEditor.convertHexRhom(tab.structure, tab.bondConfiguration, toRhom) }
                    .onSuccess(onStructure)
                    .onFailure { onMessage(it.message ?: "Conversion failed") }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(localized("六方/菱方晶胞转换", "Hex/Rhombohedral conversion"))
            }
        }
        OutlinedButton(onClick = { transformOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(localized("3×3 变换矩阵", "3×3 Transform")) }
    }
    if (transformOpen) TransformDialog(onDismiss = { transformOpen = false }) { rows, translation ->
        runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.Transform(rows, translation)) }
            .onSuccess { transformed ->
                if (onRunBondComputation != null) onRunBondComputation {
                    CrystalEditor.ensureAutoBondRules(transformed.structure, transformed.bondConfiguration)
                } else onStructure(CrystalEditor.ensureAutoBondRules(transformed.structure, transformed.bondConfiguration))
                transformOpen = false
            }
            .onFailure { onMessage(it.message ?: "Invalid transformation") }
    }
}

@Composable
private fun CellField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = modifier.padding(vertical = 3.dp))
}

@Composable
private fun AtomEditor(tab: DocumentTab, onDismiss: () -> Unit, onStructure: (EditResult) -> Unit, onMessage: (String) -> Unit, onRunBondComputation: ((suspend () -> EditResult?) -> Unit)? = null) {
    var atomDialog by remember { mutableStateOf<Site?>(null) }
    var newElement by remember { mutableStateOf<String?>(null) }
    var periodicOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tab.editingSiteId) { tab.editingSiteId?.let { id -> atomDialog = tab.structure.sites.firstOrNull { it.id == id }; tab.editingSiteId = null } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { periodicOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(localized("新建", "New")) }
            OutlinedButton(onClick = { tab.recordHistory(); tab.atomEditMode = AtomEditMode.MODIFY_NEXT; onDismiss() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Edit, null); Text(localized("修改", "Modify")) }
            OutlinedButton(onClick = { tab.recordHistory(); tab.atomEditMode = AtomEditMode.DELETE_NEXT; onDismiss() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Text(localized("删除", "Delete")) }
        }
        Text(localized("选择修改或删除后，在查看器中点击原子。", "Choose Modify or Delete, then tap an atom in the viewer."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(tab.structure.sites, key = { it.id }) { site ->
                Row(Modifier.fillMaxWidth().clickable { atomDialog = site }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).background(colorFromArgb(RenderPalette.resolveArgb(site.species.symbol, tab.renderConfiguration)), CircleShape))
                    Text("${site.label}  ${site.species.symbol}   (${fmt(site.fractionalCoordinate.x)}, ${fmt(site.fractionalCoordinate.y)}, ${fmt(site.fractionalCoordinate.z)})", modifier = Modifier.weight(1f).padding(start = 10.dp))
                    IconButton(onClick = {
                        val deleted = runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.DeleteAtom(site.id)) }
                            .onFailure { onMessage(it.message ?: "Delete failed") }.getOrNull() ?: return@IconButton
                        // Per v0.5.0: deleting an atom changes bond partners → recompute rules with overlay.
                        if (onRunBondComputation != null) onRunBondComputation {
                            CrystalEditor.ensureAutoBondRules(deleted.structure, deleted.bondConfiguration)
                        } else onStructure(CrystalEditor.ensureAutoBondRules(deleted.structure, deleted.bondConfiguration))
                    }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }
    if (periodicOpen) PeriodicTableDialog(onDismiss = { periodicOpen = false }) { element -> periodicOpen = false; newElement = element }
    newElement?.let { element -> AtomDialog(null, element, onDismiss = { newElement = null }) { label, chosen, frac, occupancy ->
        runCatching {
            CrystalEditor.apply(
                tab.structure,
                tab.bondConfiguration,
                EditCommand.AddAtom(Species(chosen), label, frac, occupancy),
            )
        }
            .onSuccess {
                // Per v0.5.0: AddAtom already synthesizes rules inside the editor; recompute on a
                // background thread with the overlay when available, else apply synchronously.
                if (onRunBondComputation != null) {
                    onRunBondComputation { CrystalEditor.ensureAutoBondRules(it.structure, it.bondConfiguration) }
                } else onStructure(CrystalEditor.ensureAutoBondRules(it.structure, it.bondConfiguration))
                it.warnings.forEach(onMessage); newElement = null
            }.onFailure { onMessage(it.message ?: "Invalid atom") }
    } }
    atomDialog?.let { site -> AtomDialog(site, site.species.symbol, onDismiss = { atomDialog = null }) { label, chosen, frac, occupancy ->
        runCatching {
            CrystalEditor.apply(
                tab.structure,
                tab.bondConfiguration,
                EditCommand.UpdateAtom(site.id, Species(chosen), label, frac, occupancy),
            )
        }
            .onSuccess { onStructure(it); it.warnings.forEach(onMessage); atomDialog = null }.onFailure { onMessage(it.message ?: "Invalid atom") }
    } }
}

@Composable
private fun TransformDialog(onDismiss: () -> Unit, onApply: (List<List<Int>>, FractionalCoordinate) -> Unit) {
    // 9 matrix entries (default identity) + 3 translation entries (default 0). Per v0.3.3 the
    // translation is a separate column to the right of the matrix; it applies after the linear
    // transform in the new fractional basis and allows fractional values (e.g. 1/4, 1/2).
    val matrixValues = remember { List(9) { index -> mutableStateOf(if (index % 4 == 0) "1" else "0") } }
    val translationValues = remember { List(3) { mutableStateOf("0") } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("3×3 变换矩阵", "3×3 Transform")) },
        text = { Column {
            repeat(3) { row -> Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { column ->
                    OutlinedTextField(matrixValues[row * 3 + column].value, { matrixValues[row * 3 + column].value = it }, singleLine = true, modifier = Modifier.weight(1f).padding(3.dp))
                }
                // Per v0.3.3: the translation column. tx/ty/tz apply after the matrix, in the new basis.
                OutlinedTextField(translationValues[row].value, { translationValues[row].value = it }, singleLine = true, label = { Text("t${row + 1}") }, modifier = Modifier.weight(1f).padding(3.dp))
            } }
            Text(localized("右侧为平移向量（变换后应用，允许分数如 1/4）", "Right column is the translation (applied after transform; fractions like 1/4 allowed)"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
        } },
        confirmButton = { TextButton(onClick = {
            val rows = List(3) { r -> List(3) { c ->
                val number = eval(matrixValues[r * 3 + c].value)
                require(kotlin.math.abs(number - kotlin.math.round(number)) < 1e-9) { "Matrix entries must be integers" }
                number.toInt()
            } }
            val translation = FractionalCoordinate(eval(translationValues[0].value), eval(translationValues[1].value), eval(translationValues[2].value))
            onApply(rows, translation)
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AtomDialog(site: Site?, initialElement: String, onDismiss: () -> Unit, onApply: (String, String, FractionalCoordinate, Double) -> Unit) {
    var label by remember { mutableStateOf(site?.label ?: initialElement) }; var element by remember { mutableStateOf(initialElement) }
    var x by remember { mutableStateOf(site?.fractionalCoordinate?.x?.toString() ?: "0") }; var y by remember { mutableStateOf(site?.fractionalCoordinate?.y?.toString() ?: "0") }
    var z by remember { mutableStateOf(site?.fractionalCoordinate?.z?.toString() ?: "0") }; var occupancy by remember { mutableStateOf(site?.occupancy?.toString() ?: "1") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (site == null) localized("新建原子", "New atom") else localized("修改原子", "Modify atom")) }, text = { Column {
        OutlinedTextField(element, { element = it }, label = { Text(localized("元素", "Element")) }); OutlinedTextField(label, { label = it }, label = { Text(localized("标签", "Label")) })
        Row { CellField("x", x, { x = it }, true, Modifier.weight(1f)); CellField("y", y, { y = it }, true, Modifier.weight(1f)); CellField("z", z, { z = it }, true, Modifier.weight(1f)) }
        OutlinedTextField(occupancy, { occupancy = it }, label = { Text(localized("占据率", "Occupancy")) })
    } }, confirmButton = { TextButton(onClick = { runCatching { onApply(label, element, FractionalCoordinate(eval(x), eval(y), eval(z)), eval(occupancy)) } }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private val periodicTableLayout: List<List<String?>> = listOf(
    listOf("H") + List(16) { null } + listOf("He"),
    listOf("Li", "Be") + List(10) { null } + listOf("B", "C", "N", "O", "F", "Ne"),
    listOf("Na", "Mg") + List(10) { null } + listOf("Al", "Si", "P", "S", "Cl", "Ar"),
    listOf("K", "Ca", "Sc", "Ti", "V", "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn", "Ga", "Ge", "As", "Se", "Br", "Kr"),
    listOf("Rb", "Sr", "Y", "Zr", "Nb", "Mo", "Tc", "Ru", "Rh", "Pd", "Ag", "Cd", "In", "Sn", "Sb", "Te", "I", "Xe"),
    listOf("Cs", "Ba", "*") + listOf("Hf", "Ta", "W", "Re", "Os", "Ir", "Pt", "Au", "Hg", "Tl", "Pb", "Bi", "Po", "At", "Rn"),
    listOf("Fr", "Ra", "**") + listOf("Rf", "Db", "Sg", "Bh", "Hs", "Mt", "Ds", "Rg", "Cn", "Nh", "Fl", "Mc", "Lv", "Ts", "Og"),
)
private val lanthanides = listOf("La", "Ce", "Pr", "Nd", "Pm", "Sm", "Eu", "Gd", "Tb", "Dy", "Ho", "Er", "Tm", "Yb", "Lu")
private val actinides = listOf("Ac", "Th", "Pa", "U", "Np", "Pu", "Am", "Cm", "Bk", "Cf", "Es", "Fm", "Md", "No", "Lr")

@Composable
private fun PeriodicTableDialog(onDismiss: () -> Unit, onElement: (String) -> Unit) {
    val cellWidth = 44.dp
    val cellH = 46.dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("元素周期表", "Periodic table")) },
        text = {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(cellWidth * 18).verticalScroll(rememberScrollState())) {
                    periodicTableLayout.forEach { row ->
                        Row { row.forEach { cell(cellWidth, cellH, it, onElement) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        repeat(2) { Box(Modifier.width(cellWidth).height(cellH)) }
                        lanthanides.forEach { cell(cellWidth, cellH, it, onElement) }
                        Box(Modifier.width(cellWidth).height(cellH))
                    }
                    Row {
                        repeat(2) { Box(Modifier.width(cellWidth).height(cellH)) }
                        actinides.forEach { cell(cellWidth, cellH, it, onElement) }
                        Box(Modifier.width(cellWidth).height(cellH))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun cell(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, symbol: String?, onElement: (String) -> Unit) {
    when (symbol) {
        null -> Box(Modifier.width(w).height(h))
        "*", "**" -> Box(Modifier.width(w).height(h), contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Surface(
            onClick = { onElement(symbol) },
            modifier = Modifier.width(w).height(h).padding(1.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(symbol, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BondEditor(tab: DocumentTab, onStructure: (EditResult) -> Unit, onMessage: (String) -> Unit) {
    val sites = tab.structure.sites
    if (sites.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("请先添加原子", "Add atoms first")) }; return }
    var addOpen by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<BondRule?>(null) }
    var radiiMenuOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var confirmSmartIonic by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Per v0.5.0: smart-ionic unavailable warning + ε hint. localized() is @Composable, so resolve
    // them here in the composable body and reuse inside non-composable lambdas below.
    val unavailableMessage = localized("智能离子规则在该晶体下不可用", "Smart ionic rules are unavailable for this crystal")
    val confirmTitle = localized("确认计算", "Confirm")
    val confirmMessage = localized("当前晶胞原子数过多，计算时间可能较长。确认自动计算化学键规则吗？", "This cell has many atoms; computation may take a while. Recompute bond rules anyway?")
    val computingMessage = localized("计算中...", "Computing...")
    val epsilonHint = localized("max = rA + rB + ε，建议在 0.35–0.45 之间", "max = rA + rB + ε, suggested 0.35–0.45")
    val rules = tab.bondConfiguration.rules
    // Per v0.2.3: hide rules that produce no bond in the current structure (no atom pair within
    // the distance window), not just rules whose sites are gone.
    // Per v0.5.2b: expand + grid once and reuse across all rules so MOF-scale cells don't freeze.
    val bondGrid = remember(tab.structure) {
        val atoms = SymmetryExpander.expand(tab.structure)
        BondGrid(atoms, tab.structure, BondRuleMatching.estimateCellSize(tab.structure)) to atoms
    }
    val visibleRules = rules.filter { rule ->
        BondRuleMatching.hasMatchingBond(
            rule,
            tab.structure,
            tab.bondConfiguration,
            bondGrid.second,
            bondGrid.first,
        )
    }.distinctBy { it.key } // Per v0.5.4b: bondRules can carry duplicate site-pair keys (e.g. a large
    // cell whose ASU has repeated site ids, or a smart-ionic regen that re-emitted a pair). The
    // renderer dedups via associateBy, but LazyColumn's key must be unique — collapse duplicates
    // here so opening 编辑-化学键 on a large cell no longer crashes with "Key was already used".

    // Rebuild rules off the UI thread, showing a "computing" dialog while it runs.
    fun rebuildAsync(source: RadiusSource, epsilon: Double, skipConfirm: Boolean) {
        // Per v0.5.0: large cells prompted to confirm before a manual smart-ionic rebuild.
        if (source == RadiusSource.SMART_IONIC && !skipConfirm &&
            SymmetryExpander.expand(tab.structure).size > BondValence.SMART_IONIC_ATOM_LIMIT) {
            confirmSmartIonic = true
            return
        }
        loading = true
        scope.launch(Dispatchers.Default) {
            val result = CrystalEditor.rebuildBondRules(
                tab.structure,
                tab.bondConfiguration,
                source,
                epsilon,
            )
            withContext(Dispatchers.Main) {
                loading = false
                tab.lastRadiusSource = source
                if (CrystalEditor.SMART_IONIC_UNAVAILABLE in result.warnings) onMessage(unavailableMessage)
                onStructure(result)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Per v0.5.0: "自动应用半径" (auto-apply radii) clears every bond rule and regenerates them
        // from one of three radius sources (smart-ionic default / bonding / vdW).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(localized("新建规则", "New rule")) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { radiiMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(localized("自动应用半径", "Auto-apply radii"))
                }
                DropdownMenu(expanded = radiiMenuOpen, onDismissRequest = { radiiMenuOpen = false }) {
                    DropdownMenuItem(text = { Text(localized("智能离子", "Smart ionic")) }, onClick = {
                        radiiMenuOpen = false
                        rebuildAsync(RadiusSource.SMART_IONIC, tab.bondEpsilon, skipConfirm = false)
                    })
                    DropdownMenuItem(text = { Text(localized("键合半径", "Bonding radius")) }, onClick = {
                        radiiMenuOpen = false
                        rebuildAsync(RadiusSource.BONDING, tab.bondEpsilon, skipConfirm = true)
                    })
                    DropdownMenuItem(text = { Text(localized("vdW 半径", "vdW radius")) }, onClick = {
                        radiiMenuOpen = false
                        rebuildAsync(RadiusSource.VDW, tab.bondEpsilon, skipConfirm = true)
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(localized("取消", "Cancel")) }, onClick = { radiiMenuOpen = false })
                }
            }
        }
        // Per v0.5.0: bond-threshold ε — slider and two-decimal input side by side. Adjusting it
        // re-runs the last-used source so the scene re-renders immediately; the 100-atom confirm
        // does not re-prompt on ε changes.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var epsilonText by remember(tab.bondEpsilon) { mutableStateOf("%.2f".format(tab.bondEpsilon)) }
            OutlinedTextField(
                value = epsilonText,
                onValueChange = { input ->
                    epsilonText = input
                    input.toFloatOrNull()?.let { v ->
                        val clamped = v.coerceIn(0.0f, 0.5f)
                        if (clamped.toDouble() != tab.bondEpsilon) {
                            tab.bondEpsilon = clamped.toDouble()
                            rebuildAsync(tab.lastRadiusSource, tab.bondEpsilon, skipConfirm = true)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.width(88.dp),
            )
            Slider(
                value = tab.bondEpsilon.toFloat(),
                onValueChange = { v ->
                    tab.bondEpsilon = v.toDouble()
                    rebuildAsync(tab.lastRadiusSource, tab.bondEpsilon, skipConfirm = true)
                },
                valueRange = 0f..0.5f,
                modifier = Modifier.weight(1f),
            )
        }
        Text(epsilonHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(visibleRules, key = { it.key }) { rule ->
                val labelA = sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                val labelB = sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                Row(
                    Modifier.fillMaxWidth().clickable { editingRule = rule }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$labelA — $labelB", modifier = Modifier.weight(1f))
                    Text("%.3f–%.3f Å".format(rule.minAngstrom, rule.maxAngstrom), modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = {
                        onStructure(CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.RemoveBondRule(rule.key)))
                    }) {
                        Icon(Icons.Default.Delete, null)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (loading) {
        BasicAlertDialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(computingMessage)
                }
            }
        }
    }
    if (confirmSmartIonic) {
        AlertDialog(
            onDismissRequest = { confirmSmartIonic = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmMessage) },
            confirmButton = { TextButton(onClick = {
                confirmSmartIonic = false
                rebuildAsync(RadiusSource.SMART_IONIC, tab.bondEpsilon, skipConfirm = true)
            }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { confirmSmartIonic = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (addOpen) BondRuleDialog(sites, editingRule = null, onDismiss = { addOpen = false }) { siteA, siteB, min, max, extend ->
        runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(BondRule(siteA, siteB, min, max, BondRuleSource.CUSTOM, extend))) }
            .onSuccess { onStructure(it); addOpen = false }.onFailure { onMessage(it.message ?: "Invalid bond rule") }
    }
    editingRule?.let { rule ->
        BondRuleDialog(sites, editingRule = rule, onDismiss = { editingRule = null }) { siteA, siteB, min, max, extend ->
            runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(BondRule(siteA, siteB, min, max, BondRuleSource.CUSTOM, extend))) }
                .onSuccess { onStructure(it); editingRule = null }.onFailure { onMessage(it.message ?: "Invalid bond rule") }
        }
    }
}

@Composable
private fun BondRuleDialog(
    sites: List<Site>,
    editingRule: BondRule?,
    onDismiss: () -> Unit,
    onApply: (String, String, Double, Double, Boolean) -> Unit,
) {
    var a by remember { mutableStateOf(editingRule?.siteA ?: sites.first().id) }
    var b by remember { mutableStateOf(editingRule?.siteB ?: sites.first().id) }
    val siteA = sites.firstOrNull { it.id == a } ?: sites.first()
    val siteB = sites.firstOrNull { it.id == b } ?: sites.first()
    // Per v0.4.1: default maximum is the sum of the two atoms' bonding radii (键合半径),
    // matching the default bond-rule generator. It is recomputed as the user switches sites so the
    // suggested window tracks the selected pair.
    val defaultMax = PeriodicTable.radius(siteA.species.symbol, RadiusSource.BONDING) +
        PeriodicTable.radius(siteB.species.symbol, RadiusSource.BONDING)
    var minValue by remember { mutableFloatStateOf((editingRule?.minAngstrom ?: 0.1).toFloat()) }
    // When creating a new rule (no editingRule), maxValue follows the selected pair's default; once
    // the user drags the slider the override sticks until they switch sites again.
    var maxValue by remember { mutableFloatStateOf((editingRule?.maxAngstrom ?: defaultMax).toFloat()) }
    var userTouchedMax by remember { mutableStateOf(editingRule != null) }
    if (!userTouchedMax) {
        // Re-sync the displayed maximum to the current pair's default whenever the user changes A/B
        // without having manually adjusted the slider.
        LaunchedEffect(defaultMax) { maxValue = defaultMax.toFloat() }
    }
    val extendAcrossCell = editingRule?.extendAcrossCell ?: false
    val sliderMax = max(5.0, defaultMax + 1.0).toFloat().coerceAtMost(10.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingRule == null) localized("新建化学键规则", "New bond rule") else localized("修改化学键规则", "Edit bond rule")) },
        text = {
            Column {
                DropdownField(localized("晶位 A", "Site A"), siteA.label, sites.map { it.label }) { label -> a = sites.first { it.label == label }.id; userTouchedMax = false }
                DropdownField(localized("晶位 B", "Site B"), siteB.label, sites.map { it.label }) { label -> b = sites.first { it.label == label }.id; userTouchedMax = false }
                DistanceControl(localized("最小值 (Å)", "Minimum (Å)"), minValue, 0f..sliderMax) { minValue = it }
                DistanceControl(localized("最大值 (Å)", "Maximum (Å)"), maxValue, 0f..sliderMax) { maxValue = it; userTouchedMax = true }
                Text(localized("默认最大值为两原子键合半径之和", "Default maximum is the sum of bonding radii"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = {
            val min = minValue.toDouble()
            val max = maxValue.toDouble()
            if (min > max) return@TextButton
            runCatching { onApply(a, b, min, max, extendAcrossCell) }.onFailure { /* ignore */ }
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DistanceControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.3f".format(value)) }
    Text(label, modifier = Modifier.padding(top = 12.dp)); OutlinedTextField(text, { input -> text = input; input.toFloatOrNull()?.let(onValue) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Slider(value, onValue, valueRange = range)
}

@Composable
private fun ExpansionEditor(tab: DocumentTab, onMessage: (String) -> Unit, onRunBondComputation: ((suspend () -> EditResult?) -> Unit)? = null) {
    var x by remember(tab.expansion) { mutableStateOf(tab.expansion.x) }
    var y by remember(tab.expansion) { mutableStateOf(tab.expansion.y) }
    var z by remember(tab.expansion) { mutableStateOf(tab.expansion.z) }
    // Per v0.5.3b: pre-resolved so the degrade snackbar can fire from a non-@Composable onClick.
    val degradeMessage = localized("原子数过多，已降级显示（边界多面体可能不完整）", "Many atoms; rendering in degraded mode (boundary polyhedra may be incomplete)")
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text(localized("显示扩胞", "Display supercell"), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        listOf(
            Triple("x", x) { v: Int -> x = v },
            Triple("y", y) { v: Int -> y = v },
            Triple("z", z) { v: Int -> z = v },
        ).forEach { (label, value, setter) ->
            ExpansionCluster(label, value, setter)
            Spacer(Modifier.height(10.dp))
        }
        Button(onClick = {
            runCatching {
                val expansion = Expansion(x, y, z)
                val base = SymmetryExpander.expand(tab.structure).size
                require(base.toLong() * expansion.multiplier <= BondDetector.MAX_RENDERED_ATOMS) { "100,000 atom limit exceeded" }
                // Per v0.5.3b: warn if the shell materialisation will degrade to avoid OOM.
                val degrade = BondDetector.estimatePeakAtomCount(base, expansion) >
                    BondDetector.SHELL_DEGRADE_THRESHOLD
                tab.expansion = expansion
                degrade
            }.onSuccess { degrade ->
                if (degrade) onMessage(degradeMessage)
            }.onFailure { onMessage(it.message ?: "Invalid expansion") }
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(localized("应用", "Apply")) }
    }
}

@Composable
private fun ExpansionCluster(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    // Range 1..5.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
        OutlinedTextField(
            text,
            { input -> text = input; input.toIntOrNull()?.let { n -> if (n in 1..5) onValue(n) } },
            singleLine = true,
            modifier = Modifier.width(64.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> val n = v.toInt().coerceIn(1, 5); onValue(n); text = n.toString() },
            valueRange = 1f..5f,
            steps = 3,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

@Composable
fun AppearanceDialog(
    tab: DocumentTab,
    onDismiss: () -> Unit,
    onApplied: (ViewerAppearance) -> Unit = {},
    rendererBackend: RendererBackend = RendererBackend.FILAMENT,
    onRendererBackendChanged: (RendererBackend) -> Unit = {},
    // Per v0.5.2a: press-and-hold Preview callbacks. onPreviewStart hands the in-dialog appearance
    // up so the viewer can render with it; onPreviewEnd restores the dialog.
    onPreviewStart: (ViewerAppearance) -> Unit = {},
    onPreviewEnd: () -> Unit = {},
) {
    var appearance by remember { mutableStateOf(tab.appearance) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var bondColorPickerOpen by remember { mutableStateOf(false) }
    // Per v0.5.3a: preview hides the dialog visually (alpha 0) but keeps it mounted so the
    // Preview button's pointerInput survives the press-and-hold and onPreviewEnd fires on release.
    // (v0.5.2a unmounted the dialog on press, killing the gesture → stuck preview.)
    var previewing by remember { mutableStateOf(false) }
    val frameLabels = listOf(localized("不显示框线", "No frame"), localized("单个晶胞", "Single cell"), localized("所有框线", "All frames"))
    val lineLabels = listOf(localized("实线", "Solid"), localized("虚线", "Dashed"))
    val bondColorLabels = listOf(localized("双色圆柱", "Bicolor cylinder"), localized("单色圆柱", "Unicolor cylinder"))
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (previewing) 0f else 1f),
    ) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow, previewing) {
            val window = dialogWindow ?: return@DisposableEffect onDispose {}
            val originalDimAmount = window.attributes.dimAmount
            if (previewing) window.setDimAmount(0f)
            onDispose { runCatching { window.setDimAmount(originalDimAmount) } }
        }
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                Text(localized("调整外观", "Appearance"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                Column(Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState())) {
            Text(localized("背景色", "Background"), fontWeight = FontWeight.Bold)
            FlowRow(verticalArrangement = Arrangement.Center) {
                listOf(
                    0xFF000000L to localized("黑色", "Black"),
                    0xFFFFFFFFL to localized("白色", "White"),
                    0xFF4D2D6EL to localized("紫色", "Purple"),
                ).forEach { (argb, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Box(Modifier.size(40.dp).background(colorFromArgb(argb), RoundedCornerShape(20.dp)).clickable { appearance = appearance.copy(backgroundArgb = argb) })
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Box(Modifier.size(40.dp).background(colorFromArgb(appearance.backgroundArgb), RoundedCornerShape(20.dp)).clickable { colorPickerOpen = true })
                    Text(localized("自定义", "Custom"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("框线", "Frame"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    DropdownField(localized("模式", "Mode"), frameLabels[appearance.frameMode.ordinal], frameLabels) { appearance = appearance.copy(frameMode = FrameMode.entries[frameLabels.indexOf(it)]) }
                }
                // Per v0.5.3: line style is meaningless when no frame is drawn, so hide it.
                if (appearance.frameMode != FrameMode.NONE) Column(Modifier.weight(1f)) {
                    DropdownField(localized("线型", "Line"), lineLabels[appearance.lineStyle.ordinal], lineLabels) { appearance = appearance.copy(lineStyle = LineStyle.entries[lineLabels.indexOf(it)]) }
                }
            }
            Spacer(Modifier.height(8.dp))
            val axisLabels = listOf("abc", "XYZ")
            Text(localized("坐标轴", "Axes"), style = MaterialTheme.typography.bodySmall)
            // Per v0.5.3: switch left, system dropdown right, both vertically centred and spaced.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = appearance.showAxes, onCheckedChange = { appearance = appearance.copy(showAxes = it) })
                if (appearance.showAxes) {
                    Spacer(Modifier.weight(1f))
                    DropdownField(localized("坐标系", "System"), axisLabels[appearance.axisMode.ordinal], axisLabels) {
                        appearance = appearance.copy(axisMode = AxisMode.entries[axisLabels.indexOf(it)])
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("原子", "Atoms"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("原子反射", "Atom reflection"), appearance.reflectionEnabled) { appearance = appearance.copy(reflectionEnabled = it) }
            LabeledSlider(localized("原子不透明度", "Atom opacity"), appearance.atomOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(atomOpacity = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("化学键", "Bonds"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            ToggleRow(localized("键反射", "Bond reflection"), appearance.bondReflectionEnabled) { appearance = appearance.copy(bondReflectionEnabled = it) }
            LabeledSlider(localized("键半径", "Bond radius"), appearance.bondRadius, 0.02f..0.4f) { appearance = appearance.copy(bondRadius = it) }
            LabeledSlider(localized("化学键不透明度", "Bond opacity"), appearance.bondOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(bondOpacity = it) }
            DropdownField(localized("键颜色", "Bond color"), bondColorLabels[appearance.bondColorMode.ordinal], bondColorLabels) { appearance = appearance.copy(bondColorMode = BondColorMode.entries[bondColorLabels.indexOf(it)]) }
            if (appearance.bondColorMode == BondColorMode.UNICOLOR) FlowRow {
                listOf(0xFF9A90A0, 0xFF9966CC, 0xFFFFFFFF, 0xFF333333).forEach { argb -> Box(Modifier.padding(5.dp).size(38.dp).background(colorFromArgb(argb), CircleShape).clickable { appearance = appearance.copy(uniformBondArgb = argb) }) }
                // Per v0.5.3: custom colour swatch opens the colour wheel for the uniform bond colour.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp)) {
                    Box(Modifier.size(38.dp).background(colorFromArgb(appearance.uniformBondArgb), CircleShape).clickable { bondColorPickerOpen = true })
                    Text(localized("自定义", "Custom"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("多面体", "Polyhedra"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("显示多面体", "Show polyhedra"), appearance.polyhedronEnabled) { appearance = appearance.copy(polyhedronEnabled = it) }
            if (appearance.polyhedronEnabled) {
                ToggleRow(localized("多面体反射", "Polyhedron reflection"), appearance.polyhedronReflectionEnabled) { appearance = appearance.copy(polyhedronReflectionEnabled = it) }
                LabeledSlider(localized("多面体不透明度", "Polyhedron opacity"), appearance.polyhedronOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(polyhedronOpacity = it) }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("世界光源", "World light"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabeledSlider(localized("光源方位角", "Light azimuth"), appearance.lightAzimuth, 0f..360f) { appearance = appearance.copy(lightAzimuth = it) }
                    LabeledSlider(localized("光源高度角", "Light elevation"), appearance.lightElevation, 0f..90f) { appearance = appearance.copy(lightElevation = it) }
                    LabeledSlider(localized("反射强度", "Reflection intensity"), appearance.lightIntensity, 0f..1f, percentage = true) { appearance = appearance.copy(lightIntensity = it) }
                    LabeledSlider(localized("反射扩散", "Reflection diffusion"), appearance.diffusion, 0f..1f, percentage = true) { appearance = appearance.copy(diffusion = it) }
                }
                AtomAppearancePreview(appearance, Modifier.padding(start = 10.dp).size(112.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("景深", "Depth cueing"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("景深", "Depth cueing"), appearance.depthOfFieldEnabled) { appearance = appearance.copy(depthOfFieldEnabled = it) }
            if (appearance.depthOfFieldEnabled) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // Per v0.5.4: 景深标度近=正/远=负,约定 near>=far(起≥止)。连续滑块 -5..5。
                        LabeledSlider(localized("起始值", "Near"), appearance.dofNear, -5f..5f) { v -> appearance = appearance.copy(dofNear = v.coerceAtLeast(appearance.dofFar)) }
                        LabeledSlider(localized("终止值", "Far"), appearance.dofFar, -5f..5f) { v -> appearance = appearance.copy(dofFar = v.coerceAtMost(appearance.dofNear)) }
                    }
                    DepthCueingPreview(appearance, Modifier.padding(start = 10.dp).size(112.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("渲染引擎", "Rendering engine"), fontWeight = FontWeight.Bold)
            val backends = listOf(
                RendererBackend.FILAMENT to "Filament",
                RendererBackend.CANVAS_LEGACY to "Canvas (Legacy)",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                backends.forEachIndexed { index, (backend, label) ->
                    SegmentedButton(
                        selected = rendererBackend == backend,
                        onClick = { onRendererBackendChanged(backend) },
                        shape = SegmentedButtonDefaults.itemShape(index, backends.size),
                        label = { Text(label) },
                    )
                }
            }
        }
        // Per v0.5.2a: Preview (press-and-hold, rounded) / Cancel (text) / Save (rounded).
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Preview: press-and-hold via detectTapGestures.onPress; no onClick.
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                previewing = true
                                onPreviewStart(appearance)
                                tryAwaitRelease()
                                onPreviewEnd()
                                previewing = false
                            },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(localized("预览", "Preview"), color = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(localized("取消", "Cancel")) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onApplied(appearance); onDismiss() },
                shape = RoundedCornerShape(16.dp),
            ) { Text(localized("保存", "Save")) }
        }
        }
    }
    }

    if (colorPickerOpen) {
        ColorPickerDialog(
            initialArgb = appearance.backgroundArgb,
            onDismiss = { colorPickerOpen = false },
            onColorSelected = { color ->
                appearance = appearance.copy(backgroundArgb = color)
                colorPickerOpen = false
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); androidx.compose.material3.Switch(checked, onChecked) } }
@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, percentage: Boolean = false, steps: Int = 0, onValue: (Float) -> Unit) { Text("$label  ${if (percentage) "%.0f%%".format(value * 100) else "%.1f".format(value)}"); Slider(value, onValue, valueRange = range, steps = steps) }

@Composable
private fun AtomAppearancePreview(appearance: ViewerAppearance, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val radius = size.minDimension * 0.38f
            val center = Offset(size.width / 2f, size.height / 2f)
            val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
            // Per v0.5.3: slightly lower the preview sphere's own lightness so the highlight reads.
            val gray = Color(0xFF747479).copy(alpha = opacity)
            val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
            val elevation = appearance.lightElevation / 180f * PI.toFloat()
            val lightOffset = radius * .95f * cos(elevation)
            val highlight = center - Offset(cos(azimuth) * lightOffset, sin(azimuth) * lightOffset)
            drawCircle(gray, radius, center)
            if (appearance.reflectionEnabled && opacity > 0.01f) {
                val highlightBrush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = appearance.lightIntensity.coerceIn(.05f, 1f) * opacity), Color.Transparent),
                    center = highlight,
                    radius = radius * (0.35f + 0.75f * appearance.diffusion),
                )
                drawCircle(highlightBrush, radius, center)
            }
            drawCircle(Color.Black.copy(alpha = .3f * opacity), radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        }
    }
}

/** Five horizontal depth samples with their background-mix curve plotted above them. */
@Composable
private fun DepthCueingPreview(appearance: ViewerAppearance, modifier: Modifier = Modifier) {
    val bgCompose = MaterialTheme.colorScheme.surfaceVariant
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = bgCompose) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val near = appearance.dofNear.coerceIn(-5f, 5f)
            val far = appearance.dofFar.coerceIn(-5f, 5f)
            val depths = listOf(-5f, -2.5f, 0f, 2.5f, 5f)
            val radius = size.minDimension * 0.105f
            val xStart = radius * 1.25f
            val xEnd = size.width - radius * 1.25f
            val xPositions = depths.indices.map { index ->
                xStart + (xEnd - xStart) * index / depths.lastIndex.toFloat()
            }
            val chartTop = size.height * 0.10f
            val chartBottom = size.height * 0.43f
            val atomY = size.height * 0.73f
            val guideColor = Color.White.copy(alpha = 0.48f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
            drawLine(guideColor, Offset(xStart, chartTop), Offset(xEnd, chartTop), 1.2f, pathEffect = dash)
            drawLine(guideColor, Offset(xStart, chartBottom), Offset(xEnd, chartBottom), 1.2f, pathEffect = dash)

            val fogValues = depths.map { depthCueFog(it, near, far) }
            val curve = Path().apply {
                fogValues.forEachIndexed { index, fog ->
                    val y = chartBottom - fog * (chartBottom - chartTop)
                    if (index == 0) moveTo(xPositions[index], y) else lineTo(xPositions[index], y)
                }
            }
            drawPath(curve, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            fogValues.forEachIndexed { index, fog ->
                val y = chartBottom - fog * (chartBottom - chartTop)
                drawCircle(Color.White, 2.4f, Offset(xPositions[index], y))
                drawPreviewSphere(Offset(xPositions[index], atomY), radius, appearance, fog, bgCompose)
            }

            val nc = drawContext.canvas.nativeCanvas
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                textSize = radius * 0.72f
            }
            nc.drawText("1", xStart - radius, chartTop + p.textSize * 0.35f, p)
            nc.drawText("0", xStart - radius, chartBottom + p.textSize * 0.35f, p)
        }
    }
}

/** Draws one lit preview sphere at [c] with radius [r], world-light highlight; colour blends
 *  toward [bg] by [fog] (opacity unchanged), mirroring the renderer's depth cueing. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPreviewSphere(
    c: Offset, r: Float, appearance: ViewerAppearance, fog: Float, bg: Color,
) {
    val base = Color(0xFF747479).blend(bg, fog)
    drawCircle(base, r, c)
    if (appearance.reflectionEnabled) {
        val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
        val elevation = appearance.lightElevation / 180f * PI.toFloat()
        val lightOffset = r * .95f * cos(elevation)
        val highlight = c - Offset(cos(azimuth) * lightOffset, sin(azimuth) * lightOffset)
        val highlightBrush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = appearance.lightIntensity.coerceIn(.05f, 1f) * (1f - fog)), Color.Transparent),
            center = highlight,
            radius = r * (0.35f + 0.75f * appearance.diffusion),
        )
        drawCircle(highlightBrush, r, c)
    }
    drawCircle(Color.Black.copy(alpha = .3f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
}

/**
 * Linear fog on the normalized supercell depth scale (-5 far, +5 near). Returns fog in 0..1
 * (0 = near/no fade, 1 = far/fully faded). Mirrors [CrystalViewport.dofFog].
 */
private fun depthCueFog(d: Float, near: Float, far: Float): Float {
    if (near <= far) return if (d >= near) 0f else 1f
    if (d >= near) return 0f
    if (d <= far) return 1f
    return ((near - d) / (near - far)).coerceIn(0f, 1f)
}

/** Per v0.5.3a: blend this colour toward [target] by [t] (0..1). Mirrors the renderer's blend. */
private fun Color.blend(target: Color, t: Float) = Color(
    red + (target.red - red) * t,
    green + (target.green - green) * t,
    blue + (target.blue - blue) * t,
    alpha,
)

@Composable
fun ColorPickerDialog(initialArgb: Long, onDismiss: () -> Unit, onColorSelected: (Long) -> Unit) {
    val initialHsv = remember(initialArgb) { argbToHsv(initialArgb) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val density = LocalDensity.current
    val wheelSize = 220.dp
    val wheelPx = with(density) { wheelSize.toPx() }
    val radiusPx = wheelPx / 2f

    fun updateFromPosition(position: Offset) {
        val center = Offset(radiusPx, radiusPx)
        val dx = position.x - center.x
        val dy = position.y - center.y
        val dist = sqrt(dx * dx + dy * dy)
        saturation = (dist / radiusPx).coerceIn(0f, 1f)
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (degrees < 0) degrees += 360f
        hue = degrees
    }

    val selectedColor = remember(hue, saturation, value) {
        hsvToArgb(0xFF, floatArrayOf(hue, saturation, value))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("选择颜色", "Choose color")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(wheelSize)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                updateFromPosition(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    updateFromPosition(change.position)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                                center = center,
                            ),
                            radius = radius,
                            center = center,
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White, Color.Transparent),
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                        )
                        val angleRad = Math.toRadians(hue.toDouble())
                        val ix = center.x + saturation * radius * cos(angleRad).toFloat()
                        val iy = center.y + saturation * radius * sin(angleRad).toFloat()
                        drawCircle(Color.Black, 8f, Offset(ix, iy), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawCircle(Color.White, 6f, Offset(ix, iy), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
                Text(localized("亮度", "Brightness"), modifier = Modifier.padding(top = 8.dp))
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(48.dp)
                        .background(colorFromArgb(selectedColor), RoundedCornerShape(8.dp))
                )
            }
        },
        confirmButton = { TextButton(onClick = { onColorSelected(selectedColor); onDismiss() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun argbToHsv(argb: Long): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), hsv)
    return hsv
}

private fun hsvToArgb(alpha: Int, hsv: FloatArray): Long {
    return android.graphics.Color.HSVToColor(alpha, hsv).toLong() and 0xFFFFFFFFL
}

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
