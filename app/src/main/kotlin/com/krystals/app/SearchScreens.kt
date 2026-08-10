@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.app.ui.AppPalette
import com.krystals.crystal.io.ParsedStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun MpApiKeyDialog(
    context: Context,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onConfirmed: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var key by remember { mutableStateOf(MaterialsProject.getKey(context) ?: "") }
    var validating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Materials Project API Key") },
        text = {
            Column {
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onOpenUrl("https://next-gen.materialsproject.org/api") }) { Text(localized("创建 apikey...", "Create an API key...")) }
                if (validating) Text(localized("验证中…", "Validating…"), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && !validating,
                onClick = {
                    validating = true
                    scope.launch {
                        val valid = MaterialsProject.validateKey(key)
                        validating = false
                        if (valid) {
                            MaterialsProject.saveKey(context, key)
                            onConfirmed()
                        } else {
                            onMessage("Invalid API key")
                        }
                    }
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun MpSearchScreen(
    context: Context,
    viewModel: KrystalsViewModel,
    autoConvertCell: Boolean,
    onBack: () -> Unit,
    onChangeKey: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenParsed: (ParsedStructure, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var fuzzySearch by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MpSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Per v0.7.0: cancellable search — the cancel button appears 3s into a search.
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchCancelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) {
            searchCancelVisible = false
            kotlinx.coroutines.delay(3000)
            searchCancelVisible = true
        } else {
            searchCancelVisible = false
        }
    }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var testingConnection by remember { mutableStateOf(true) }
    var connectionError by remember { mutableStateOf(false) }
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    // Per v0.7.0: filter-item visibility (persisted for the session) + help panel state.
    var visibleFilters by remember { mutableStateOf(DEFAULT_VISIBLE_FILTERS) }
    // Per v0.7.0: help hint is a standard Material3 TooltipBox (custom panel removed).
    val helpTooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (MaterialsProject.testConnection()) {
            testingConnection = false
        } else {
            testingConnection = false
            connectionError = true
        }
    }
    BackHandler(testingConnection) { onBack() }
    // Full-screen surface so the screen covers the whole viewport (status bar area
    // included via the Scaffold insets already applied above) instead of a padded column.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (testingConnection) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(localized("测试节点中…", "Testing connection…"))
                }
            }
        } else {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground) }
                Text(localized("MP 搜索", "MP search"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                TextButton(onClick = onChangeKey) { Text(localized("修改 apikey…", "Change API key…")) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text(localized("搜索", "Search")) }, modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (query.isBlank() || searching) return@Button
                    searching = true
                    results = null
                    searchJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val result = MaterialsProject.search(context, query, fuzzySearch)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            searching = false
                            result.onSuccess { results = it }.onFailure { onMessage(it.message ?: "Search failed") }
                        }
                    }
                }, enabled = !searching) { Text(localized("搜索", "Search")) }
            }
            // Per v0.2.4: exact match by default; opt-in fuzzy search with `*` wildcards.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(fuzzySearch, onCheckedChange = { fuzzySearch = it })
                Text(localized("模糊搜索", "Fuzzy search"), style = MaterialTheme.typography.bodyMedium)
                // Per v0.7.0: help hint via Material3 TooltipBox (custom panel removed).
                TooltipBox(
                    // Per v0.7.0: show the hint below the button.
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(positioning = TooltipAnchorPosition.Below),
                    tooltip = {
                        PlainTooltip {
                            Text(localized(
                                "使用 * 进行元素通配搜索（如SiO*）。精确搜索中，* 仅代表一种元素；模糊搜索显示 * 代表多种元素的结果。",
                                "Use * for element wildcard search (e.g. SiO*). In exact search, * represents a single element; fuzzy search shows results where * matches multiple elements.",
                            ))
                        }
                    },
                    state = helpTooltip,
                ) {
                    IconButton(onClick = { scope.launch { if (helpTooltip.isVisible) helpTooltip.dismiss() else helpTooltip.show() } }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Help, localized("帮助", "Help"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Per v0.6.5: snapshot `results` into a local val so the LazyColumn's item lambda never
            // re-reads a null `results` during a Compose snapshot-apply (which threw NPE on the 2nd
            // search when `results = null` invalidated the list mid-recomposition).
            val current = results
            val filtered = current?.let { filterMpResults(it, filterState) }
            val filterOptions = current?.let { buildFilterOptions(it.map { r -> r.meta() }) } ?: SearchFilterOptions(emptyList(), emptyList(), emptyList(), emptyList())
            LaunchedEffect(current) { filterState = SearchFilterState() }
            if (current != null && current.isNotEmpty()) {
                SearchFilterBar(
                    options = filterOptions,
                    filterState = filterState,
                    onFilterChange = { filterState = it },
                    availableFilters = MP_FILTER_TYPES,
                    visibleFilters = visibleFilters,
                    onVisibleFiltersChange = { visibleFilters = it },
                )
                // Per v0.7.0: matching-count caption under the filter bar.
                Text(
                    localized("符合条件的", "Matching") + " ${filtered?.size ?: 0} " + localized("个结果: ", "results: "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                // Per v0.7.0: the search-cancel button appears below 'Searching…' after 3s.
                searching -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(localized("搜索中…", "Searching…"))
                    if (searchCancelVisible) {
                        TextButton(onClick = {
                            searchJob?.cancel()
                            searching = false
                        }) { Text(localized("取消", "Cancel")) }
                    }
                }
                filtered == null -> {}
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    // Per v0.7.0: stable key so filter/search updates keep item identity (scroll + reuse).
                    items(filtered, key = { it.materialId }) { item ->
                        Card(
                            enabled = downloadingId == null,
                            onClick = {
                                if (downloadingId != null) return@Card
                                downloadingId = item.materialId
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val target = File(context.cacheDir, "${item.materialId}.cif")
                                    val result = MaterialsProject.downloadCif(context, item.materialId, target, autoConvertConventional = autoConvertCell)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        downloadingId = null
                                        result.onSuccess { parsed ->
                                            onBack()
                                            onOpenParsed(parsed, "${item.materialId}.cif")
                                        }.onFailure { onMessage(it.message ?: "Download failed") }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.materialId, fontWeight = FontWeight.Bold)
                                Text("${item.formula}  ${item.crystalSystem}  ${item.spaceGroup}  ${item.nsites} sites")
                                item.energyAboveHull?.let { Text(localized("E_hull: %.3f eV/atom (%s)".format(it, if (it <= 0.0) "stable" else "unstable"), "E_hull: %.3f eV/atom (%s)".format(it, if (it <= 0.0) "stable" else "unstable")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (downloadingId == item.materialId) {
                                    Text(localized("下载中…", "Downloading…"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Per v0.7.0: help hint moved to a TooltipBox above; the custom animated panel is gone.
        }
        }
        if (connectionError) {
            AlertDialog(
                onDismissRequest = { connectionError = false; onBack() },
                title = { Text(localized("无法连接", "Connection failed")) },
                text = { Text(localized("当前无法连接到Materials Project数据库，可能是网络不可用或链路异常。", "Unable to connect to the Materials Project database. The network may be unavailable or the link is abnormal.")) },
                confirmButton = {
                    TextButton(onClick = { connectionError = false; onBack() }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
            )
        }
    }
}

@Composable
internal fun CodSearchScreen(
    context: Context,
    viewModel: KrystalsViewModel,
    autoConvertCell: Boolean,
    codMirrorMode: CodMirrorMode,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenParsed: (ParsedStructure, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Per v0.3.1: COD supports three search modes — formula (default), element, text. Formula is
    // auto-converted to COD's space-separated form (SiO2 → "Si O2"); element takes a list (Si O);
    // text matches mineral/chemical names and titles.
    var mode by remember { mutableStateOf(CrystallographyOpenDatabase.SearchMode.FORMULA) }
    // Per v0.3.3: element-search cap on the number of distinct elements in returned structures
    // (COD's nel2 parameter). Range 1..8, default 8 (no effective limit).
    var maxElements by remember { mutableIntStateOf(8) }
    var results by remember { mutableStateOf<List<CodSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Per v0.7.0: cancellable search — the cancel button appears 3s into a search.
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchCancelVisible by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) {
            searchCancelVisible = false
            kotlinx.coroutines.delay(3000)
            searchCancelVisible = true
        } else {
            searchCancelVisible = false
        }
    }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var testingMirrors by remember { mutableStateOf(true) }
    var connectionError by remember { mutableStateOf(false) }
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    // Per v0.7.0: filter-item visibility (persisted for the session) + help panel state.
    var visibleFilters by remember { mutableStateOf(DEFAULT_VISIBLE_FILTERS) }
    // Per v0.7.0: help hint is a standard Material3 TooltipBox (custom panel removed).
    val helpTooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Per v0.7.0: only AUTO mode re-tests mirrors on entry. FIXED/CUSTOM pick their
        // mirror in KrystalsRoot's setMirrorMode; testMirrors() would overwrite that choice.
        if (codMirrorMode == CodMirrorMode.AUTO) {
            val mirror = CrystallographyOpenDatabase.testMirrors()
            if (mirror != null) {
                CrystallographyOpenDatabase.selectMirror(mirror)
                testingMirrors = false
            } else {
                testingMirrors = false
                connectionError = true
            }
        } else {
            testingMirrors = false
        }
    }
    BackHandler(testingMirrors) { onBack() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (testingMirrors) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(localized("测试节点中…", "Testing mirrors…"))
                }
            }
        } else {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground) }
                Text(localized("COD 搜索", "COD search"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text(localized("搜索", "Search")) }, modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (query.isBlank() || searching) return@Button
                    searching = true
                    results = null
                    searchJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val result = CrystallographyOpenDatabase.search(query, mode, if (mode == CrystallographyOpenDatabase.SearchMode.ELEMENT) maxElements else null)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            searching = false
                            result.onSuccess { results = it }.onFailure { onMessage(it.message ?: "Search failed") }
                        }
                    }
                }, enabled = !searching) { Text(localized("搜索", "Search")) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val formulaLabel = localized("化学式", "Formula")
                val elementLabel = localized("元素", "Element")
                val textLabel = localized("文本", "Text")
                listOf(
                    CrystallographyOpenDatabase.SearchMode.FORMULA to formulaLabel,
                    CrystallographyOpenDatabase.SearchMode.ELEMENT to elementLabel,
                    CrystallographyOpenDatabase.SearchMode.TEXT to textLabel,
                ).forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }
                // Per v0.7.0: help hint via Material3 TooltipBox (custom panel removed).
                TooltipBox(
                    // Per v0.7.0: show the hint below the button.
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(positioning = TooltipAnchorPosition.Below),
                    tooltip = {
                        PlainTooltip {
                            Text(localized(
                                "化学式如 SiO2（自动转为 Hill 顺序 O2 Si）。元素以空格分开，如 Si O。文本如 quartz，匹配矿物名/化学名/标题。",
                                "Formula e.g. SiO2 (auto-converted to Hill order: O2 Si). Elements separated by space, e.g. Si O. Text e.g. quartz, matches mineral/chemical names and titles.",
                            ))
                        }
                    },
                    state = helpTooltip,
                ) {
                    IconButton(onClick = { scope.launch { if (helpTooltip.isVisible) helpTooltip.dismiss() else helpTooltip.show() } }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Help, localized("帮助", "Help"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Per v0.6.5: snapshot `results` into a local val so the LazyColumn's item lambda never
            // re-reads a null `results` during a Compose snapshot-apply (which threw NPE on the 2nd
            // search when `results = null` invalidated the list mid-recomposition).
            val current = results
            val filtered = current?.let { filterCodResults(it, filterState) }
            val filterOptions = current?.let { buildFilterOptions(it.map { r -> r.meta() }) } ?: SearchFilterOptions(emptyList(), emptyList(), emptyList(), emptyList())
            LaunchedEffect(current) { filterState = SearchFilterState() }
            if (current != null && current.isNotEmpty()) {
                SearchFilterBar(
                    options = filterOptions,
                    filterState = filterState,
                    onFilterChange = { filterState = it },
                    availableFilters = COD_FILTER_TYPES,
                    visibleFilters = visibleFilters,
                    onVisibleFiltersChange = { visibleFilters = it },
                )
                // Per v0.7.0: matching-count caption under the filter bar.
                Text(
                    localized("符合条件的", "Matching") + " ${filtered?.size ?: 0} " + localized("个结果: ", "results: "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                // Per v0.7.0: the search-cancel button appears below 'Searching…' after 3s.
                searching -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(localized("搜索中…", "Searching…"))
                    if (searchCancelVisible) {
                        TextButton(onClick = {
                            searchJob?.cancel()
                            searching = false
                        }) { Text(localized("取消", "Cancel")) }
                    }
                }
                filtered == null -> {}
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    // Per v0.7.0: stable key so filter/search updates keep item identity (scroll + reuse).
                    items(filtered, key = { it.fileId }) { item ->
                        val isExact = CrystallographyOpenDatabase.isExactMatch(item, query, mode)
                        Card(
                            enabled = downloadingId == null,
                            onClick = {
                                if (downloadingId != null) return@Card
                                downloadingId = item.fileId
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val target = File(context.cacheDir, "cod-${item.fileId}.cif")
                                    val result = CrystallographyOpenDatabase.downloadCif(item.fileId, target, autoConvertConventional = autoConvertCell)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        downloadingId = null
                                        result.onSuccess { parsed ->
                                            onBack()
                                            onOpenParsed(parsed, "cod-${item.fileId}.cif")
                                        }.onFailure { onMessage(it.message ?: "Download failed") }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(
                                if (isExact) Modifier.border(2.dp, Color(AppPalette.BRAND_DEEP), RoundedCornerShape(12.dp)) else Modifier
                            ),
                            colors = androidx.compose.material3.CardDefaults.cardColors(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.fileId, fontWeight = FontWeight.Bold)
                                    if (isExact) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(localized("精确匹配", "Exact match"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text("${item.formula}  ${item.spaceGroup}${if (item.sgNumber.isNotBlank()) " #${item.sgNumber}" else ""}  ${item.name}${if (item.nel > 0) "  ${item.nel} elements" else ""}")
                                if (downloadingId == item.fileId) {
                                    Text(localized("下载中…", "Downloading…"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Per v0.7.0: help hint moved to a TooltipBox above; the custom animated panel is gone.
        }
        }
        if (connectionError) {
            AlertDialog(
                onDismissRequest = { connectionError = false; onBack() },
                title = { Text(localized("无法连接", "Connection failed")) },
                text = { Text(localized("当前无法连接到COD数据库，可能是网络不可用或链路异常。", "Unable to connect to the COD database. The network may be unavailable or the link is abnormal.")) },
                confirmButton = {
                    TextButton(onClick = { connectionError = false; onBack() }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
            )
        }
    }
}

@Composable
internal fun OnlineSourcePickerDialog(
    onDismiss: () -> Unit,
    onPickCod: () -> Unit,
    onPickMp: () -> Unit,
) {
    // Per v0.7.0: plain dialog — the v0.7.0 expand/shrink animation was removed per user request.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_online)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Per v0.3.3: the two sources are presented as labelled cards with an icon and a
                // one-line description, rather than bare text buttons.
                OnlineSourceCard(
                    icon = Icons.Default.Science,
                    title = stringResource(R.string.import_cod),
                    subtitle = localized("Crystallography Open Database，无需密钥", "Crystallography Open Database, no API key required"),
                    onClick = onPickCod,
                )
                OnlineSourceCard(
                    icon = Icons.Default.CloudDownload,
                    title = stringResource(R.string.materials_project),
                    subtitle = localized("Materials Project，需 API Key", "Materials Project, requires an API key"),
                    onClick = onPickMp,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OnlineSourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Per v0.6.5: HelpDialog replaced by unified link-confirmation dialog above.

