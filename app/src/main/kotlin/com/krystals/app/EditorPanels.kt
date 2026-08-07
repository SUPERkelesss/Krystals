@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.VoronoiSearchLimitExceededException
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.ExpressionParser
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.data.BravaisLatticeData
import com.krystals.renderer.core.style.RenderPalette
import kotlin.math.max
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue

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
    var selectedTab by remember {
        mutableStateOf(
            when {
                tab.editingSiteId != null -> EditorTab.ATOMS
                tab.pendingEditorTab == "atoms" -> EditorTab.ATOMS
                tab.pendingEditorTab == "bonds" -> EditorTab.BONDS
                tab.pendingEditorTab == "expansion" -> EditorTab.EXPANSION
                // Per v0.8.27: otherwise remember this tab's last sub-menu; a new tab
                // (or never-opened) falls through to the first sub-menu (BASIC).
                tab.rememberedEditorTab != null -> runCatching {
                    EditorTab.valueOf(tab.rememberedEditorTab!!)
                }.getOrNull() ?: EditorTab.BASIC
                else -> EditorTab.BASIC
            }
        )
    }
    // Per v0.7.1: consume the pending tab hint once the editor opens.
    LaunchedEffect(Unit) {
        tab.pendingEditorTab = null
        // Per v0.8.27: an explicit intent (double-tap atom, add-bond, ...) is also a
        // sub-menu switch — sync the remembered tab so reopening shows what the user
        // actually last saw, not a stale memory.
        if (tab.rememberedEditorTab != selectedTab.name) {
            tab.rememberedEditorTab = selectedTab.name
        }
    }
    ResizableSlidePanel(
        ratioKey = "editor_panel_ratio",
        defaultRatio = 0.62f,
        onDismiss = onDismiss,
    ) { closePanel ->
        Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            // Per v0.8.27: sub-menu selector is a fixed-height horizontally-scrollable
            // row; the close button stays pinned at the right edge (outside the scroll).
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        EditorTab.BASIC to stringResource(R.string.basic_info), EditorTab.ATOMS to stringResource(R.string.atoms),
                        EditorTab.BONDS to stringResource(R.string.bonds), EditorTab.EXPANSION to stringResource(R.string.expand_cell),
                    ).forEach { (kind, label) ->
                        FilterChip(
                            selectedTab == kind,
                            onClick = {
                                selectedTab = kind
                                // Per v0.8.27: remember per-tab so reopening the editor
                                // restores the sub-menu the user last had open.
                                tab.rememberedEditorTab = kind.name
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 3.dp),
                        )
                    }
                }
                IconButton(onClick = { closePanel() }) { Icon(Icons.Default.Close, null) }
            }
            when (selectedTab) {
                EditorTab.BASIC -> BasicEditor(tab, onStructure, onMessage, onRunBondComputation)
                EditorTab.ATOMS -> AtomEditor(tab, { closePanel() }, onStructure, onMessage, onRunBondComputation, onPersistentMessage)
                EditorTab.BONDS -> BondEditor(tab, onStructure, onMessage, { closePanel() }, onPersistentMessage)
                EditorTab.EXPANSION -> ExpansionEditor(tab, onMessage, onRunBondComputation)
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
                            // Per v0.8.40: spglib contributes ONLY the space-group number
                            // (correcting a mislabelled cell); the actual matrix conversion
                            // runs through CrystalEditor.convertToConventional (BravaisLatticeData
                            // tables). The v0.8.39 spglib-raw-data path was removed — it built
                            // sites with per-species ids ("spg:P1" for both Na1 and Cl1) which
                            // crashed LazyColumn with "Key already used", and its species
                            // handling was unreliable.
                            val sgNumber = runCatching { SpglibStructure.toConventionalCell(tab.structure) }
                                .getOrNull()?.spaceGroupNumber?.takeIf { it > 0 }
                            val corrected = sgNumber?.let { n ->
                                SpaceGroupCatalog.all.getOrNull(n - 1)?.takeIf { it.number != tab.structure.spaceGroup.number }
                                    ?.let { tab.structure.copy(spaceGroup = it) }
                            } ?: tab.structure
                            runCatching { CrystalEditor.convertToConventional(corrected, tab.bondConfiguration) }
                                .getOrElse { onMessage(it.message ?: "Conversion failed"); return@OutlinedButton }
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
                            // Per v0.8.40: spglib contributes ONLY the space-group number;
                            // matrix conversion runs through CrystalEditor.convertToPrimitive
                            // (BravaisLatticeData tables). See conventional branch for why the
                            // v0.8.39 spglib-raw-data path was removed.
                            val sgNumber = runCatching { SpglibStructure.toPrimitiveCell(tab.structure) }
                                .getOrNull()?.spaceGroupNumber?.takeIf { it > 0 }
                            val corrected = sgNumber?.let { n ->
                                SpaceGroupCatalog.all.getOrNull(n - 1)?.takeIf { it.number != tab.structure.spaceGroup.number }
                                    ?.let { tab.structure.copy(spaceGroup = it) }
                            } ?: tab.structure
                            runCatching { CrystalEditor.convertToPrimitive(corrected, tab.bondConfiguration) }
                                .getOrElse { onMessage(it.message ?: "Conversion failed"); return@OutlinedButton }
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
    var manualNewAtom by remember { mutableStateOf(false) }
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
        val siteGroups = remember(tab.structure) { computeGatheredSiteInfo(tab.structure.sites, tab.renderConfiguration) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(tab.structure.sites, key = { it.id }) { site ->
                val groupInfo = siteGroups[site.id]
                val borderMod = if (groupInfo != null) {
                    // Per v0.8.11: co-located (same-position) site groups get a mixedColor border;
                    // raw Σocc > 1 gets a thicker RED border.
                    val borderColor = if (groupInfo.wasNormalized) Color(0xFFFF4444) else Color(groupInfo.mixedColor ?: 0xFFCCCCCC)
                    Modifier.border(if (groupInfo.wasNormalized) 3.dp else 2.dp, borderColor, RoundedCornerShape(8.dp))
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
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        // Per inspection-window layout: [element] [label] [occ] / (x, y, z).
                        Text("${site.species.symbol}  ${site.label}  occ ${site.occupancy}")
                        Text("(${fmt(site.fractionalCoordinate.x)}, ${fmt(site.fractionalCoordinate.y)}, ${fmt(site.fractionalCoordinate.z)})", style = MaterialTheme.typography.bodySmall)
                    }
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
    // Per user spec: adding an atom must NOT modify bond rules or auto-apply them —
    // AddAtom preserves existing rules (v0.7.1), no ensureAutoBondRules here.
    val applyNewAtom: (String, String, FractionalCoordinate, Double, () -> Unit) -> Unit = { label, chosen, frac, occupancy, close ->
        runCatching {
            CrystalEditor.apply(
                tab.structure,
                tab.bondConfiguration,
                EditCommand.AddAtom(Species(chosen), label, frac, occupancy),
            )
        }
            .onSuccess { onStructure(it); it.warnings.forEach(onMessage); close() }
            .onFailure { onMessage(it.message ?: "Invalid atom") }
    }
    if (periodicOpen) PeriodicTableDialog(
        onDismiss = { periodicOpen = false },
        onElement = { element -> periodicOpen = false; newElement = element },
        onManualInput = { periodicOpen = false; manualNewAtom = true },
    )
    newElement?.let { element -> AtomDialog(null, element, tab.structure.sites, onDismiss = { newElement = null }) { label, chosen, frac, occupancy ->
        applyNewAtom(label, chosen, frac, occupancy) { newElement = null }
    } }
    if (manualNewAtom) AtomDialog(null, "", tab.structure.sites, onDismiss = { manualNewAtom = false }) { label, chosen, frac, occupancy ->
        applyNewAtom(label, chosen, frac, occupancy) { manualNewAtom = false }
    }
    atomDialog?.let { site -> AtomDialog(site, site.species.symbol, tab.structure.sites, onDismiss = { atomDialog = null }) { label, chosen, frac, occupancy ->
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
private fun AtomDialog(site: Site?, initialElement: String, sites: List<Site>, onDismiss: () -> Unit, onApply: (String, String, FractionalCoordinate, Double) -> Unit) {
    val isNew = site == null
    var element by remember(site) { mutableStateOf(initialElement) }
    // New atoms: label auto-suggests the CrystalEditor.uniqueLabel scheme (X, X2, X3...) and follows
    // the element field while it changes — until the user edits the label field themselves.
    var labelTouched by remember(site) { mutableStateOf(!isNew) }
    var label by remember(site) { mutableStateOf(if (isNew) suggestedNewAtomLabel(sites, initialElement) else site!!.label) }
    var x by remember(site) { mutableStateOf(site?.fractionalCoordinate?.x?.toString() ?: "0") }; var y by remember(site) { mutableStateOf(site?.fractionalCoordinate?.y?.toString() ?: "0") }
    var z by remember(site) { mutableStateOf(site?.fractionalCoordinate?.z?.toString() ?: "0") }; var occupancy by remember(site) { mutableStateOf(site?.occupancy?.toString() ?: "1") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (site == null) localized("新建原子", "New atom") else localized("修改原子", "Modify atom")) }, text = { Column {
        OutlinedTextField(element, { newElement -> element = newElement; if (isNew && !labelTouched) label = suggestedNewAtomLabel(sites, newElement) }, label = { Text(localized("元素", "Element")) })
        OutlinedTextField(label, { label = it; labelTouched = true }, label = { Text(localized("标签", "Label")) })
        Row { CellField("x", x, { x = it }, true, Modifier.weight(1f)); CellField("y", y, { y = it }, true, Modifier.weight(1f)); CellField("z", z, { z = it }, true, Modifier.weight(1f)) }
        OutlinedTextField(occupancy, { occupancy = it }, label = { Text(localized("占据率", "Occupancy")) })
    } }, confirmButton = { TextButton(onClick = { runCatching { onApply(label, element, FractionalCoordinate(eval(x), eval(y), eval(z)), eval(occupancy)) } }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** New-atom label auto-suggestion matching CrystalEditor.uniqueLabel: X, X2, X3... */
private fun suggestedNewAtomLabel(sites: List<Site>, element: String): String {
    val e = element.trim()
    return if (e.isEmpty()) "" else CrystalEditor.uniqueLabel(e, sites)
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
private fun PeriodicTableDialog(onDismiss: () -> Unit, onElement: (String) -> Unit, onManualInput: () -> Unit) {
    val cellWidth = 44.dp
    val cellH = 46.dp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("元素周期表", "Periodic table")) },
        text = {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(cellWidth * 18).verticalScroll(rememberScrollState())) {
                    periodicTableLayout.forEach { row ->
                        Row { row.forEach { PeriodicCell(cellWidth, cellH, it, onElement) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        repeat(2) { Box(Modifier.width(cellWidth).height(cellH)) }
                        lanthanides.forEach { PeriodicCell(cellWidth, cellH, it, onElement) }
                        Box(Modifier.width(cellWidth).height(cellH))
                    }
                    Row {
                        repeat(2) { Box(Modifier.width(cellWidth).height(cellH)) }
                        actinides.forEach { PeriodicCell(cellWidth, cellH, it, onElement) }
                        Box(Modifier.width(cellWidth).height(cellH))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        dismissButton = { TextButton(onClick = onManualInput) { Text(localized("手动输入", "Manual input")) } },
    )
}


@Composable
private fun PeriodicCell(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, symbol: String?, onElement: (String) -> Unit) {
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

// Per v0.8.x: bond editor sub-sections — covalent bonds vs hydrogen bonds. The H-bond
// section appears only when the structure contains hydrogen.
private enum class BondSection { NORMAL, HBOND }

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
        // Per v0.8.x: covalent / H-bond sub-sections. The H-bond section appears only when the
        // structure contains hydrogen and sits after the covalent section. Both sections share
        // the same New/Draw/Delete actions — the draw/delete target type flag
        // (tab.bondDrawTargetIsHbond) is set before entering either flow.
        val hasHydrogen = sites.any { it.species.symbol == "H" }
        val covalentBondsLabel = localized("化学键", "Covalent bonds")
        val hbondsLabel = localized("氢键", "H-bonds")
        var bondSection by remember { mutableStateOf(BondSection.NORMAL) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            val optionCount = if (hasHydrogen) 2 else 1
            SegmentedButton(
                selected = bondSection == BondSection.NORMAL,
                onClick = { bondSection = BondSection.NORMAL },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = optionCount),
            ) { Text(covalentBondsLabel, maxLines = 1) }
            if (hasHydrogen) {
                SegmentedButton(
                    selected = bondSection == BondSection.HBOND,
                    onClick = { bondSection = BondSection.HBOND },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(hbondsLabel, maxLines = 1) }
            }
        }
        // Per v0.5.0: "自动应用半径" (auto-apply radii) clears every bond rule and regenerates them
        // from one of three radius sources (smart-ionic default / bonding / vdW).
        // Per v0.7.1: "绘制" (draw) and "删除" (delete) buttons replace the old auto-apply button position.
        // Auto-apply is now a compact row with a Switch + source selector + ε slider.
        val isHbondSection = bondSection == BondSection.HBOND
        val drawHint = localized(
            if (isHbondSection) "点击成键的两个目标原子（氢键）" else "点击成键的两个目标原子",
            if (isHbondSection) "Tap two atoms to bond (H-bond)" else "Tap two atoms to bond",
        )
        val deleteHint = localized(
            if (isHbondSection) "点击要删除氢键的两个原子" else "点击要删除键的两个原子",
            if (isHbondSection) "Tap two atoms to delete their H-bond" else "Tap two atoms to delete their bond",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                tab.bondDrawTargetIsHbond = isHbondSection
                addOpen = true
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(localized("新建", "New")) }
            OutlinedButton(onClick = {
                tab.bondDrawTargetIsHbond = isHbondSection
                tab.bondDrawMode = BondDrawMode.DRAWING
                tab.bondDrawFirstSiteId = null
                tab.bondDrawFirstCartesian = null
                tab.selectedAtomIds = emptyList()
                onPersistentMessage(drawHint)
                onDismiss()
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Create, null); Text(localized("绘制", "Draw")) }
            OutlinedButton(onClick = {
                tab.bondDrawTargetIsHbond = isHbondSection
                tab.bondDrawMode = BondDrawMode.DELETING
                tab.bondDrawFirstSiteId = null
                tab.bondDrawFirstCartesian = null
                tab.selectedAtomIds = emptyList()
                onPersistentMessage(deleteHint)
                onDismiss()
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Text(localized("删除", "Delete")) }
        }
        if (bondSection == BondSection.NORMAL) {
            // Per v0.7.1: label on first line, mode button + slider on second line.
            var sliderEpsilon by remember(tab.bondEpsilon) { mutableFloatStateOf(tab.bondEpsilon.toFloat().coerceIn(0.1f, 0.6f)) }
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
        } else {
            // Per v0.8.x: H-bond angle threshold (display filter, 0–180°, default 110°).
            // Replaces the auto-apply area — H-bonds have no "auto-generate rules" section.
            // The scene rebuilds on commit (tab.hbondAngleThreshold keys the scene-build
            // effect in ViewerScreen), so dragging only updates the local slider value.
            var sliderHbondThreshold by remember(tab.hbondAngleThreshold) { mutableFloatStateOf(tab.hbondAngleThreshold.toFloat().coerceIn(0f, 180f)) }
            Text(
                localized("角度阈值", "Angle threshold") + ": %.0f°".format(sliderHbondThreshold),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            Slider(
                value = sliderHbondThreshold,
                onValueChange = { v -> sliderHbondThreshold = v },
                onValueChangeFinished = { tab.hbondAngleThreshold = sliderHbondThreshold.toDouble() },
                valueRange = 0f..180f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                localized("仅显示 D–H···A 角度大于阈值的氢键", "Show only H-bonds whose D–H···A angle exceeds the threshold"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        // Per v0.8.2: compute co-located site groups for border coloring.
        val gatheredSiteInfo = remember(tab.structure) { computeGatheredSiteInfo(sites, tab.renderConfiguration) }
        // Per v0.8.x: each section lists only its own rules — hbonds no longer mix into the
        // covalent list. Rows keep their existing structure (incl. the "H-bond" grey label).
        val sectionRules = if (bondSection == BondSection.HBOND) {
            visibleRules.filter { it.isHBond }
        } else {
            visibleRules.filter { !it.isHBond }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sectionRules, key = { it.key }) { rule ->
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
    // Per v0.8.x: the dialog's hbond mode follows the edit target — the draw preset's rule type,
    // the edited rule's type, or the section the user pressed New in (bondDrawTargetIsHbond).
    if (addOpen) BondRuleDialog(
        sites,
        editingRule = null,
        preset = tab.pendingBondDrawRule,
        hbond = tab.pendingBondDrawRule?.isHBond == true || tab.bondDrawTargetIsHbond,
        onDismiss = { addOpen = false; tab.pendingBondDrawRule = null },
    ) { siteA, siteB, min, max, extendAtoB, extendBtoA, isHBond ->
        tab.pendingBondDrawRule = null
        runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(BondRule(siteA, siteB, min, max, BondRuleSource.CUSTOM, extendAtoB, extendBtoA, isHBond))) }
            .onSuccess { onStructure(it); addOpen = false }.onFailure { onMessage(it.message ?: "Invalid bond rule") }
    }
    editingRule?.let { rule ->
        BondRuleDialog(sites, editingRule = rule, hbond = rule.isHBond, onDismiss = { editingRule = null }) { siteA, siteB, min, max, extendAtoB, extendBtoA, isHBond ->
            runCatching { CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(BondRule(siteA, siteB, min, max, BondRuleSource.CUSTOM, extendAtoB, extendBtoA, isHBond))) }
                .onSuccess { onStructure(it); editingRule = null }.onFailure { onMessage(it.message ?: "Invalid bond rule") }
        }
    }
}


@Composable
private fun BondRuleDialog(
    sites: List<Site>,
    editingRule: BondRule?,
    preset: BondRule? = null,
    // Per v0.8.x: hbond mode — hydrogen-bond title and the isHBond flag passed back via onApply.
    hbond: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (String, String, Double, Double, Boolean, Boolean, Boolean) -> Unit,
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
        title = {
            Text(
                if (editingRule == null) {
                    localized(if (hbond) "新建氢键规则" else "新建化学键规则", if (hbond) "New H-bond rule" else "New bond rule")
                } else {
                    localized(if (hbond) "修改氢键规则" else "修改化学键规则", if (hbond) "Edit H-bond rule" else "Edit bond rule")
                }
            )
        },
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
            runCatching { onApply(a, b, min, max, extendAtoB, extendBtoA, hbond) }.onFailure { /* ignore */ }
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
    var x by remember(tab.expansion) { mutableIntStateOf(tab.expansion.x) }
    var y by remember(tab.expansion) { mutableIntStateOf(tab.expansion.y) }
    var z by remember(tab.expansion) { mutableIntStateOf(tab.expansion.z) }
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


private fun eval(value: String) = ExpressionParser(value).evaluate()

private fun fmt(value: Double) = "%.4f".format(value)

/** Per v0.8.2/v0.8.11: identifies sites sharing the same fractional coordinates (disorder).
 *  mixedColor = occ-weighted ARGB blend of all member site colors (null when unavailable);
 *  wasNormalized = true when raw Σocc > 1 → red border. */

private data class GatheredSiteInfo(val wasNormalized: Boolean, val mixedColor: Long?)

/** Per v0.8.2/v0.8.11: compute co-located site groups and their border info for both the AtomEditor
 *  (mixedColor border) and BondEditor (wasNormalized only) lists. */

private fun computeGatheredSiteInfo(sites: List<com.krystals.crystal.core.model.Site>, config: com.krystals.renderer.core.style.RenderConfiguration): Map<String, GatheredSiteInfo> {
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
        val info = GatheredSiteInfo(wasNormalized = wasNormalized, mixedColor = mixedColor)
        for (site in bucket) result[site.id] = info
    }
    return result
}

internal fun colorFromArgb(value: Long) = Color(value)

/** Per v0.8.27: rainbow hue order shared by the background/bond colour wheels and the colour picker. */
