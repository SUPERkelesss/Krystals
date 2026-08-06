@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog

internal enum class SearchFilterType {
    ELEMENT_COUNT, CRYSTAL_SYSTEM, POINT_GROUP, SPACE_GROUP,
    TITLE, AUTHOR, JOURNAL, YEAR, ELEMENT_COMPOSITION, STABLE,
}

// Per v0.8.36: the formula filter is removed everywhere — its option list (hundreds of
// formulas) crashes the DropdownMenu-based chip on some devices, so the filter bar keeps the
// four low-cardinality filters only.
/** Per v0.8.27: default visible filters. Per v0.8.36: formula filter removed. */

internal val DEFAULT_VISIBLE_FILTERS = setOf(
    SearchFilterType.ELEMENT_COUNT, SearchFilterType.CRYSTAL_SYSTEM,
    SearchFilterType.POINT_GROUP, SearchFilterType.SPACE_GROUP,
)

/** Per v0.8.27: the full filter set offered by each search page. Per v0.8.29: COD has no
 * composition/stability filters; MP has no bibliographic filters. Per v0.8.36: formula removed. */

internal val COD_FILTER_TYPES = setOf(
    SearchFilterType.ELEMENT_COUNT, SearchFilterType.CRYSTAL_SYSTEM,
    SearchFilterType.POINT_GROUP, SearchFilterType.SPACE_GROUP,
    SearchFilterType.TITLE, SearchFilterType.AUTHOR, SearchFilterType.JOURNAL, SearchFilterType.YEAR,
)

internal val MP_FILTER_TYPES = setOf(
    SearchFilterType.ELEMENT_COUNT, SearchFilterType.CRYSTAL_SYSTEM,
    SearchFilterType.POINT_GROUP, SearchFilterType.SPACE_GROUP,
    SearchFilterType.ELEMENT_COMPOSITION, SearchFilterType.STABLE,
)

/** Per v0.6.5: filter state for search result filtering. */

internal data class SearchFilterState(
    val elementCount: Int? = null,
    val crystalSystem: String? = null,
    val pointGroup: String? = null,
    val spaceGroup: String? = null,
    // Per v0.8.27: extended filters.
    val title: String? = null,
    val author: String? = null,
    val journal: String? = null,
    val year: String? = null,
    val elementComposition: String? = null,
    val stable: Boolean? = null,
) {
    val isActive: Boolean get() = elementCount != null || crystalSystem != null || pointGroup != null ||
        spaceGroup != null || title != null || author != null || journal != null ||
        year != null || elementComposition != null || stable != null
}

/** Per v0.6.5: metadata extracted from a search result for filtering. */

internal data class SearchResultMeta(
    val elementCount: Int,
    val crystalSystem: String?,
    val pointGroup: String?,
    val spaceGroup: String?,
    // Per v0.8.27: bibliographic fields for the extended filters (COD), empty for MP.
    val formula: String = "",
    val title: String = "",
    val author: String = "",
    val journal: String = "",
    val year: String = "",
)

/** Per v0.8.27: distinct element symbols in a formula like "Fe2O3" → {Fe, O}. Single shared
 *  implementation for element parsing (count + composition filter) across the app. */

internal fun elementsInFormula(formula: String): Set<String> =
    Regex("[A-Z][a-z]?").findAll(formula).map { it.value }.toSet()

/** Per v0.6.5: resolve crystal system and point group from a space group symbol or number. */

private fun resolveSpaceGroupMeta(sgSymbol: String, sgNumber: String? = null): Pair<String?, String?> {
    // Prefer the space-group number when it resolves; fall back to symbol lookup otherwise.
    val catalog = if (!sgNumber.isNullOrBlank()) {
        sgNumber.toIntOrNull()?.let { SpaceGroupCatalog.all.getOrNull(it - 1) }
            ?: SpaceGroupCatalog.find(sgSymbol)
    } else {
        SpaceGroupCatalog.find(sgSymbol)
    }
    return catalog?.crystalSystem to catalog?.pointGroup
}

/** Per v0.6.5: extract metadata from MP search results. */

internal fun MpSearchResult.meta(): SearchResultMeta {
    val (cs, pg) = resolveSpaceGroupMeta(spaceGroup)
    return SearchResultMeta(
        elementCount = elementsInFormula(formula).size,
        crystalSystem = cs ?: crystalSystem.takeIf { it != "N/A" },
        pointGroup = pg,
        spaceGroup = spaceGroup.takeIf { it != "N/A" },
        formula = formula,
    )
}

/** Per v0.6.5: extract metadata from COD search results. */

internal fun CodSearchResult.meta(): SearchResultMeta {
    val (cs, pg) = resolveSpaceGroupMeta(spaceGroup, sgNumber)
    return SearchResultMeta(
        elementCount = nel,
        crystalSystem = cs,
        pointGroup = pg,
        spaceGroup = spaceGroup.takeIf { it.isNotBlank() },
        formula = formula,
        title = title,
        author = author,
        journal = journal,
        year = year,
    )
}

/** Per v0.6.5: build the available options from a list of metadata. */

internal fun buildFilterOptions(metas: List<SearchResultMeta>): SearchFilterOptions {
    val elementCounts = metas.map { it.elementCount }.distinct().sorted()
    val crystalSystems = metas.mapNotNull { it.crystalSystem }.distinct().sorted()
    val pointGroups = metas.mapNotNull { it.pointGroup }.distinct().sorted()
    val spaceGroups = metas.mapNotNull { it.spaceGroup }.distinct().sorted()
    // Per v0.8.27: extended filter options (empty when the source provides no values).
    val titles = metas.map { it.title }.filter { it.isNotBlank() }.distinct().sorted()
    val authors = metas.map { it.author }.filter { it.isNotBlank() }.distinct().sorted()
    val journals = metas.map { it.journal }.filter { it.isNotBlank() }.distinct().sorted()
    val years = metas.map { it.year }.filter { it.isNotBlank() }.distinct().sorted()
    // Per v0.8.29: element-composition options = distinct sorted element sets (e.g. "Fe O").
    val elementCompositions = metas.map { elementsInFormula(it.formula).sorted().joinToString(" ") }
        .filter { it.isNotBlank() }.distinct().sorted()
    return SearchFilterOptions(elementCounts, crystalSystems, pointGroups, spaceGroups, titles, authors, journals, years, elementCompositions)
}


internal data class SearchFilterOptions(
    val elementCounts: List<Int>,
    val crystalSystems: List<String>,
    val pointGroups: List<String>,
    val spaceGroups: List<String>,
    // Per v0.8.27: extended filter options.
    val titles: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val journals: List<String> = emptyList(),
    val years: List<String> = emptyList(),
    // Per v0.8.29: element-composition options (distinct sorted element sets).
    val elementCompositions: List<String> = emptyList(),
)


private fun normalizeSgSymbol(s: String) = s.replace(" ", "").replace("_", "").lowercase()

/** Per v0.6.5: cascade-link crystal system → point group → space group using SpaceGroupCatalog. */

private fun pointGroupsForCrystalSystem(cs: String?): List<String> {
    if (cs == null) return SpaceGroupCatalog.all.mapNotNull { it.pointGroup }.distinct().sorted()
    return SpaceGroupCatalog.all.filter { it.crystalSystem == cs }.mapNotNull { it.pointGroup }.distinct().sorted()
}


private fun spaceGroupsForPointGroup(pg: String?): List<String> {
    if (pg == null) return SpaceGroupCatalog.all.map { it.symbol }.distinct().sorted()
    return SpaceGroupCatalog.all.filter { it.pointGroup == pg }.map { it.symbol }.distinct().sorted()
}

/** Per v0.5.3b: holds an open request whose expanded atom count exceeds the warn threshold until
 *  the user confirms or cancels. */

internal fun filterMpResults(results: List<MpSearchResult>, filter: SearchFilterState): List<MpSearchResult> {
    if (!filter.isActive) return results
    return results.filter { item ->
        val meta = item.meta()
        (filter.elementCount == null || meta.elementCount == filter.elementCount) &&
        (filter.crystalSystem == null || meta.crystalSystem == filter.crystalSystem) &&
        (filter.pointGroup == null || meta.pointGroup == filter.pointGroup) &&
        (filter.spaceGroup == null || meta.spaceGroup == filter.spaceGroup) &&
        // Per v0.8.27: extended filters.
        (filter.elementComposition == null || elementsInFormula(item.formula).containsAll(elementsInFormula(filter.elementComposition))) &&
        (filter.stable == null || stableOf(item) == filter.stable)
    }
}

/** Per v0.6.5: apply filters to COD search results. */

internal fun filterCodResults(results: List<CodSearchResult>, filter: SearchFilterState): List<CodSearchResult> {
    if (!filter.isActive) return results
    return results.filter { item ->
        val meta = item.meta()
        (filter.elementCount == null || meta.elementCount == filter.elementCount) &&
        (filter.crystalSystem == null || meta.crystalSystem == filter.crystalSystem) &&
        (filter.pointGroup == null || meta.pointGroup == filter.pointGroup) &&
        (filter.spaceGroup == null || meta.spaceGroup == filter.spaceGroup) &&
        // Per v0.8.27: extended filters.
        (filter.title == null || item.title.contains(filter.title, ignoreCase = true)) &&
        (filter.author == null || item.author.contains(filter.author, ignoreCase = true)) &&
        (filter.journal == null || item.journal.contains(filter.journal, ignoreCase = true)) &&
        (filter.year == null || item.year == filter.year)
    }
}

/** Per v0.8.27: true when the material is on the hull (stable). Null when no hull data. */

private fun stableOf(item: MpSearchResult): Boolean? = item.energyAboveHull?.let { it <= 0.0 }

/** Per v0.6.5: a single dropdown-chip for filtering. */

@Composable
private fun FilterDropdownChip(
    label: String,
    selectedValue: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = selectedValue ?: label
    Box {
        FilterChip(
            selected = selectedValue != null,
            onClick = { expanded = true },
            label = { Text(displayValue, maxLines = 1) },
            modifier = Modifier.padding(horizontal = 2.dp),
            trailingIcon = if (selectedValue != null) {
                {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onSelect(null) },
                    )
                }
            } else null,
        )
        // Per v0.8.36: DropdownMenu scrolls its content internally. A plain Column keeps that
        // scrolling working — the earlier heightIn(max 360dp) capped the list and clipped long
        // option sets, and the earlier inner verticalScroll nested a second scroll container
        // which crashed with "infinity maximum height constraints".
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

/** Per v0.6.5: a dropdown-chip for integer-based filtering (element count). */

@Composable
private fun IntFilterDropdownChip(
    label: String,
    selectedValue: Int?,
    options: List<Int>,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = selectedValue?.toString() ?: label
    Box {
        FilterChip(
            selected = selectedValue != null,
            onClick = { expanded = true },
            label = { Text(displayValue, maxLines = 1) },
            modifier = Modifier.padding(horizontal = 2.dp),
            trailingIcon = if (selectedValue != null) {
                {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onSelect(null) },
                    )
                }
            } else null,
        )
        // Per v0.8.36: plain Column, DropdownMenu scrolls internally (see FilterDropdownChip).
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.toString()) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

/** Per v0.8.27: localized label for a filter type. */

@Composable
private fun filterTypeLabel(type: SearchFilterType): String = when (type) {
    SearchFilterType.ELEMENT_COUNT -> localized("元素数量", "Elements")
    SearchFilterType.CRYSTAL_SYSTEM -> localized("晶系", "Crystal sys.")
    SearchFilterType.POINT_GROUP -> localized("点群", "Point group")
    SearchFilterType.SPACE_GROUP -> localized("空间群", "Space group")
    SearchFilterType.TITLE -> localized("标题", "Title")
    SearchFilterType.AUTHOR -> localized("作者", "Author")
    SearchFilterType.JOURNAL -> localized("期刊", "Journal")
    SearchFilterType.YEAR -> localized("年份", "Year")
    SearchFilterType.ELEMENT_COMPOSITION -> localized("元素组成", "Composition")
    SearchFilterType.STABLE -> localized("是否稳定", "Stability")
}

/** Per v0.8.27: dialog to pick which filter chips are visible in the filter bar. */

@Composable
private fun FilterPickerDialog(
    availableFilters: Set<SearchFilterType>,
    visibleFilters: Set<SearchFilterType>,
    onVisibleFiltersChange: (Set<SearchFilterType>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("筛选项目", "Filter items")) },
        text = {
            Column {
                availableFilters.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onVisibleFiltersChange(if (type in visibleFilters) visibleFilters - type else visibleFilters + type)
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(type in visibleFilters, onCheckedChange = { checked ->
                            onVisibleFiltersChange(if (checked) visibleFilters + type else visibleFilters - type)
                        })
                        Text(filterTypeLabel(type))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}

/** Per v0.6.5: horizontal filter bar for search results with cascading crystal system → point group → space group. */

@Composable
internal fun SearchFilterBar(
    options: SearchFilterOptions,
    filterState: SearchFilterState,
    onFilterChange: (SearchFilterState) -> Unit,
    availableFilters: Set<SearchFilterType>,
    visibleFilters: Set<SearchFilterType>,
    onVisibleFiltersChange: (Set<SearchFilterType>) -> Unit,
) {
    val elemCountLabel = localized("元素数量", "Elements")
    val crystalSystemLabel = localized("晶系", "Crystal sys.")
    val pointGroupLabel = localized("点群", "Point group")
    val spaceGroupLabel = localized("空间群", "Space group")
    val stableLabel = localized("是否稳定", "Stability")
    val stableOptions = listOf(localized("稳定", "Stable"), localized("不稳定", "Unstable"))

    val availablePointGroups = if (filterState.crystalSystem != null) {
        pointGroupsForCrystalSystem(filterState.crystalSystem).filter { it in options.pointGroups }
    } else {
        options.pointGroups
    }
    val availableSpaceGroups = when {
        filterState.pointGroup != null -> {
            // Per v0.6.5: use normalized comparison to handle format differences
            // (e.g. catalog "P 1" vs search result "P1")
            val catalogSymbols = spaceGroupsForPointGroup(filterState.pointGroup).map(::normalizeSgSymbol).toSet()
            options.spaceGroups.filter { normalizeSgSymbol(it) in catalogSymbols }
        }
        filterState.crystalSystem != null -> {
            // Per v0.7.1: filter space groups by crystal system when point group is not selected.
            val catalogSymbols = SpaceGroupCatalog.all
                .filter { it.crystalSystem == filterState.crystalSystem }
                .map { it.symbol }.map(::normalizeSgSymbol).toSet()
            options.spaceGroups.filter { normalizeSgSymbol(it) in catalogSymbols }
        }
        else -> options.spaceGroups
    }

    var pickerOpen by remember { mutableStateOf(false) }
    Surface(
        // Per v0.8.36: the filter bar blends with the page background (no elevation tint).
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Per v0.8.27: "+" button opens the filter-item picker.
            item {
                IconButton(onClick = { pickerOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, localized("筛选项目", "Filter items"), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (SearchFilterType.ELEMENT_COUNT in visibleFilters) {
                item {
                    IntFilterDropdownChip(
                        label = elemCountLabel,
                        selectedValue = filterState.elementCount,
                        options = options.elementCounts,
                        onSelect = { v -> onFilterChange(filterState.copy(elementCount = v)) },
                    )
                }
            }
            if (SearchFilterType.CRYSTAL_SYSTEM in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = crystalSystemLabel,
                        selectedValue = filterState.crystalSystem,
                        options = options.crystalSystems,
                        onSelect = { v ->
                            // Cascade: reset point group and space group when crystal system changes
                            onFilterChange(filterState.copy(crystalSystem = v, pointGroup = null, spaceGroup = null))
                        },
                    )
                }
            }
            if (SearchFilterType.POINT_GROUP in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = pointGroupLabel,
                        selectedValue = filterState.pointGroup,
                        options = availablePointGroups,
                        onSelect = { v ->
                            // Cascade: reset space group when point group changes
                            onFilterChange(filterState.copy(pointGroup = v, spaceGroup = null))
                        },
                    )
                }
            }
            if (SearchFilterType.SPACE_GROUP in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = spaceGroupLabel,
                        selectedValue = filterState.spaceGroup,
                        options = availableSpaceGroups,
                        onSelect = { v -> onFilterChange(filterState.copy(spaceGroup = v)) },
                    )
                }
            }
            if (SearchFilterType.TITLE in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = filterTypeLabel(SearchFilterType.TITLE),
                        selectedValue = filterState.title,
                        options = options.titles,
                        onSelect = { v -> onFilterChange(filterState.copy(title = v)) },
                    )
                }
            }
            if (SearchFilterType.AUTHOR in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = filterTypeLabel(SearchFilterType.AUTHOR),
                        selectedValue = filterState.author,
                        options = options.authors,
                        onSelect = { v -> onFilterChange(filterState.copy(author = v)) },
                    )
                }
            }
            if (SearchFilterType.JOURNAL in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = filterTypeLabel(SearchFilterType.JOURNAL),
                        selectedValue = filterState.journal,
                        options = options.journals,
                        onSelect = { v -> onFilterChange(filterState.copy(journal = v)) },
                    )
                }
            }
            if (SearchFilterType.YEAR in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = filterTypeLabel(SearchFilterType.YEAR),
                        selectedValue = filterState.year,
                        options = options.years,
                        onSelect = { v -> onFilterChange(filterState.copy(year = v)) },
                    )
                }
            }
            if (SearchFilterType.ELEMENT_COMPOSITION in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = filterTypeLabel(SearchFilterType.ELEMENT_COMPOSITION),
                        selectedValue = filterState.elementComposition,
                        options = options.elementCompositions,
                        onSelect = { v -> onFilterChange(filterState.copy(elementComposition = v)) },
                    )
                }
            }
            if (SearchFilterType.STABLE in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = stableLabel,
                        selectedValue = filterState.stable?.let { if (it) stableOptions[0] else stableOptions[1] },
                        options = stableOptions,
                        onSelect = { v -> onFilterChange(filterState.copy(stable = v == stableOptions[0])) },
                    )
                }
            }
            if (filterState.isActive) {
                item {
                    TextButton(onClick = { onFilterChange(SearchFilterState()) }) {
                        Text(localized("清除", "Clear"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (pickerOpen) {
        FilterPickerDialog(
            availableFilters = availableFilters,
            visibleFilters = visibleFilters,
            onVisibleFiltersChange = onVisibleFiltersChange,
            onDismiss = { pickerOpen = false },
        )
    }
}

