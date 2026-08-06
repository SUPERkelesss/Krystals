@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import androidx.core.content.edit
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.crystal.io.ParsedStructure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PresetLibraryScreen(
    context: Context,
    viewModel: KrystalsViewModel,
    preferences: android.content.SharedPreferences,
    autoConvertCell: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenParsed: (ParsedStructure, String) -> Unit,
) {
    // Per v0.8.34: COD-search-like library — first-level groups (folders) expand to files, a
    // 5-filter bar (formula/element count/crystal system/point group/space group), per-file
    // checkboxes with a batch toolbar (open / move to / delete), and user groups
    // ("我的预设" plus groups created via 新建组) that can be renamed.
    // Per v0.8.39: groups load off the UI thread (listGroups is a suspend disk scan).
    var groups by remember { mutableStateOf(emptyList<PresetGroup>()) }
    LaunchedEffect(Unit) { groups = PresetRepository.listGroups(context) }
    var metas by remember { mutableStateOf<Map<PresetEntry, PresetMeta>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    var selected by remember { mutableStateOf<Set<PresetEntry>>(emptySet()) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PresetGroup?>(null) }
    var renameFileTarget by remember { mutableStateOf<PresetEntry?>(null) }
    var moveOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    // Per v0.8.36: delete a whole user group (not the protected default group).
    var deleteGroupTarget by remember { mutableStateOf<PresetGroup?>(null) }
    // Per v0.8.36: the default group's display name follows the UI language.
    @Composable fun groupLabel(name: String): String =
        if (name == PresetRepository.MY_PRESETS_GROUP) localized("我的预设", "My Presets") else name
    val scope = rememberCoroutineScope()
    val EXPANDED_KEY = PreferencesStore.KEY_PRESET_EXPANDED_CATEGORIES
    var expanded by remember {
        mutableStateOf(
            preferences.getString(EXPANDED_KEY, PresetRepository.MY_PRESETS_GROUP)!!.split(",").filter { it.isNotBlank() }.toSet()
        )
    }
    fun toggle(cat: String) {
        expanded = if (cat in expanded) expanded - cat else expanded + cat
        preferences.edit { putString(EXPANDED_KEY, expanded.joinToString(",")) }
    }
    fun refresh() { scope.launch { groups = PresetRepository.listGroups(context) } }

    // Per v0.8.34: parse filter metadata for every preset off the UI thread.
    // Per v0.8.35: cache-first — only files whose last-modified stamp is missing/stale get
    // re-parsed, and fresh results are persisted so the library opens instantly next time.
    LaunchedEffect(groups) {
        val cache = PresetRepository.loadMetaCache(context)
        val result = mutableMapOf<PresetEntry, PresetMeta>()
        var dirty = false
        withContext(Dispatchers.Default) {
            groups.flatMap { it.entries }.forEach { entry ->
                val cached = PresetRepository.cachedMeta(entry, cache)
                if (cached != null) {
                    result[entry] = cached
                } else {
                    val text = try {
                        when (entry.source) {
                            PresetSource.BUNDLED -> context.assets.open(entry.assetPath!!).bufferedReader().use { it.readText() }
                            PresetSource.USER -> entry.file!!.readText()
                        }
                    } catch (_: Exception) { return@forEach }
                    PresetRepository.parseMeta(text)?.let { meta ->
                        result[entry] = meta
                        cache[PresetRepository.metaKey(entry)] = PresetRepository.cacheEntry(entry, meta)
                        dirty = true
                    }
                }
            }
        }
        if (dirty) PresetRepository.saveMetaCache(context, cache)
        metas = result
    }

    val filterOptions = buildFilterOptions(
        metas.values.map { SearchResultMeta(elementCount = it.elementCount, crystalSystem = it.crystalSystem, pointGroup = it.pointGroup, spaceGroup = it.spaceGroup, formula = it.formula) }
    )
    val elementCountFilter = filterState.elementCount
    val crystalSystemFilter = filterState.crystalSystem
    val pointGroupFilter = filterState.pointGroup
    val spaceGroupFilter = filterState.spaceGroup
    val filteredGroups = groups.map { group ->
        group.copy(entries = group.entries.filter { entry ->
            val meta = metas[entry]
            (searchQuery.isBlank() || entry.name.contains(searchQuery, ignoreCase = true)) &&
                (elementCountFilter == null || meta?.elementCount == elementCountFilter) &&
                (crystalSystemFilter == null || meta?.crystalSystem == crystalSystemFilter) &&
                (pointGroupFilter == null || meta?.pointGroup == pointGroupFilter) &&
                (spaceGroupFilter == null || meta?.spaceGroup == spaceGroupFilter)
        })
    }
    val totalFiltered = filteredGroups.sumOf { it.entries.size }

    fun openSelected() {
        val ordered = groups.flatMap { it.entries }.filter { it in selected }
        scope.launch {
            ordered.forEach { entry ->
                runCatching { withContext(Dispatchers.IO) { PresetRepository.openPreset(context, entry, autoConvertConventional = autoConvertCell) } }
                    .onSuccess { parsed -> onOpenParsed(parsed, entry.name) }
                    .onFailure { if (it !is CancellationException) onMessage(it.message ?: "Unable to open preset") }
            }
            onDismiss()
        }
    }
    // Per v0.8.35: a single click on a file row opens it (selection is checkbox-only now).
    fun openSingle(entry: PresetEntry) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { PresetRepository.openPreset(context, entry, autoConvertConventional = autoConvertCell) } }
                .onSuccess { parsed -> onOpenParsed(parsed, entry.name) }
                .onFailure { if (it !is CancellationException) onMessage(it.message ?: "Unable to open preset") }
            onDismiss()
        }
    }
    fun deleteSelected() {
        scope.launch {
            selected.filter { it.source == PresetSource.USER }.forEach { PresetRepository.deletePreset(context, it) }
            selected = emptySet()
            refresh()
        }
    }
    fun moveSelected(targetGroup: String) {
        scope.launch {
            selected.filter { it.source == PresetSource.USER }.forEach { PresetRepository.movePreset(context, it, targetGroup) }
            selected = emptySet()
            refresh()
        }
    }

    // Per v0.8.34: full-screen page (like the COD search screen). Per v0.8.35: rendered as a
    // plain page composable, NOT inside a Dialog — Compose DropdownMenus (the filter chips)
    // crash during Popup measurement when hosted inside a dialog window on some devices.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground) }
                    Text(stringResource(R.string.preset_library), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    // Per v0.8.36: new-group is an icon button (CreateNewFolder).
                    IconButton(onClick = { newGroupOpen = true }) { Icon(Icons.Default.CreateNewFolder, localized("新建组", "New group")) }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text(localized("搜索文件名...", "Search by name...")) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )
                SearchFilterBar(
                    options = filterOptions,
                    filterState = filterState,
                    onFilterChange = { filterState = it },
                    availableFilters = DEFAULT_VISIBLE_FILTERS,
                    visibleFilters = DEFAULT_VISIBLE_FILTERS,
                    onVisibleFiltersChange = {},
                )
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (totalFiltered == 0) {
                        item { Text(localized("无匹配结果", "No matching results"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                    }
                    filteredGroups.forEach { group ->
                        item(key = "h_" + group.name) {
                            // Per v0.8.36: folder icon before the group name; the protected
                            // default group cannot be renamed or deleted.
                            Row(Modifier.fillMaxWidth().clickable { toggle(group.name) }.padding(vertical = 4.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (group.name in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp))
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Text(groupLabel(group.name), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp).weight(1f))
                                if (group.isUserGroup && group.name != PresetRepository.MY_PRESETS_GROUP) {
                                    IconButton(onClick = { renameTarget = group }) { Icon(Icons.Default.Edit, localized("重命名组", "Rename group"), modifier = Modifier.size(18.dp)) }
                                    IconButton(onClick = { deleteGroupTarget = group }) { Icon(Icons.Default.Delete, localized("删除组", "Delete group"), modifier = Modifier.size(18.dp)) }
                                }
                            }
                        }
                        if (group.name in expanded) items(group.entries, key = { "e_" + group.name + "_" + it.name }) { entry ->
                            PresetRow(
                                entry = entry,
                                meta = metas[entry],
                                checked = entry in selected,
                                onToggle = { selected = if (entry in selected) selected - entry else selected + entry },
                                onOpen = { openSingle(entry) },
                                onRename = { renameFileTarget = entry },
                                // Per v0.8.36: the row delete button marks the file selected and
                                // opens the batch-delete confirmation.
                                onDelete = {
                                    selected = selected + entry
                                    deleteConfirmOpen = true
                                },
                            )
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    HorizontalDivider()
                    // Per v0.8.35: order 删除/移动到/打开, with Open as a highlighted button.
                    // Per v0.8.36: when the selection includes non-editable (bundled) files,
                    // Delete and Move-to are hidden entirely.
                    val hasLocked = selected.any { it.source != PresetSource.USER }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localized("已选 ${selected.size} 项", "Selected ${selected.size}"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (!hasLocked) {
                            TextButton(onClick = { deleteConfirmOpen = true }) { Text(localized("删除", "Delete")) }
                            TextButton(onClick = { moveOpen = true }) { Text(localized("移动到", "Move to")) }
                        }
                        Button(onClick = { openSelected() }) { Text(localized("打开", "Open")) }
                    }
                }
            }
        }

    // Per v0.8.34: 新建组 — create a named first-level user group.
    if (newGroupOpen) {
        var name by remember { mutableStateOf("") }
        val createError = localized("组已存在或创建失败", "Group exists or failed to create")
        AlertDialog(
            onDismissRequest = { newGroupOpen = false },
            title = { Text(localized("新建组", "New group")) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text(localized("组名", "Group name")) })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (name.isNotBlank()) {
                            if (PresetRepository.createGroup(context, name) != null) refresh()
                            else onMessage(createError)
                        }
                        newGroupOpen = false
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { newGroupOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Per v0.8.34: rename a first-level user group.
    renameTarget?.let { group ->
        var name by remember(group) { mutableStateOf(group.name) }
        val renameError = localized("重命名失败", "Rename failed")
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(localized("重命名组", "Rename group")) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (!PresetRepository.renameGroup(context, group.name, name)) onMessage(renameError)
                        renameTarget = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Per v0.8.35: rename a single user preset file (per-row 重命名 button).
    renameFileTarget?.let { entry ->
        var name by remember(entry) { mutableStateOf(entry.name.removeSuffix(".cif")) }
        val renameError = localized("重命名失败", "Rename failed")
        AlertDialog(
            onDismissRequest = { renameFileTarget = null },
            title = { Text(localized("重命名文件", "Rename file")) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (!PresetRepository.renamePreset(context, entry, name)) onMessage(renameError)
                        renameFileTarget = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { renameFileTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Per v0.8.34: 移动到 — pick a target user group.
    if (moveOpen) {
        val userGroups = groups.filter { it.isUserGroup }.map { it.name }
        AlertDialog(
            onDismissRequest = { moveOpen = false },
            title = { Text(localized("移动到...", "Move to...")) },
            text = {
                Column {
                    userGroups.forEach { target ->
                        Row(Modifier.fillMaxWidth().clickable { moveSelected(target); moveOpen = false }.padding(vertical = 8.dp)) {
                            Text(target)
                        }
                    }
                    if (userGroups.isEmpty()) Text(localized("暂无文件夹", "No folders yet"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Per v0.8.34: batch delete confirmation.
    if (deleteConfirmOpen) {
        val deletable = selected.count { it.source == PresetSource.USER }
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.delete_preset_title)) },
            text = { Text(localized("确定删除选中的 $deletable 个文件吗？", "Delete the $deletable selected file(s)?")) },
            confirmButton = { TextButton(onClick = { deleteConfirmOpen = false; deleteSelected() }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { deleteConfirmOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Per v0.8.36: delete a whole user group (default group protected).
    deleteGroupTarget?.let { group ->
        val groupDeleteTitle = localized("删除组", "Delete group")
        val groupDeleteMessage = localized("确定删除组“${groupLabel(group.name)}”及其全部文件吗？", "Delete group \"${groupLabel(group.name)}\" and all its files?")
        val groupDeleteError = localized("删除组失败", "Failed to delete group")
        AlertDialog(
            onDismissRequest = { deleteGroupTarget = null },
            title = { Text(groupDeleteTitle) },
            text = { Text(groupDeleteMessage) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (!PresetRepository.deleteGroup(context, group.name)) onMessage(groupDeleteError)
                        deleteGroupTarget = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { deleteGroupTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Per v0.8.34: one preset file row with a checkbox; tapping the row opens the file (v0.8.35),
 *  the checkbox toggles selection, and user files get per-row rename + delete buttons (v0.8.36). */

@Composable
private fun PresetRow(
    entry: PresetEntry,
    meta: PresetMeta?,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (checked) Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) else Modifier)
            .clickable { onOpen() }
            .padding(vertical = 4.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            meta?.let {
                Text("${it.formula} · ${it.spaceGroup}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entry.source == PresetSource.USER) {
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, localized("重命名", "Rename"), modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, localized("删除", "Delete"), modifier = Modifier.size(18.dp)) }
        } else {
            Text(stringResource(R.string.bundled), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    HorizontalDivider()
}

/** Per v0.6.5: apply filters to MP search results. */
