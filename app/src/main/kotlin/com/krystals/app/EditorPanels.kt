@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.VoronoiSearchLimitExceededException
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
import com.krystals.crystal.data.BravaisLatticeData
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class EditorTab { BASIC, ATOMS, BONDS, EXPANSION }

@Composable
fun EditorPanel(
    tab: DocumentTab,
    onDismiss: () -> Unit,
    onStructure: (EditResult) -> Unit,
    onMessage: (String) -> Unit,
    onRunBondComputation: ((suspend () -> EditResult?) -> Unit)? = null,
    onPersistentMessage: (String?) -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(
        when {
            tab.editingSiteId != null -> EditorTab.ATOMS
            tab.pendingEditorTab == "atoms" -> EditorTab.ATOMS
            tab.pendingEditorTab == "bonds" -> EditorTab.BONDS
            tab.pendingEditorTab == "expansion" -> EditorTab.EXPANSION
            else -> EditorTab.BASIC
        }
    ) }
    // Per v0.7.1: consume the pending tab hint once the editor opens.
    LaunchedEffect(Unit) { tab.pendingEditorTab = null }
    // Per v0.7.0: slide-in animation state.
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val slideProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "editorSlide",
    )
    LaunchedEffect(visible) {
        if (!visible && dismissed) { delay(150); onDismiss() }
    }
    fun doDismiss() { dismissed = true; visible = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Per v0.7.0: persist panel ratio per orientation (portrait/landscape) so the user's
        // drag-resize preference survives across sessions.
        val panelPrefs = LocalContext.current.getSharedPreferences("panel_sizes", android.content.Context.MODE_PRIVATE)
        val prefKey = "editor_panel_ratio_${if (landscape) "landscape" else "portrait"}"
        var panelRatio by remember(landscape) { mutableStateOf(panelPrefs.getFloat(prefKey, 0.62f)) }
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = slideProgress }.background(Color.Black.copy(alpha = 0.22f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { doDismiss() },
            ),
        )
        val panelModifier = if (landscape) {
            Modifier.fillMaxHeight().fillMaxWidth(panelRatio).align(Alignment.CenterEnd)
                .graphicsLayer { translationX = (1f - slideProgress) * widthPx }
        } else {
            Modifier.fillMaxWidth().fillMaxHeight(panelRatio).align(Alignment.BottomCenter)
                .graphicsLayer { translationY = (1f - slideProgress) * heightPx }
        }
        Surface(
            tonalElevation = 8.dp,
            modifier = panelModifier,
        ) {
            // Per v0.3.3: in landscape the drag handle is a vertical bar on the left, so the panel
            // content must sit beside it in a Row (a Column with a fillMaxHeight first child would
            // leave no height for the tabs/content — the cause of the blank landscape panel).
            val handleModifier = if (landscape) {
                Modifier.fillMaxHeight().width(24.dp)
            } else {
                Modifier.fillMaxWidth().height(24.dp)
            }
            val dividerModifier = if (landscape) {
                Modifier.fillMaxHeight().width(18.dp)
            } else {
                Modifier.fillMaxWidth().height(18.dp)
            }
            val handle = @Composable {
                // Per v0.8.1: 24.dp drag hit target (the visible divider stays pinned to the panel
                // edge, pre-v0.8.1 style); persist the ratio once when the drag ends (or is
                // cancelled) instead of writing SharedPreferences on every drag frame.
                Box(
                    Modifier
                        .then(handleModifier)
                        .pointerInput(landscape) {
                            detectDragGestures(
                                onDragEnd = { panelPrefs.edit().putFloat(prefKey, panelRatio).apply() },
                                onDragCancel = { panelPrefs.edit().putFloat(prefKey, panelRatio).apply() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (landscape) {
                                        panelRatio = (panelRatio - amount.x / widthPx).coerceIn(0.2f, 0.95f)
                                    } else {
                                        panelRatio = (panelRatio - amount.y / heightPx).coerceIn(0.2f, 0.95f)
                                    }
                                },
                            )
                        },
                ) {
                    // Per v0.8.1: pin the visible divider to the panel edge (as pre-v0.8.1) so no
                    // background strip shows above it; only the drag hit target is 24.dp.
                    Box(
                        Modifier.then(dividerModifier)
                            // In Compose 1.11 the 1-D Alignment.Start is an Alignment.Horizontal that
                            // is NOT an Alignment, so BoxScope.align() rejects it. CenterStart/TopStart
                            // are declared Alignment; the divider fills the other axis so its
                            // alignment on that axis is irrelevant.
                            .align(if (landscape) Alignment.CenterStart else Alignment.TopStart)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            val content = @Composable {
                Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            EditorTab.BASIC to stringResource(R.string.basic_info), EditorTab.ATOMS to stringResource(R.string.atoms),
                            EditorTab.BONDS to stringResource(R.string.bonds), EditorTab.EXPANSION to stringResource(R.string.expand_cell),
                        ).forEach { (kind, label) -> FilterChip(selectedTab == kind, onClick = { selectedTab = kind }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                        Spacer(Modifier.weight(1f)); IconButton(onClick = { doDismiss() }) { Icon(Icons.Default.Close, null) }
                    }
                    when (selectedTab) {
                        EditorTab.BASIC -> BasicEditor(tab, onStructure, onMessage, onRunBondComputation)
                        EditorTab.ATOMS -> AtomEditor(tab, { doDismiss() }, onStructure, onMessage, onRunBondComputation, onPersistentMessage)
                        EditorTab.BONDS -> BondEditor(tab, onStructure, onMessage, { doDismiss() }, onPersistentMessage)
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
    var clearSymmetryOpen by remember { mutableStateOf(false) }

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
        // Per v0.8.1: swapped order — primitive/conventional conversion comes before 3×3 transform.
        val centering = BravaisLatticeData.centeringFromSymbol(tab.structure.spaceGroup.symbol)
        if (centering != BravaisLatticeData.CenteringType.PRIMITIVE) {
            OutlinedButton(
                onClick = {
                    tab.recordHistory()
                    if (tab.isPrimitiveCell) {
                        // Converting from primitive back to conventional.
                        // Save current primitive data so we can restore it later.
                        tab.savedPrimitiveStructure = tab.structure
                        tab.savedPrimitiveBondConfig = tab.bondConfiguration
                        // Restore saved conventional data if available; otherwise do matrix conversion.
                        val result = if (tab.savedConventionalStructure != null) {
                            EditResult(tab.savedConventionalStructure!!, tab.savedConventionalBondConfig!!)
                        } else {
                            runCatching { CrystalEditor.convertToConventional(tab.structure, tab.bondConfiguration) }
                                .getOrElse { onMessage(it.message ?: "Conversion failed"); return@OutlinedButton }
                                .also {
                                    tab.savedConventionalStructure = it.structure
                                    tab.savedConventionalBondConfig = it.bondConfiguration
                                }
                        }
                        if (onRunBondComputation != null) onRunBondComputation {
                            CrystalEditor.ensureAutoBondRules(result.structure, result.bondConfiguration)
                        } else onStructure(CrystalEditor.ensureAutoBondRules(result.structure, result.bondConfiguration))
                        tab.isPrimitiveCell = false
                    } else {
                        // Converting from conventional to primitive.
                        // Save current conventional data so we can restore it later.
                        tab.savedConventionalStructure = tab.structure
                        tab.savedConventionalBondConfig = tab.bondConfiguration
                        // Restore saved primitive data if available; otherwise do matrix conversion.
                        val result = if (tab.savedPrimitiveStructure != null) {
                            EditResult(tab.savedPrimitiveStructure!!, tab.savedPrimitiveBondConfig!!)
                        } else {
                            runCatching { CrystalEditor.convertToPrimitive(tab.structure, tab.bondConfiguration) }
                                .getOrElse { onMessage(it.message ?: "Conversion failed"); return@OutlinedButton }
                                .also {
                                    tab.savedPrimitiveStructure = it.structure
                                    tab.savedPrimitiveBondConfig = it.bondConfiguration
                                }
                        }
                        if (onRunBondComputation != null) onRunBondComputation {
                            CrystalEditor.ensureAutoBondRules(result.structure, result.bondConfiguration)
                        } else onStructure(CrystalEditor.ensureAutoBondRules(result.structure, result.bondConfiguration))
                        tab.isPrimitiveCell = true
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (tab.isPrimitiveCell) localized("转换为正当晶胞", "Convert to Conventional") else localized("转换为素晶胞", "Convert to Primitive"))
            }
        }
        OutlinedButton(onClick = { transformOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(localized("3×3 变换矩阵", "3×3 Transform")) }
        // Per v0.8.0: Clear symmetry button.
        OutlinedButton(
            onClick = { clearSymmetryOpen = true },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(localized("清除对称性", "Clear Symmetry")) }
    }
    if (transformOpen) TransformDialog(onDismiss = { transformOpen = false }) { rows, translation, clearSymmetryFirst ->
        runCatching {
            if (clearSymmetryFirst) {
                CrystalEditor.transformAfterClearingSymmetry(tab.structure, tab.bondConfiguration, rows, translation)
            } else {
                CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.Transform(rows, translation))
            }
        }
            .onSuccess { transformed ->
                if (onRunBondComputation != null) onRunBondComputation {
                    CrystalEditor.ensureAutoBondRules(transformed.structure, transformed.bondConfiguration)
                } else onStructure(CrystalEditor.ensureAutoBondRules(transformed.structure, transformed.bondConfiguration))
// Per v0.7.1: 3×3 matrix transform no longer affects expansion. The scaling is
// handled by symmetry operations within the structure itself.
                tab.isPrimitiveCell = false
                tab.savedConventionalStructure = null; tab.savedConventionalBondConfig = null
                tab.savedPrimitiveStructure = null; tab.savedPrimitiveBondConfig = null
                transformOpen = false
            }
            .onFailure { onMessage(it.message ?: "Invalid transformation") }
    }
    if (clearSymmetryOpen) {
        AlertDialog(
            onDismissRequest = { clearSymmetryOpen = false },
            title = { Text(localized("确认", "Confirm")) },
            text = { Text(localized("确认移除此晶体的对称性并回退到P1点群吗？", "Remove symmetry and revert to P1?")) },
            confirmButton = { TextButton(onClick = {
                runCatching { CrystalEditor.clearSymmetry(tab.structure, tab.bondConfiguration) }
                    .onSuccess { result ->
                        if (onRunBondComputation != null) onRunBondComputation {
                            CrystalEditor.ensureAutoBondRules(result.structure, result.bondConfiguration)
                        } else onStructure(CrystalEditor.ensureAutoBondRules(result.structure, result.bondConfiguration))
                        tab.isPrimitiveCell = false
                        tab.savedConventionalStructure = null; tab.savedConventionalBondConfig = null
                        tab.savedPrimitiveStructure = null; tab.savedPrimitiveBondConfig = null
                        clearSymmetryOpen = false
                    }
                    .onFailure { onMessage(it.message ?: "Failed to clear symmetry") }
            }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { clearSymmetryOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CellField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = modifier.padding(vertical = 3.dp))
}

@Composable
private fun AtomEditor(tab: DocumentTab, onDismiss: () -> Unit, onStructure: (EditResult) -> Unit, onMessage: (String) -> Unit, onRunBondComputation: ((suspend () -> EditResult?) -> Unit)? = null, onPersistentMessage: (String?) -> Unit = {}) {
    var atomDialog by remember { mutableStateOf<Site?>(null) }
    var newElement by remember { mutableStateOf<String?>(null) }
    var periodicOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tab.editingSiteId) { tab.editingSiteId?.let { id -> atomDialog = tab.structure.sites.firstOrNull { it.id == id }; tab.editingSiteId = null } }
    val atomEditHint = localized("点击需要修改/删除的原子", "Tap the atom to modify/delete")
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { periodicOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(localized("新建", "New")) }
            OutlinedButton(onClick = { tab.recordHistory(); tab.atomEditMode = AtomEditMode.MODIFY_NEXT; onPersistentMessage(atomEditHint); onDismiss() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Edit, null); Text(localized("修改", "Modify")) }
            OutlinedButton(onClick = { tab.recordHistory(); tab.atomEditMode = AtomEditMode.DELETE_NEXT; onPersistentMessage(atomEditHint); onDismiss() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Text(localized("删除", "Delete")) }
        }
// Per v0.8.11: compute co-located site groups for border coloring.
    val siteGroups = remember(tab.structure) { computeGatheredSiteBorders(tab.structure.sites, tab.renderConfiguration) }
    LazyColumn(Modifier.fillMaxSize()) {
items(tab.structure.sites, key = { it.id }) { site ->
    val groupInfo = siteGroups[site.id]
    val borderMod = if (groupInfo != null) {
        val (wasNormalized, color) = groupInfo
        // Per v0.8.11: co-located (same-position) site groups get a mixedColor border;
        // raw Σocc > 1 gets a thicker RED border.
        val borderColor = if (wasNormalized) Color(0xFFFF4444) else Color(color)
        Modifier.border(if (wasNormalized) 3.dp else 2.dp, borderColor, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(0.5.dp, Color.Gray, RoundedCornerShape(8.dp))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { atomDialog = site }
            .then(borderMod)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
                        Box(Modifier.size(20.dp).background(colorFromArgb(RenderPalette.resolveArgb(site.species.symbol, tab.renderConfiguration)), CircleShape))
                        Text("${site.label}  ${site.species.symbol}   (${fmt(site.fractionalCoordinate.x)}, ${fmt(site.fractionalCoordinate.y)}, ${fmt(site.fractionalCoordinate.z)})", modifier = Modifier.weight(1f).padding(start = 10.dp))
                        IconButton(onClick = {
                            val deleted = runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.DeleteAtom(site.id)) }
                                .onFailure { onMessage(it.message ?: "Delete failed") }.getOrNull() ?: return@IconButton
                            // Per v0.7.1: deleting an atom no longer regenerates all bond rules —
                            // only rules referencing the deleted atom are removed (handled inside DeleteAtom).
                            onStructure(deleted)
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
                // Per v0.7.1: AddAtom no longer regenerates bond rules — existing rules are preserved.
                onStructure(it); it.warnings.forEach(onMessage); newElement = null
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
private fun TransformDialog(onDismiss: () -> Unit, onApply: (List<List<Int>>, FractionalCoordinate, Boolean) -> Unit) {
    // Per v0.6.2: labeled matrix (xx, xy, …, zz) with a vertical separator and translation column.
    val matrixLabels = listOf("xx", "xy", "xz", "yx", "yy", "yz", "zx", "zy", "zz")
    val matrixValues = remember { List(9) { index -> mutableStateOf(if (index % 4 == 0) "1" else "0") } }
    val translationValues = remember { List(3) { mutableStateOf("0") } }
    // Per v0.8.0: warning state for handedness change and shear matrix.
    var pendingRows by remember { mutableStateOf<List<List<Int>>?>(null) }
    var pendingTranslation by remember { mutableStateOf(FractionalCoordinate.ZERO) }
    var showHandednessWarning by remember { mutableStateOf(false) }
    var showShearWarning by remember { mutableStateOf(false) }

    fun parseMatrix(): Pair<List<List<Int>>, FractionalCoordinate>? = runCatching {
        val rows = List(3) { r -> List(3) { c ->
            val number = eval(matrixValues[r * 3 + c].value)
            require(kotlin.math.abs(number - kotlin.math.round(number)) < 1e-9) { "Matrix entries must be integers" }
            number.toInt()
        } }
        val translation = FractionalCoordinate(eval(translationValues[0].value), eval(translationValues[1].value), eval(translationValues[2].value))
        rows to translation
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("3×3 变换矩阵", "3×3 Transform")) },
        text = { Column {
            // Per v0.6.2: matrix rows with a continuous vertical bar separator before translation.
            repeat(3) { row -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { column ->
                    val idx = row * 3 + column
                    OutlinedTextField(matrixValues[idx].value, { matrixValues[idx].value = it.filter { ch -> ch.isDigit() || ch == '-' } }, singleLine = true, label = { Text(matrixLabels[idx], style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f).padding(2.dp), textStyle = MaterialTheme.typography.bodySmall)
                }
                // Continuous vertical separator between matrix and translation
                Box(Modifier.width(2.dp).height(48.dp).background(MaterialTheme.colorScheme.outline))
                OutlinedTextField(translationValues[row].value, { translationValues[row].value = it }, singleLine = true, label = { Text(if (row == 0) "tx" else if (row == 1) "ty" else "tz", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f).padding(2.dp), textStyle = MaterialTheme.typography.bodySmall)
            } }
            Text(localized("变换后坐标 R' = XR + T", "Transformed coordinate R' = XR + T"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
        } },
        confirmButton = { TextButton(onClick = {
            val parsed = parseMatrix()
            if (parsed == null) return@TextButton
            val (rows, translation) = parsed
            pendingRows = rows
            pendingTranslation = translation
            when {
                CrystalEditor.isShearMatrix(rows) -> showShearWarning = true
                CrystalEditor.changesHandedness(rows) -> showHandednessWarning = true
                else -> { onApply(rows, translation, false); onDismiss() }
            }
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    // Per v0.8.0: Handedness warning — matrix changes right-handed to left-handed or vice versa.
    if (showHandednessWarning) {
        AlertDialog(
            onDismissRequest = { showHandednessWarning = false },
            title = { Text(localized("注意", "Warning")) },
            text = { Text(localized("此变换矩阵将右手系变为左手系（或反之），这可能影响晶体的物理性质描述。是否继续？", "This matrix changes handedness (right-handed ↔ left-handed). This may affect physical property descriptions. Continue?")) },
            confirmButton = { TextButton(onClick = {
                showHandednessWarning = false
                pendingRows?.let { onApply(it, pendingTranslation, false); onDismiss() }
            }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { showHandednessWarning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Per v0.8.0: Shear warning — matrix will reduce crystal symmetry. Clear symmetry first.
    if (showShearWarning) {
        AlertDialog(
            onDismissRequest = { showShearWarning = false },
            title = { Text(localized("注意", "Warning")) },
            text = { Text(localized("此变换为剪切矩阵，将降低晶体对称性。系统将先移除对称性（回退到P1），再执行变换。是否继续？", "This is a shear matrix that will reduce crystal symmetry. Symmetry will be cleared (P1) before applying the transform. Continue?")) },
            confirmButton = { TextButton(onClick = {
                showShearWarning = false
                pendingRows?.let { onApply(it, pendingTranslation, true); onDismiss() }
            }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { showShearWarning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
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
private fun BondEditor(tab: DocumentTab, onStructure: (EditResult) -> Unit, onMessage: (String) -> Unit, onDismiss: () -> Unit, onPersistentMessage: (String?) -> Unit = {}) {
    val sites = tab.structure.sites
    if (sites.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("请先添加原子", "Add atoms first")) }; return }
    var addOpen by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<BondRule?>(null) }
    var radiiMenuOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var confirmSmartIonic by remember { mutableStateOf(false) }
    var voronoiWarningOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Per v0.6.3: store the rebuild job and previous epsilon so back-button
    // cancellation can undo the slider change.
    var rebuildJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var previousEpsilon by remember { mutableStateOf<Double?>(null) }
    // Per v0.5.0: smart-ionic unavailable warning + ε hint. localized() is @Composable, so resolve
    // them here in the composable body and reuse inside non-composable lambdas below.
    val unavailableMessage = localized("智能离子规则在该晶体下不可用", "Smart ionic rules are unavailable for this crystal")
    val confirmTitle = localized("警告！", "Warning!")
    val confirmMessage = localized("该晶胞/超胞包含原子数较多，计算量较大，可能导致软件卡顿或崩溃。是否继续？", "This cell/supercell has many atoms; computation is heavy and may cause lag or crashes. Continue?")
    val voronoiWarningTitle = localized("计算已停止", "Calculation stopped")
    val voronoiWarningMessage = localized(
        "周期 Voronoi 搜索范围过大，继续计算可能耗尽内存。请调整晶胞参数或改用键合半径。",
        "The periodic Voronoi search is too large and may exhaust memory. Adjust the cell or use bonding radii.",
    )
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
        previousEpsilon = tab.bondEpsilon
        loading = true
        rebuildJob = scope.launch(Dispatchers.Default) {
            val outcome = runCatching {
                CrystalEditor.rebuildBondRules(
                    tab.structure,
                    tab.bondConfiguration,
                    source,
                    epsilon,
                )
            }
            withContext(Dispatchers.Main) {
                loading = false
                rebuildJob = null
                previousEpsilon = null
                outcome.onSuccess { result ->
                    tab.lastRadiusSource = source
                    if (CrystalEditor.SMART_IONIC_UNAVAILABLE in result.warnings) onMessage(unavailableMessage)
                    onStructure(result)
                }.onFailure { error ->
                    if (error is VoronoiSearchLimitExceededException) voronoiWarningOpen = true
                    else onMessage(error.message ?: "Bond calculation failed")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Per v0.5.0: "自动应用半径" (auto-apply radii) clears every bond rule and regenerates them
        // from one of three radius sources (smart-ionic default / bonding / vdW).
        // Per v0.7.1: "绘制" (draw) and "删除" (delete) buttons replace the old auto-apply button position.
        // Auto-apply is now a compact row with a Switch + source selector + ε slider.
        val drawHint = localized("点击成键的两个目标原子", "Tap two atoms to bond")
        val deleteHint = localized("点击要删除键的两个原子", "Tap two atoms to delete their bond")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { addOpen = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(localized("新建", "New")) }
            OutlinedButton(onClick = {
                tab.bondDrawMode = BondDrawMode.DRAWING
                tab.bondDrawFirstSiteId = null
                tab.bondDrawFirstCartesian = null
                tab.selectedAtomIds = emptyList()
                onPersistentMessage(drawHint)
                onDismiss()
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Create, null); Text(localized("绘制", "Draw")) }
            OutlinedButton(onClick = {
                tab.bondDrawMode = BondDrawMode.DELETING
                tab.bondDrawFirstSiteId = null
                tab.bondDrawFirstCartesian = null
                tab.selectedAtomIds = emptyList()
                onPersistentMessage(deleteHint)
                onDismiss()
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Text(localized("删除", "Delete")) }
        }
        // Per v0.7.1: label on first line, mode button + slider on second line.
        var sliderEpsilon by remember(tab.bondEpsilon) { mutableStateOf(tab.bondEpsilon.toFloat().coerceIn(0.1f, 0.6f)) }
        Text(
            localized("自动应用规则：容忍度 ε = ", "Auto-apply rules: tolerance ε = ") + "%.2f".format(sliderEpsilon.toDouble()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Per v0.7.1: highlighted "模式" button opens a dropdown to pick radius source.
            Box {
                Button(
                    onClick = { radiiMenuOpen = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                    Text(localized("模式", "modes"), modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = radiiMenuOpen, onDismissRequest = { radiiMenuOpen = false }) {
                    DropdownMenuItem(text = { Text(localized("智能离子", "Smart ionic")) }, onClick = {
                        radiiMenuOpen = false
                        tab.lastRadiusSource = RadiusSource.SMART_IONIC
                        rebuildAsync(RadiusSource.SMART_IONIC, tab.bondEpsilon, skipConfirm = false)
                    })
                    DropdownMenuItem(text = { Text(localized("键合半径", "Bonding radius")) }, onClick = {
                        radiiMenuOpen = false
                        tab.lastRadiusSource = RadiusSource.BONDING
                        rebuildAsync(RadiusSource.BONDING, tab.bondEpsilon, skipConfirm = true)
                    })
                    DropdownMenuItem(text = { Text(localized("vdW 半径", "vdW radius")) }, onClick = {
                        radiiMenuOpen = false
                        tab.lastRadiusSource = RadiusSource.VDW
                        rebuildAsync(RadiusSource.VDW, tab.bondEpsilon, skipConfirm = true)
                    })
                }
            }
            // Per v0.6.3: only trigger rebuild on finger release to prevent lag.
            Slider(
                value = sliderEpsilon,
                onValueChange = { v -> sliderEpsilon = v },
                onValueChangeFinished = {
                    tab.bondEpsilon = sliderEpsilon.toDouble()
                    rebuildAsync(tab.lastRadiusSource, tab.bondEpsilon, skipConfirm = true)
                },
                valueRange = 0.1f..0.6f,
                modifier = Modifier.weight(1f),
            )
        }
        Text(epsilonHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // Per v0.8.2: compute co-located site groups for border coloring.
        val gatheredSiteInfo = remember(tab.structure) { computeGatheredSiteInfo(sites) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(visibleRules, key = { it.key }) { rule ->
                val labelA = sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                val labelB = sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                // Per v0.8.2: border precedence — red (Σocc>1) > mixedColor > hbond-gray.
                val gatherA = gatheredSiteInfo[rule.siteA]
                val gatherB = gatheredSiteInfo[rule.siteB]
                val anyNormalized = (gatherA?.wasNormalized == true) || (gatherB?.wasNormalized == true)
                val anyGathered = gatherA != null || gatherB != null
                val borderColor = when {
                    anyNormalized -> Color.Red
                    anyGathered -> Color(0xFF808080) // placeholder mixedColor
                    rule.isHBond -> Color.Gray
                    else -> null
                }
                val rowModifier = Modifier.fillMaxWidth()
                    .clickable { editingRule = rule }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .let { if (borderColor != null) it.border(BorderStroke(1.dp, borderColor), RoundedCornerShape(4.dp)) else it }
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$labelA — $labelB")
                        if (rule.isHBond) Text(
                            localized("氢键", "H-bond"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                        )
                    }
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
        BasicAlertDialog(
            onDismissRequest = {
                // Per v0.6.3: back button cancels computation and reverts epsilon.
                rebuildJob?.cancel()
                loading = false
                rebuildJob = null
                previousEpsilon?.let { tab.bondEpsilon = it }
                previousEpsilon = null
            },
            // Per v0.8.36: loading dialogs dismiss only via the system back button,
            // never by tapping outside.
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        ) {
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
    if (voronoiWarningOpen) {
        AlertDialog(
            onDismissRequest = { voronoiWarningOpen = false },
            title = { Text(voronoiWarningTitle) },
            text = { Text(voronoiWarningMessage) },
            confirmButton = {
                TextButton(onClick = { voronoiWarningOpen = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
    // Per v0.7.1: auto-open the BondRuleDialog when a preset arrives from the draw flow.
    LaunchedEffect(tab.pendingBondDrawRule) {
        if (tab.pendingBondDrawRule != null) { addOpen = true }
    }
    if (addOpen) BondRuleDialog(sites, editingRule = null, preset = tab.pendingBondDrawRule, onDismiss = { addOpen = false; tab.pendingBondDrawRule = null }) { siteA, siteB, min, max, extendAtoB, extendBtoA ->
        tab.pendingBondDrawRule = null
        runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(BondRule(siteA, siteB, min, max, BondRuleSource.CUSTOM, extendAtoB, extendBtoA))) }
            .onSuccess { onStructure(it); addOpen = false }.onFailure { onMessage(it.message ?: "Invalid bond rule") }
    }
    editingRule?.let { rule ->
        BondRuleDialog(sites, editingRule = rule, onDismiss = { editingRule = null }) { siteA, siteB, min, max, extendAtoB, extendBtoA ->
            runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(BondRule(siteA, siteB, min, max, BondRuleSource.CUSTOM, extendAtoB, extendBtoA))) }
                .onSuccess { onStructure(it); editingRule = null }.onFailure { onMessage(it.message ?: "Invalid bond rule") }
        }
    }
}

@Composable
private fun BondRuleDialog(
    sites: List<Site>,
    editingRule: BondRule?,
    preset: BondRule? = null,
    onDismiss: () -> Unit,
    onApply: (String, String, Double, Double, Boolean, Boolean) -> Unit,
) {
    var a by remember { mutableStateOf(preset?.siteA ?: editingRule?.siteA ?: sites.first().id) }
    var b by remember { mutableStateOf(preset?.siteB ?: editingRule?.siteB ?: sites.first().id) }
    val siteA = sites.firstOrNull { it.id == a } ?: sites.first()
    val siteB = sites.firstOrNull { it.id == b } ?: sites.first()
    // Per v0.4.1: default maximum is the sum of the two atoms' bonding radii (键合半径),
    // matching the default bond-rule generator. It is recomputed as the user switches sites so the
    // suggested window tracks the selected pair.
    val defaultMax = PeriodicTable.radius(siteA.species.symbol, RadiusSource.BONDING) +
        PeriodicTable.radius(siteB.species.symbol, RadiusSource.BONDING)
    var minValue by remember { mutableFloatStateOf((preset?.minAngstrom ?: editingRule?.minAngstrom ?: 0.1).toFloat()) }
    // When creating a new rule (no editingRule), maxValue follows the selected pair's default; once
    // the user drags the slider the override sticks until they switch sites again.
    // Per v0.7.1: when a preset is provided (from draw flow), use its max and mark as user-touched.
    var maxValue by remember { mutableFloatStateOf((preset?.maxAngstrom ?: editingRule?.maxAngstrom ?: defaultMax).toFloat()) }
    var userTouchedMax by remember { mutableStateOf(editingRule != null || preset != null) }
    if (!userTouchedMax) {
        // Re-sync the displayed maximum to the current pair's default whenever the user changes A/B
        // without having manually adjusted the slider.
        LaunchedEffect(defaultMax) { maxValue = defaultMax.toFloat() }
    }
    val extendAtoB = editingRule?.extendAtoB ?: false
    val extendBtoA = editingRule?.extendBtoA ?: false
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
            runCatching { onApply(a, b, min, max, extendAtoB, extendBtoA) }.onFailure { /* ignore */ }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text(localized("显示扩胞", "Display supercell"), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        listOf(
            Triple("a", x) { v: Int -> x = v },
            Triple("b", y) { v: Int -> y = v },
            Triple("c", z) { v: Int -> z = v },
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
                tab.structuralExpansion = false
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
    // Per v0.5.2a: press-and-hold Preview callbacks. onPreviewStart hands the in-dialog appearance
    // up so the viewer can render with it; onPreviewEnd restores the dialog.
    onPreviewStart: (ViewerAppearance) -> Unit = {},
    onPreviewEnd: () -> Unit = {},
    backgroundFollowTheme: Boolean = true,
    onBackgroundFollowThemeChange: (Boolean) -> Unit = {},
) {
    var appearance by remember { mutableStateOf(tab.appearance) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var bondColorPickerOpen by remember { mutableStateOf(false) }
    var followTheme by remember { mutableStateOf(backgroundFollowTheme) }
    // Per v0.8.34: previews are 2D Canvas again — no dedicated preview Filament engine to release.
    // Per v0.6.5: precompute dark/light for the follow-theme clickable.
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDarkSurface = (0.299f * surfaceColor.red + 0.587f * surfaceColor.green + 0.114f * surfaceColor.blue) < 0.5f
    // Per v0.5.3a: preview hides the dialog visually (alpha 0) but keeps it mounted so the
    // Preview button's pointerInput survives the press-and-hold and onPreviewEnd fires on release.
    // (v0.5.2a unmounted the dialog on press, killing the gesture → stuck preview.)
    var previewing by remember { mutableStateOf(false) }
    var resetConfirmOpen by remember { mutableStateOf(false) }
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
                // Follow Theme: half-black/half-white circle, default option.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Box(
                        Modifier.size(40.dp).then(
                            if (followTheme) Modifier.border(2.dp, Color(0xFF7542A5), RoundedCornerShape(20.dp)) else Modifier
                        ).clickable {
followTheme = true
// Per v0.6.5: do NOT call onBackgroundFollowThemeChange or update appearance here.
// The theme background is applied only on save or preview.
                        }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(color = Color.Black, startAngle = 90f, sweepAngle = 180f, useCenter = true, size = size)
                            drawArc(color = Color.White, startAngle = 270f, sweepAngle = 180f, useCenter = true, size = size)
                        }
                    }
                    Text(localized("跟随主题", "Follow Theme"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
                listOf(
                    0xFF101014L to localized("深色", "Dark"),
                    0xFFF8F8FBL to localized("浅色", "Light"),
                    0xFF4D2D6EL to localized("紫色", "Purple"),
                ).forEach { (argb, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Box(Modifier.size(40.dp).background(colorFromArgb(argb), RoundedCornerShape(20.dp))
                            .then(if (appearance.backgroundArgb == argb && !followTheme) Modifier.border(2.dp, Color(0xFF7542A5), RoundedCornerShape(20.dp)) else Modifier)
                            .clickable { followTheme = false; appearance = appearance.copy(backgroundArgb = argb) })
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Box(Modifier.size(40.dp)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color.Red, Color(0xFFFFA500), Color.Yellow,
                                    Color.Green, Color.Cyan, Color.Blue,
                                    Color.Magenta, Color.Red,
                                )
                            ),
                            RoundedCornerShape(20.dp),
                        )
                        .then(if (!followTheme) Modifier.border(2.dp, Color(0xFF7542A5), RoundedCornerShape(20.dp)) else Modifier)
                        .clickable { followTheme = false; colorPickerOpen = true })
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
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // Per v0.6.5: Axes section — separate from frame, with position sliders.
            Text(localized("坐标轴", "Axes"), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val axisLabels = listOf("abc", "XYZ")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = appearance.showAxes, onCheckedChange = { appearance = appearance.copy(showAxes = it) })
                if (appearance.showAxes) {
                    Spacer(Modifier.weight(1f))
                    DropdownField(localized("坐标系", "System"), axisLabels[appearance.axisMode.ordinal], axisLabels) {
                        appearance = appearance.copy(axisMode = AxisMode.entries[axisLabels.indexOf(it)])
                    }
                }
            }
            if (appearance.showAxes) {
                LabeledSlider(localized("坐标轴 x 坐标", "Axis X position"), appearance.axisOffsetX, 0f..1f, decimals = 2) { appearance = appearance.copy(axisOffsetX = it) }
                LabeledSlider(localized("坐标轴 y 坐标", "Axis Y position"), appearance.axisOffsetY, 0f..1f, decimals = 2) { appearance = appearance.copy(axisOffsetY = it) }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("原子", "Atoms"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("原子反射", "Atom reflection"), appearance.reflectionEnabled) { appearance = appearance.copy(reflectionEnabled = it) }
            LabeledSlider(localized("原子不透明度", "Atom opacity"), appearance.atomOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(atomOpacity = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("化学键", "Bonds"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            ToggleRow(localized("键反射", "Bond reflection"), appearance.bondReflectionEnabled) { appearance = appearance.copy(bondReflectionEnabled = it) }
            LabeledSlider(localized("键半径", "Bond radius"), appearance.bondRadius, 0.01f..0.15f, decimals = 2) { appearance = appearance.copy(bondRadius = it) }
            LabeledSlider(localized("化学键不透明度", "Bond opacity"), appearance.bondOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(bondOpacity = it) }
            DropdownField(localized("键颜色", "Bond color"), bondColorLabels[appearance.bondColorMode.ordinal], bondColorLabels) { appearance = appearance.copy(bondColorMode = BondColorMode.entries[bondColorLabels.indexOf(it)]) }
            if (appearance.bondColorMode == BondColorMode.UNICOLOR) FlowRow {
                listOf(0xFF9A90A0, 0xFF9966CC, 0xFFFFFFFF, 0xFF333333).forEach { argb -> Box(Modifier.padding(5.dp).size(38.dp).background(colorFromArgb(argb), CircleShape).then(if (appearance.uniformBondArgb == argb) Modifier.border(2.dp, Color(0xFF7542A5), CircleShape) else Modifier).clickable { appearance = appearance.copy(uniformBondArgb = argb) }) }
                // Per v0.5.3: custom colour swatch opens the colour wheel for the uniform bond colour.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp)) {
                    Box(Modifier.size(38.dp)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color.Red, Color(0xFFFFA500), Color.Yellow,
                                    Color.Green, Color.Cyan, Color.Blue,
                                    Color.Magenta, Color.Red,
                                )
                            ),
                            CircleShape,
                        )
                        .clickable { bondColorPickerOpen = true })
                    Text(localized("自定义", "Custom"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("多面体", "Polyhedra"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("多面体反射", "Polyhedron reflection"), appearance.polyhedronReflectionEnabled) { appearance = appearance.copy(polyhedronReflectionEnabled = it) }
            LabeledSlider(localized("多面体不透明度", "Polyhedron opacity"), appearance.polyhedronOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(polyhedronOpacity = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // Per v0.8.30: hydrogen-bond appearance section.
            Text(localized("氢键", "H-Bonds"), fontWeight = FontWeight.Bold)
            LabeledSlider(localized("氢键半径", "H-bond radius"), appearance.hbondRadius, 0.01f..0.15f, decimals = 2) { appearance = appearance.copy(hbondRadius = it) }
            LabeledSlider(localized("氢键不透明度", "H-bond opacity"), appearance.hbondOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(hbondOpacity = it) }
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
                        // The depth-cue mix runs from 1 to 0 as depth increases: far/start -> near/end.
                        LabeledSlider(localized("起始值", "Start"), appearance.dofFar, -5f..5f) { v -> appearance = appearance.copy(dofFar = v.coerceAtMost(appearance.dofNear)) }
                        LabeledSlider(localized("终止值", "End"), appearance.dofNear, -5f..5f) { v -> appearance = appearance.copy(dofNear = v.coerceAtLeast(appearance.dofFar)) }
                    }
                    DepthCueingPreview(appearance, Modifier.padding(start = 10.dp).size(112.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }
        // Per v0.6.3: Restore defaults button at the bottom center.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { resetConfirmOpen = true }) {
                Text(localized("恢复默认设置", "Restore Defaults"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                val previewAppearance = if (followTheme) appearance.copy(backgroundArgb = if (isDarkSurface) 0xFF101014L else 0xFFF8F8FBL) else appearance
                                onPreviewStart(previewAppearance)
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
                onClick = {
                    onBackgroundFollowThemeChange(followTheme)
                    val savedAppearance = if (followTheme) appearance.copy(backgroundArgb = if (isDarkSurface) 0xFF101014L else 0xFFF8F8FBL) else appearance
                    onApplied(savedAppearance); onDismiss()
                },
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

    if (resetConfirmOpen) {
        AlertDialog(
            onDismissRequest = { resetConfirmOpen = false },
            title = { Text(localized("确认", "Confirm")) },
            text = { Text(localized("确认将外观设置为默认设置吗？", "Reset all appearance settings to defaults?")) },
            confirmButton = { TextButton(onClick = { resetConfirmOpen = false; appearance = ViewerAppearance(); followTheme = true }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { resetConfirmOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); androidx.compose.material3.Switch(checked, onChecked) } }
@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, percentage: Boolean = false, steps: Int = 0, decimals: Int = 1, onValue: (Float) -> Unit) {
    Text("$label  ${formatSliderValue(value, percentage, decimals)}")
    Slider(value, onValue, valueRange = range, steps = steps)
}

internal fun formatSliderValue(value: Float, percentage: Boolean = false, decimals: Int = 1): String =
    if (percentage) String.format(Locale.ROOT, "%.0f%%", value * 100)
    else String.format(Locale.ROOT, "%.${decimals}f", value)

@Composable
private fun AtomAppearancePreview(appearance: ViewerAppearance, modifier: Modifier = Modifier) {
    // Per v0.8.34: back to the 2D Canvas preview (the Filament off-screen render of
    // v0.8.32 failed to display in the dialog on device). Sphere grey adapts to the
    // theme: light grey ball on dark surfaces, dark grey ball on light surfaces.
    val bgCompose = MaterialTheme.colorScheme.surfaceVariant
    val isLight = (0.299f * bgCompose.red + 0.587f * bgCompose.green + 0.114f * bgCompose.blue) > 0.5f
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = bgCompose) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val radius = size.minDimension * 0.38f
            val center = Offset(size.width / 2f, size.height / 2f)
            val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
            // Per v0.8.36: fixed dark grey sphere regardless of theme.
            val gray = Color(0xFF5A5A60)
            val sphereColor = gray.copy(alpha = opacity)
            val theta = appearance.lightAzimuth / 180f * PI.toFloat()
            val phi = appearance.lightElevation / 180f * PI.toFloat()
            val cosPhi = cos(phi)
            val lightOffset = radius * .95f * cosPhi
            val highlight = Offset(center.x + cos(theta) * lightOffset, center.y - sin(theta) * lightOffset)
            drawCircle(sphereColor, radius, center)
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

/** Five depth samples (−3, −1.5, 0, +1.5, +3) with the continuous opacity-depth curve plotted above them. */
@Composable
private fun DepthCueingPreview(appearance: ViewerAppearance, modifier: Modifier = Modifier) {
    val bgCompose = MaterialTheme.colorScheme.surfaceVariant
    // Per v0.8.34: the five spheres are drawn as 2D Canvas again (the Filament off-screen
    // render of v0.8.32 failed to display on device); the fog curve + axis labels stay Canvas.
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = bgCompose) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val near = appearance.dofNear.coerceIn(-5f, 5f)
            val far = appearance.dofFar.coerceIn(-5f, 5f)
            val depths = listOf(-3f, -1.5f, 0f, 1.5f, 3f)
            val radius = size.minDimension * 0.105f
            val xStart = radius * 1.25f
            val xEnd = size.width - radius * 1.25f
            val xPositions = depths.indices.map { index ->
                xStart + (xEnd - xStart) * index / depths.lastIndex.toFloat()
            }
            val chartTop = size.height * 0.10f
            val chartBottom = size.height * 0.43f
            val atomY = size.height * 0.73f
            val isLight = (0.299f * bgCompose.red + 0.587f * bgCompose.green + 0.114f * bgCompose.blue) > 0.5f
            val lineColor = if (isLight) Color.Black else Color.White
            val guideColor = lineColor.copy(alpha = 0.48f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
            drawLine(guideColor, Offset(xStart, chartTop), Offset(xEnd, chartTop), 1.2f, pathEffect = dash)
            drawLine(guideColor, Offset(xStart, chartBottom), Offset(xEnd, chartBottom), 1.2f, pathEffect = dash)

            // Continuous opacity-depth curve: y plots OPACITY (1−fog), not fog.
            // fog=0 (full opacity) → top; fog=1 (transparent) → bottom.
            val samples = 60
            val curve = Path().apply {
                for (i in 0..samples) {
                    val d = -3f + 6f * i / samples
                    val fog = depthCueFog(d, near, far)
                    val y = chartTop + fog * (chartBottom - chartTop)
                    val x = xStart + (xEnd - xStart) * i / samples
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(curve, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))

            depths.forEachIndexed { index, depth ->
                val fog = depthCueFog(depth, near, far)
                val y = chartTop + fog * (chartBottom - chartTop)
                drawCircle(lineColor, 2.4f, Offset(xPositions[index], y))
                drawPreviewSphere(Offset(xPositions[index], atomY), radius, appearance, fog, bgCompose)
            }

            val nc = drawContext.canvas.nativeCanvas
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLight) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
                textSize = radius * 0.72f
            }
            // Opacity axis labels (1 = top/full opacity, 0 = bottom/transparent).
            nc.drawText("1", xStart - radius, chartTop + p.textSize * 0.35f, p)
            nc.drawText("0", xStart - radius, chartBottom + p.textSize * 0.35f, p)
            // Depth axis labels: only −3, 0, +3 (skip −1.5 and +1.5).
            val labelY = atomY + radius * 1.8f
            depths.forEachIndexed { index, depth ->
                if (depth == -1.5f || depth == 1.5f) return@forEachIndexed
                val label = depth.toInt().toString()
                val tw = p.measureText(label)
                nc.drawText(label, xPositions[index] - tw / 2f, labelY, p)
            }
        }
    }
}

/** Draws one lit preview sphere at [c] with radius [r], world-light highlight; colour blends
 *  toward [bg] by [fog] (opacity unchanged), mirroring the renderer's depth cueing.
 *  Per v0.8.34: sphere grey adapts to the theme (light grey on dark, dark grey on light). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPreviewSphere(
    c: Offset, r: Float, appearance: ViewerAppearance, fog: Float, bg: Color,
) {
    val isLight = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) > 0.5f
    // Per v0.8.36: fixed dark grey sphere regardless of theme.
    val base = Color(0xFF5A5A60)
    drawCircle(base.blend(bg, fog), r, c)
    if (appearance.reflectionEnabled) {
        val theta = appearance.lightAzimuth / 180f * PI.toFloat()
        val phi = appearance.lightElevation / 180f * PI.toFloat()
        val cosPhi = cos(phi)
        val highlight = Offset(c.x + cos(theta) * cosPhi * r * .95f, c.y - sin(theta) * cosPhi * r * .95f)
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
internal fun DropdownField(label: String, value: String, options: List<String>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().clickable { open = true })
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onValue(option) }) } }
    }
}

private fun eval(value: String) = ExpressionParser(value).evaluate()
private fun fmt(value: Double) = "%.4f".format(value)

/** Per v0.8.2: identifies sites sharing the same fractional coordinates (disorder). */
private data class GatheredSiteInfo(val wasNormalized: Boolean)
private fun computeGatheredSiteInfo(sites: List<com.krystals.crystal.core.model.Site>): Map<String, GatheredSiteInfo> {
    val tol = 1e-4
    val byPos = linkedMapOf<Triple<Int, Int, Int>, MutableList<com.krystals.crystal.core.model.Site>>()
    for (s in sites) {
        val key = Triple((s.fractionalCoordinate.x / tol).toInt(), (s.fractionalCoordinate.y / tol).toInt(), (s.fractionalCoordinate.z / tol).toInt())
        byPos.getOrPut(key) { mutableListOf() }.add(s)
    }
    val result = mutableMapOf<String, GatheredSiteInfo>()
    for ((_, bucket) in byPos) {
        val distinctIds = bucket.map { it.id }.distinct()
        if (distinctIds.size < 2) continue
        val rawSum = bucket.sumOf { it.occupancy }
        val info = GatheredSiteInfo(wasNormalized = rawSum > 1.0)
        for (site in bucket) result[site.id] = info
    }
    return result
}

/** Per v0.8.11: identifies co-located site groups and computes their border color.
 *  Returns (wasNormalized: Boolean, mixedColor: Long) per site id.
 *  mixedColor = occ-weighted ARGB blend of all member site colors.
 *  wasNormalized = true when raw Σocc > 1 → red border. */
private fun computeGatheredSiteBorders(sites: List<com.krystals.crystal.core.model.Site>, config: com.krystals.renderer.core.style.RenderConfiguration): Map<String, Pair<Boolean, Long>> {
    val tol = 1e-4
    val byPos = linkedMapOf<Triple<Int, Int, Int>, MutableList<com.krystals.crystal.core.model.Site>>()
    for (s in sites) {
        val key = Triple((s.fractionalCoordinate.x / tol).toInt(), (s.fractionalCoordinate.y / tol).toInt(), (s.fractionalCoordinate.z / tol).toInt())
        byPos.getOrPut(key) { mutableListOf() }.add(s)
    }
    val result = mutableMapOf<String, Pair<Boolean, Long>>()
    for ((_, bucket) in byPos) {
        val distinctIds = bucket.map { it.id }.distinct()
        if (distinctIds.size < 2) continue
        val rawSum = bucket.sumOf { it.occupancy }
        val wasNormalized = rawSum > 1.0
        val scale = if (wasNormalized) 1.0 / rawSum else 1.0
        // Occ-weighted ARGB blend (same algorithm as GatheredAtomGrouper.mixColors)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (s in bucket) {
            val argb = RenderPalette.resolveArgb(s.species.symbol, config)
            val wt = s.occupancy * scale
            r += ((argb ushr 16) and 0xFF).toInt() * wt
            g += ((argb ushr 8) and 0xFF).toInt() * wt
            b += (argb and 0xFF).toInt() * wt
            w += wt
        }
        val iw = if (w > 0.0) 1.0 / w else 1.0
        val ir = (r * iw).toInt().coerceIn(0, 255)
        val ig = (g * iw).toInt().coerceIn(0, 255)
        val ib = (b * iw).toInt().coerceIn(0, 255)
        val mixedColor = (0xFFL shl 24) or (ir.toLong() shl 16) or (ig.toLong() shl 8) or ib.toLong()
        for (s in bucket) result[s.id] = wasNormalized to mixedColor
    }
    return result
}
private fun colorFromArgb(value: Long) = Color(value)
