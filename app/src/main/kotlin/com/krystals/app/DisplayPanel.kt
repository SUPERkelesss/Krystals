@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.structure.StructureAnalyzer
import com.krystals.crystal.data.PeriodicTableData
import com.krystals.renderer.core.style.RenderPalette

@Composable
internal fun DisplayPanel(tab: DocumentTab, viewModel: KrystalsViewModel, onDismiss: () -> Unit) {
    val sites = tab.structure.sites
    val siteIds = remember(tab.structure) { sites.map { it.id }.toSet() }
    // Per v0.2.3: per-site atom color overrides, edited in the ATOMS sub-menu.
    var colorPickerOpen by remember { mutableStateOf(false) }
    var colorPickerTarget by remember { mutableStateOf<String?>(null) }
    // Per v0.3.44: ATOMS/POLYHEDRA group color override — when set, the chosen color is applied to
    // every site of the target element (written into siteArgbOverrides for each site of that element).
    var groupColorPickerElement by remember { mutableStateOf<String?>(null) }
    val rules = tab.bondConfiguration.rules
    // Per v0.5.2b: expand + grid once for the BONDS tab's hasMatchingBond filter (MOF-scale cells).
    val bondGrid = remember(tab.structure) {
        val atoms = SymmetryExpander.expand(tab.structure)
        BondGrid(atoms, tab.structure, BondRuleMatching.estimateCellSize(tab.structure)) to atoms
    }
    val allSitesVisible = siteIds.isNotEmpty() && tab.visibility.hiddenSites.intersect(siteIds).isEmpty()
    val normalRules = remember(rules) { rules.filter { !it.isHBond } }
    val hbondRules = remember(rules) { rules.filter { it.isHBond } }
    val allBondsVisible = tab.visibility.showBonds && tab.visibility.hiddenBondPairs.none { key -> normalRules.any { it.key == key } }
    val allHbondsVisible = tab.visibility.showBonds && tab.visibility.hiddenBondPairs.none { key -> hbondRules.any { it.key == key } }
    val allPolyhedraEnabled = siteIds.isNotEmpty() && siteIds.all { it in tab.visibility.polyhedronSites }
    var selected by remember { mutableStateOf(DisplayTab.ATOMS) }
    // Per v0.8.1: if HBONDS tab is selected but no hbond rules exist anymore, fall back to BONDS.
    if (selected == DisplayTab.HBONDS && hbondRules.isEmpty()) selected = DisplayTab.BONDS
    // Per v0.3.44: per-group collapse state for the ATOMS/POLYHEDRA/BONDS grouped lists. Keyed by
    // element (ATOMS/POLYHEDRA) or element-pair (BONDS). A group is expanded when its key is absent
    // (default expanded); toggling inserts/removes the key.
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    ResizableSlidePanel(
        ratioKey = "display_panel_ratio",
        defaultRatio = 0.40f,
        onDismiss = onDismiss,
    ) { closePanel ->
        Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val tabEntries = buildList {
                            add(DisplayTab.ATOMS to localized("原子", "Atoms"))
                            add(DisplayTab.BONDS to localized("化学键", "Bonds"))
                            add(DisplayTab.POLYHEDRA to localized("多面体", "Polyhedra"))
                            if (hbondRules.isNotEmpty()) add(DisplayTab.HBONDS to localized("氢键", "H-Bonds"))
                        }
                        tabEntries.forEach { (kind, label) -> FilterChip(selected == kind, onClick = { selected = kind }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                        Spacer(Modifier.weight(1f))
                        // Per molecule-extend: 按分子展开总开关(仅分子晶体),所有子菜单共享;
                        // 启用时"扩展到晶胞外"规则失效(BONDS tab 灰显)。
                        if (tab.isMolecularCrystal) {
                            Checkbox(tab.moleculeExtend, onCheckedChange = { tab.moleculeExtend = it })
                            Text(localized("按分子展开", "Expand by Molecule"), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { closePanel() }) { Icon(Icons.Default.Close, null) }
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        when (selected) {
                            DisplayTab.ATOMS -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allSitesVisible, onCheckedChange = { checked ->
                                        tab.recordHistory()
                                        tab.visibility = tab.visibility.copy(hiddenSites = if (checked) emptySet() else siteIds)
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { tab.recordHistory(); tab.visibility = tab.visibility.copy(hiddenSites = siteIds - tab.visibility.hiddenSites) }) { Text(localized("反选", "Invert")) }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                // Per v0.3.44: group sites by element. Each group is a collapsible header
                                // (expand/collapse + group checkbox + element label + group color swatch +
                                // count) followed by the per-site rows when expanded.
                                val groupedSites = remember(sites) { sites.groupBy { it.species.symbol }.toSortedMap() }
                                groupedSites.forEach { (element, groupSites) ->
                                    val expanded = collapsedGroups["A:$element"] != true
                                    val allGroupVisible = groupSites.all { it.id !in tab.visibility.hiddenSites }
                                    CollapsibleGroupHeader(
                                        title = "$element (${groupSites.size})",
                                        expanded = expanded,
                                        onToggle = { collapsedGroups["A:$element"] = expanded },
                                        checked = allGroupVisible,
                                        onCheckChange = { checked ->
                                            tab.recordHistory()
                                            tab.visibility = tab.visibility.copy(
                                                hiddenSites = if (checked) tab.visibility.hiddenSites - groupSites.map { it.id }.toSet()
                                                else tab.visibility.hiddenSites + groupSites.map { it.id }.toSet(),
                                            )
                                        },
                                    ) {
                                        val argb = RenderPalette.resolveSiteArgb(
                                            groupSites.first().id,
                                            element,
                                            tab.renderConfiguration,
                                        )
                                        Box(
                                            Modifier.size(22.dp).background(Color(argb), CircleShape).clickable { groupColorPickerElement = element; colorPickerOpen = true }
                                        )
                                    }
                                    if (expanded) {
                                        groupSites.forEach { site ->
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp, top = 2.dp, bottom = 2.dp)) {
                                                val visible = site.id !in tab.visibility.hiddenSites
                                                Checkbox(visible, onCheckedChange = { checked ->
                                                    tab.recordHistory()
                                                    tab.visibility = tab.visibility.copy(hiddenSites = if (checked) tab.visibility.hiddenSites - site.id else tab.visibility.hiddenSites + site.id)
                                                })
                                                Text(site.label, modifier = Modifier.weight(1f))
                                                val siteArgb = RenderPalette.resolveSiteArgb(
                                                    site.id,
                                                    site.species.symbol,
                                                    tab.renderConfiguration,
                                                )
                                                Box(
                                                    Modifier.size(20.dp).background(Color(siteArgb), CircleShape).clickable { colorPickerTarget = site.id; colorPickerOpen = true }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            DisplayTab.BONDS -> {
                                // Per v0.8.1: BONDS tab lists only non-hbond rules; hbonds have their own tab.
                                val normalWithMatch = normalRules.filter { rule ->
                                    BondRuleMatching.hasMatchingBond(
                                        rule,
                                        tab.structure,
                                        tab.bondConfiguration,
                                        bondGrid.second,
                                        bondGrid.first,
                                    )
                                }.distinctBy { it.key } // Per v0.5.4b: see EditorPanels — duplicate
                                // site-pair keys crash the LazyColumn with "Key was already used".
                                // Per v0.6.5: removed top-level "extend across cell" checkbox;
                                // directional extend controls are now per-rule and per-group.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allBondsVisible, onCheckedChange = { checked ->
                                        tab.recordHistory()
                                        tab.visibility = tab.visibility.copy(
                                            showBonds = checked,
                                            hiddenBondPairs = if (checked) emptySet() else normalWithMatch.map { it.key }.toSet(),
                                        )
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(onClick = {
                                        val allKeys = normalWithMatch.map { it.key }.toSet()
                                        tab.recordHistory()
                                        tab.visibility = tab.visibility.copy(showBonds = true, hiddenBondPairs = allKeys - tab.visibility.hiddenBondPairs)
                                    }) { Text(localized("反选", "Invert")) }
                                    Spacer(Modifier.weight(1f))
                                    // Per v0.6.5: "Extend outside cell" checkbox — toggles all bond rules' extend flags.
                                    val allExtended = normalWithMatch.isNotEmpty() && normalWithMatch.all { it.extendAtoB && it.extendBtoA }
                                    Checkbox(allExtended, onCheckedChange = { checked ->
                                        var working = tab.bondConfiguration
                                        normalWithMatch.forEach { rule ->
                                            val updated = rule.copy(extendAtoB = checked, extendBtoA = checked)
                                            working = CrystalEditor.apply(tab.structure, working, EditCommand.SetBondRule(updated)).bondConfiguration
                                        }
                                        viewModel.updateAnalysis(tab, EditResult(tab.structure, working))
                                    })
                                    Text(localized("扩展到晶胞外", "Extend Outside Cell"), style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                if (normalWithMatch.isEmpty()) {
                                    Text(localized("无化学键规则", "No bond rules"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                } else {
                                    // Per v0.3.44: group bond rules by element pair (e.g. C-O, Cs-Cl). Each
                                    // group is a collapsible header (group visibility checkbox) + per-rule rows.
                                    val elementOf = remember(sites) { sites.associate { it.id to it.species.symbol } }
                                    val groupedRules = remember(normalWithMatch, elementOf) {
                                        normalWithMatch.groupBy { rule ->
                                            val ea = elementOf[rule.siteA] ?: "?"
                                            val eb = elementOf[rule.siteB] ?: "?"
                                            // Per v0.7.1: metal first in pair label; if same type, larger atomic number first.
                                            val eaMetal = PeriodicTableData.isMetal(ea)
                                            val ebMetal = PeriodicTableData.isMetal(eb)
                                            val (first, second) = when {
                                                eaMetal && !ebMetal -> ea to eb
                                                !eaMetal && ebMetal -> eb to ea
                                                else -> {
                                                    val eaNum = PeriodicTableData.symbols.indexOf(ea)
                                                    val ebNum = PeriodicTableData.symbols.indexOf(eb)
                                                    if (eaNum >= ebNum) ea to eb else eb to ea
                                                }
                                            }
                                            "$first—$second"
                                        }.toSortedMap(
                                            compareBy(
                                                { if (PeriodicTableData.isMetal(it.split("—").getOrNull(0) ?: "?")) 0 else 1 },
                                                { if (PeriodicTableData.isMetal(it.split("—").getOrNull(1) ?: "?")) 0 else 1 },
                                                { -PeriodicTableData.symbols.indexOf(it.split("—").getOrNull(0) ?: "?") },
                                                { -PeriodicTableData.symbols.indexOf(it.split("—").getOrNull(1) ?: "?") },
                                            )
                                        )
                                    }
                                    groupedRules.forEach { (pairLabel, groupRules) ->
                                        val expanded = collapsedGroups["B:$pairLabel"] != true
                                        val allGroupVisible = groupRules.all { it.key !in tab.visibility.hiddenBondPairs }
                                        // Per v0.6.5: directional extend checkboxes on the group header.
                                        val groupElements = pairLabel.split("—")
                                        val gElem1 = groupElements.getOrNull(0) ?: "?"
                                        val gElem2 = groupElements.getOrNull(1) ?: "?"
                                        val groupSameElement = gElem1 == gElem2
                                        // elem1→elem2 flag for each rule (elem1 is the metal or larger-atomic-number element).
                                        fun ruleExtend1(r: com.krystals.crystal.analysis.bonding.BondRule): Boolean {
                                            val aElem = elementOf[r.siteA] ?: "?"
                                            return if (aElem == gElem1) r.extendAtoB else r.extendBtoA
                                        }
                                        fun ruleExtend2(r: com.krystals.crystal.analysis.bonding.BondRule): Boolean {
                                            val aElem = elementOf[r.siteA] ?: "?"
                                            return if (aElem == gElem1) r.extendBtoA else r.extendAtoB
                                        }
                                        val allGroupExtend1 = groupRules.all { ruleExtend1(it) }
                                        val allGroupExtend2 = if (groupSameElement) allGroupExtend1 else groupRules.all { ruleExtend2(it) }
                                        CollapsibleGroupHeader(
                                            title = "$pairLabel (${groupRules.size})",
                                            expanded = expanded,
                                            onToggle = { collapsedGroups["B:$pairLabel"] = expanded },
                                            checked = allGroupVisible,
                                            onCheckChange = { checked ->
                                                tab.recordHistory()
                                                tab.visibility = tab.visibility.copy(
                                                    showBonds = true,
                                                    hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - groupRules.map { it.key }.toSet()
                                                    else tab.visibility.hiddenBondPairs + groupRules.map { it.key }.toSet(),
                                                )
                                            },
                                        ) {
                                            if (groupSameElement) {
                                                Checkbox(allGroupExtend1, onCheckedChange = { checked ->
                                                    var working = tab.bondConfiguration
                                                    groupRules.forEach { rule ->
                                                        val updated = rule.copy(extendAtoB = checked, extendBtoA = checked)
                                                        working = CrystalEditor.apply(tab.structure, working, EditCommand.SetBondRule(updated)).bondConfiguration
                                                    }
                                                    viewModel.updateAnalysis(tab, EditResult(tab.structure, working))
                                                })
                                                Text("$gElem1", style = MaterialTheme.typography.bodySmall)
                                            } else {
                                                Checkbox(allGroupExtend1, onCheckedChange = { checked ->
                                                    var working = tab.bondConfiguration
                                                    groupRules.forEach { rule ->
                                                        val aElem = elementOf[rule.siteA] ?: "?"
                                                        val updated = if (aElem == gElem1) rule.copy(extendAtoB = checked) else rule.copy(extendBtoA = checked)
                                                        working = CrystalEditor.apply(tab.structure, working, EditCommand.SetBondRule(updated)).bondConfiguration
                                                    }
                                                    viewModel.updateAnalysis(tab, EditResult(tab.structure, working))
                                                })
                                                Text("$gElem1", style = MaterialTheme.typography.bodySmall)
                                                Spacer(Modifier.width(4.dp))
                                                Checkbox(allGroupExtend2, onCheckedChange = { checked ->
                                                    var working = tab.bondConfiguration
                                                    groupRules.forEach { rule ->
                                                        val aElem = elementOf[rule.siteA] ?: "?"
                                                        val updated = if (aElem == gElem1) rule.copy(extendBtoA = checked) else rule.copy(extendAtoB = checked)
                                                        working = CrystalEditor.apply(tab.structure, working, EditCommand.SetBondRule(updated)).bondConfiguration
                                                    }
                                                    viewModel.updateAnalysis(tab, EditResult(tab.structure, working))
                                                })
                                                Text("$gElem2", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        if (expanded) {
                                            groupRules.forEach { rule ->
                                                val labelA = sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                                                val labelB = sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                                                val label = "$labelA—$labelB"
                                                val elemA = elementOf[rule.siteA] ?: "?"
                                                val elemB = elementOf[rule.siteB] ?: "?"
                                                val sameRuleElement = elemA == elemB
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 28.dp)) {
                                                    val visible = tab.visibility.showBonds && rule.key !in tab.visibility.hiddenBondPairs
                                                    Checkbox(visible, onCheckedChange = { checked ->
                                                        tab.recordHistory()
                                                        tab.visibility = tab.visibility.copy(
                                                            showBonds = true,
                                                            hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - rule.key else tab.visibility.hiddenBondPairs + rule.key,
                                                        )
                                                    })
                                                    Text(label, modifier = Modifier.weight(1f))
                                                    if (sameRuleElement) {
                                                        Checkbox(rule.extendAtoB || rule.extendBtoA, onCheckedChange = { checked ->
                                                            val updated = rule.copy(extendAtoB = checked, extendBtoA = checked)
                                                            viewModel.updateAnalysis(
                                                                tab,
                                                                CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(updated)),
                                                            )
                                                        })
                                                        Text("$elemA", style = MaterialTheme.typography.bodySmall)
                                                    } else {
                                                        Checkbox(rule.extendAtoB, onCheckedChange = { checked ->
                                                            val updated = rule.copy(extendAtoB = checked)
                                                            viewModel.updateAnalysis(
                                                                tab,
                                                                CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(updated)),
                                                            )
                                                        })
                                                        Text("$labelA", style = MaterialTheme.typography.bodySmall)
                                                        Spacer(Modifier.width(4.dp))
                                                        Checkbox(rule.extendBtoA, onCheckedChange = { checked ->
                                                            val updated = rule.copy(extendBtoA = checked)
                                                            viewModel.updateAnalysis(
                                                                tab,
                                                                CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.SetBondRule(updated)),
                                                            )
                                                        })
                                                        Text("$labelB", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            DisplayTab.HBONDS -> {
                                // Per v0.8.1: simplified bond-rule list for hbonds (no extend-outside-cell controls).
                                val hbondWithMatch = hbondRules.filter { rule ->
                                    BondRuleMatching.hasMatchingBond(
                                        rule,
                                        tab.structure,
                                        tab.bondConfiguration,
                                        bondGrid.second,
                                        bondGrid.first,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allHbondsVisible, onCheckedChange = { checked ->
                                        tab.recordHistory()
                                        tab.visibility = tab.visibility.copy(
                                            showBonds = checked,
                                            hiddenBondPairs = if (checked) emptySet() else hbondWithMatch.map { it.key }.toSet(),
                                        )
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(onClick = {
                                        val allKeys = hbondWithMatch.map { it.key }.toSet()
                                        tab.recordHistory()
                                        tab.visibility = tab.visibility.copy(showBonds = true, hiddenBondPairs = allKeys - tab.visibility.hiddenBondPairs)
                                    }) { Text(localized("反选", "Invert")) }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                if (hbondWithMatch.isEmpty()) {
                                    Text(localized("无氢键", "No H-bonds"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                } else {
                                    val elementOf = remember(sites) { sites.associate { it.id to it.species.symbol } }
                                    val groupedRules = remember(hbondWithMatch, elementOf) {
                                        hbondWithMatch.groupBy { rule ->
                                            val ea = elementOf[rule.siteA] ?: "?"
                                            val eb = elementOf[rule.siteB] ?: "?"
                                            val (first, second) = if (ea <= eb) ea to eb else eb to ea
                                            "$first—$second"
                                        }.toSortedMap(compareBy { it })
                                    }
                                    groupedRules.forEach { (pairLabel, groupRules) ->
                                        val expanded = collapsedGroups["H:$pairLabel"] != true
                                        val allGroupVisible = groupRules.all { it.key !in tab.visibility.hiddenBondPairs }
                                        CollapsibleGroupHeader(
                                            title = "$pairLabel (${groupRules.size})",
                                            expanded = expanded,
                                            onToggle = { collapsedGroups["H:$pairLabel"] = expanded },
                                            checked = allGroupVisible,
                                            onCheckChange = { checked ->
                                                tab.recordHistory()
                                                tab.visibility = tab.visibility.copy(
                                                    showBonds = true,
                                                    hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - groupRules.map { it.key }.toSet()
                                                    else tab.visibility.hiddenBondPairs + groupRules.map { it.key }.toSet(),
                                                )
                                            },
                                        ) { /* no extend controls for hbonds */ }
                                        if (expanded) {
                                            groupRules.forEach { rule ->
                                                val labelA = sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                                                val labelB = sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                                                val label = "$labelA—$labelB"
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 28.dp)) {
                                                    val visible = tab.visibility.showBonds && rule.key !in tab.visibility.hiddenBondPairs
                                                    Checkbox(visible, onCheckedChange = { checked ->
                                                        tab.recordHistory()
                                                        tab.visibility = tab.visibility.copy(
                                                            showBonds = true,
                                                            hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - rule.key else tab.visibility.hiddenBondPairs + rule.key,
                                                        )
                                                    })
                                                    Text(label, modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            DisplayTab.POLYHEDRA -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allPolyhedraEnabled, onCheckedChange = { checked ->
                                        tab.recordHistory()
                                        tab.visibility = tab.visibility.copy(polyhedronSites = if (checked) siteIds else emptySet())
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { tab.recordHistory(); tab.visibility = tab.visibility.copy(polyhedronSites = siteIds - tab.visibility.polyhedronSites) }) { Text(localized("反选", "Invert")) }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                // Per v0.3.44: polyhedra sites grouped by element, same collapse/group-toggle
                                // pattern as ATOMS but without a color swatch.
                                val groupedPoly = remember(sites) { sites.groupBy { it.species.symbol }.toSortedMap() }
                                groupedPoly.forEach { (element, groupSites) ->
                                    val expanded = collapsedGroups["P:$element"] != true
                                    val allGroupEnabled = groupSites.all { it.id in tab.visibility.polyhedronSites }
                                    CollapsibleGroupHeader(
                                        title = "$element (${groupSites.size})",
                                        expanded = expanded,
                                        onToggle = { collapsedGroups["P:$element"] = expanded },
                                        checked = allGroupEnabled,
                                        onCheckChange = { checked ->
                                            tab.recordHistory()
                                            tab.visibility = tab.visibility.copy(
                                                polyhedronSites = if (checked) tab.visibility.polyhedronSites + groupSites.map { it.id }.toSet()
                                                else tab.visibility.polyhedronSites - groupSites.map { it.id }.toSet(),
                                            )
                                        },
                                    ) { Spacer(Modifier.width(22.dp)) }
                                    if (expanded) {
                                        groupSites.forEach { site ->
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp)) {
                                                val enabled = site.id in tab.visibility.polyhedronSites
                                                Checkbox(enabled, onCheckedChange = { checked -> tab.recordHistory(); tab.visibility = tab.visibility.copy(polyhedronSites = if (checked) tab.visibility.polyhedronSites + site.id else tab.visibility.polyhedronSites - site.id) })
                                                Text(site.label)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
    }
    // Per v0.2.3: per-site color picker, driven from the ATOMS sub-menu. Per v0.3.44: also driven
    // from an element-group swatch (groupColorPickerElement) — the chosen color is applied to every
    // site of that element.
    if (colorPickerOpen) {
        val target = colorPickerTarget
        val element = groupColorPickerElement
        val site = sites.firstOrNull { it.id == target }
        val initial = when {
            site != null -> RenderPalette.resolveSiteArgb(site.id, site.species.symbol, tab.renderConfiguration)
            element != null -> {
                val firstOfElement = sites.firstOrNull { it.species.symbol == element }
                if (firstOfElement != null) {
                    RenderPalette.resolveSiteArgb(firstOfElement.id, element, tab.renderConfiguration)
                } else 0xFFCCCCCC
            }
            else -> 0xFFCCCCCC
        }
        ColorPickerDialog(
            initialArgb = initial,
            onDismiss = { colorPickerOpen = false; colorPickerTarget = null; groupColorPickerElement = null },
            onColorSelected = { color ->
                tab.recordHistory()
                when {
                    target != null -> tab.renderConfiguration = tab.renderConfiguration.copy(
                        siteArgbOverrides = tab.renderConfiguration.siteArgbOverrides + (target to color),
                    )
                    element != null -> {
                        val additions = sites.filter { it.species.symbol == element }.associate { it.id to color }
                        tab.renderConfiguration = tab.renderConfiguration.copy(
                            siteArgbOverrides = tab.renderConfiguration.siteArgbOverrides + additions,
                        )
                    }
                }
                colorPickerOpen = false
                colorPickerTarget = null
                groupColorPickerElement = null
            },
        )
    }
}


private enum class DisplayTab { ATOMS, BONDS, POLYHEDRA, HBONDS }

/**
 * Per v0.3.44: a collapsible group header used by the ATOMS/POLYHEDRA/BONDS sub-menus. A row with an
 * expand/collapse arrow, a group checkbox (select/deselect all items in the group), the title, and
 * an optional trailing slot (e.g. the ATOMS element color swatch).
 */

@Composable
private fun CollapsibleGroupHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    checked: Boolean,
    onCheckChange: (Boolean) -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(2.dp))
        Checkbox(checked, onCheckedChange = onCheckChange)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        trailing()
    }
}


@Composable
internal fun InfoDialog(tab: DocumentTab, onDismiss: () -> Unit) {
    val info = remember(tab.structure) { StructureAnalyzer.info(tab.structure) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("晶体信息", "Crystal information")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                InfoSection(title = localized("化学组成", "Composition"), value = info.composition, valueStyle = MaterialTheme.typography.headlineSmall)
                Text("≈ ${info.reducedFormula}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                InfoSection(title = localized("空间群", "Space group"), value = info.spaceGroup, valueStyle = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text(localized("晶胞参数", "Cell parameters"), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    InfoCell(label = "a", value = "%.5f Å".format(info.lattice.a), Modifier.weight(1f))
                    InfoCell(label = "b", value = "%.5f Å".format(info.lattice.b), Modifier.weight(1f))
                    InfoCell(label = "c", value = "%.5f Å".format(info.lattice.c), Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    InfoCell(label = "α", value = "%.4f°".format(info.lattice.alpha), Modifier.weight(1f))
                    InfoCell(label = "β", value = "%.4f°".format(info.lattice.beta), Modifier.weight(1f))
                    InfoCell(label = "γ", value = "%.4f°".format(info.lattice.gamma), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                InfoRow(label = localized("原子数", "Atoms"), value = info.atomCount.toString())
                InfoRow(label = localized("晶胞质量", "Cell mass"), value = "%.2f g/mol".format(info.cellMass))
                InfoRow(label = localized("晶胞体积", "Volume"), value = "%.5f Å³".format(info.volume))
                InfoRow(label = localized("理论密度", "Density"), value = info.density?.let { "%.5f g/cm³".format(it) } ?: "N/A")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}


@Composable
private fun InfoSection(title: String, value: String, valueStyle: androidx.compose.ui.text.TextStyle) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = valueStyle, fontWeight = FontWeight.SemiBold)
    }
}


@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}


@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

