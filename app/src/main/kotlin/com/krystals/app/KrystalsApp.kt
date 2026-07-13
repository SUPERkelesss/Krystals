@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.app.ui.KrystalsTheme
import com.krystals.app.ui.ThemeMode
import com.krystals.core.CifCodec
import com.krystals.core.CrystalEditor
import com.krystals.core.CrystalEngine
import com.krystals.core.EditCommand
import com.krystals.renderer.CrystalViewport
import com.krystals.renderer.MeasurementMode
import com.krystals.renderer.ViewerController
import com.krystals.renderer.ViewerVisibility
import com.krystals.renderer.rememberViewerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingOpen(val uri: Uri, val name: String, val text: String, val candidates: List<Int>)

@Composable
fun KrystalsRoot(
    activity: MainActivity,
    incomingUri: Uri?,
    consumeIncomingUri: () -> Unit,
    viewModel: KrystalsViewModel = viewModel(),
) {
    val preferences = remember { activity.getSharedPreferences("krystals", 0) }
    var themeMode by remember {
        mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("theme", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM))
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingOpen by remember { mutableStateOf<PendingOpen?>(null) }
    var pendingSaveTabId by remember { mutableStateOf<String?>(null) }
    var closeRequest by remember { mutableStateOf<Int?>(null) }
    var exitRequest by remember { mutableStateOf(false) }

    fun showMessage(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    fun loadUri(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = activity.contentResolver
                    runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    val text = FileRepository.readText(resolver, uri)
                    require(text.contains(Regex("(?im)^\\s*data_"))) { "Not a CIF file" }
                    val document = CifCodec.parse(text)
                    val candidates = CifCodec.structuralBlockIndices(document)
                    require(candidates.isNotEmpty()) { "No crystal structure found" }
                    PendingOpen(uri, FileRepository.displayName(resolver, uri), text, candidates)
                }
            }.onSuccess { result ->
                if (result.candidates.size == 1) {
                    val parsed = CifCodec.parseStructure(result.text, result.candidates.first())
                    viewModel.add(parsed, result.name, result.uri)
                } else pendingOpen = result
            }.onFailure { showMessage(it.message ?: "Unable to open CIF") }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::loadUri) }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("chemical/x-cif")) { uri ->
        val tab = viewModel.tabs.firstOrNull { it.id == pendingSaveTabId }
        pendingSaveTabId = null
        if (uri != null && tab != null) {
            scope.launch {
                runCatching {
                    val content = CifCodec.write(tab.parsed, tab.structure, tab.structure.bondRules)
                    withContext(Dispatchers.IO) { FileRepository.write(activity.contentResolver, uri, content) }
                    tab.uri = uri; tab.isNew = false; tab.dirty = false; tab.savedName = tab.name
                    tab.parsed = CifCodec.parseStructure(content, tab.parsed.blockIndex)
                }.onSuccess { showMessage("Saved ${tab.name}") }.onFailure { showMessage(it.message ?: "Save failed") }
            }
        }
    }

    fun save(tab: DocumentTab) {
        val uri = tab.uri
        if (uri == null || tab.isNew || tab.name != tab.savedName) {
            pendingSaveTabId = tab.id
            createLauncher.launch(tab.name.ensureCifExtension())
            return
        }
        scope.launch {
            runCatching {
                val content = CifCodec.write(tab.parsed, tab.structure, tab.structure.bondRules)
                withContext(Dispatchers.IO) { FileRepository.write(activity.contentResolver, uri, content) }
                tab.parsed = CifCodec.parseStructure(content, tab.parsed.blockIndex)
                tab.dirty = false
            }.onSuccess { showMessage("Saved ${tab.name}") }.onFailure { showMessage(it.message ?: "Save failed") }
        }
    }

    fun exportCurrent() {
        val doExport = {
            scope.launch {
                runCatching { FileRepository.exportPng(activity.contentResolver, FileRepository.capture(activity)) }
                    .onSuccess { showMessage("Exported to Pictures/Krystals") }
                    .onFailure { showMessage(it.message ?: "Export failed") }
            }
        }
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Launched through permission contract below.
        } else doExport()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { runCatching { FileRepository.exportPng(activity.contentResolver, FileRepository.capture(activity)) }.onSuccess { showMessage("Exported to Pictures/Krystals") }.onFailure { showMessage(it.message ?: "Export failed") } }
        else showMessage("Storage permission is required on Android 8–9")
    }
    fun requestExport() {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else exportCurrent()
    }

    LaunchedEffect(incomingUri) {
        incomingUri?.let { loadUri(it); consumeIncomingUri() }
    }

    fun requestExit() {
        if (viewModel.tabs.any { it.dirty }) exitRequest = true else activity.finishAndRemoveTask()
    }
    BackHandler(enabled = viewModel.tabs.isNotEmpty()) {
        if (viewModel.current?.editorOpen == true) viewModel.current?.editorOpen = false else requestExit()
    }

    KrystalsTheme(themeMode) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outerPadding ->
            Box(Modifier.fillMaxSize().padding(outerPadding)) {
                if (viewModel.tabs.isEmpty()) {
                    HomeScreen(
                        onOpen = { openLauncher.launch(arrayOf("chemical/x-cif", "text/plain", "application/octet-stream")) },
                        onNew = viewModel::createNew,
                        onMaterials = { showMessage(activity.getString(R.string.coming_soon)) },
                        themeMode = themeMode,
                        onTheme = { mode -> themeMode = mode; preferences.edit().putString("theme", mode.name).apply() },
                    )
                } else {
                    ViewerScreen(
                        viewModel = viewModel,
                        onSave = ::save,
                        onNew = viewModel::createNew,
                        onMaterials = { showMessage(activity.getString(R.string.coming_soon)) },
                        onExport = ::requestExport,
                        onExit = ::requestExit,
                        onClose = { index -> if (viewModel.tabs[index].dirty) closeRequest = index else viewModel.close(index) },
                        themeMode = themeMode,
                        onTheme = { mode -> themeMode = mode; preferences.edit().putString("theme", mode.name).apply() },
                        onMessage = ::showMessage,
                    )
                }
            }
        }
    }

    pendingOpen?.let { pending ->
        val document = remember(pending) { CifCodec.parse(pending.text) }
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text("Select structure / 选择结构") },
            text = { Column { pending.candidates.forEach { index -> TextButton(onClick = {
                val parsed = CifCodec.parseStructure(pending.text, index)
                viewModel.add(parsed, pending.name, pending.uri)
                pendingOpen = null
            }) { Text(document.blocks[index].name) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    closeRequest?.let { index ->
        val tab = viewModel.tabs.getOrNull(index)
        if (tab != null) AlertDialog(
            onDismissRequest = { closeRequest = null },
            title = { Text("Save changes? / 保存修改？") },
            text = { Text(tab.name) },
            confirmButton = { TextButton(onClick = { save(tab); closeRequest = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { Row { TextButton(onClick = { viewModel.close(index); closeRequest = null }) { Text(stringResource(R.string.discard)) }; TextButton(onClick = { closeRequest = null }) { Text(stringResource(R.string.cancel)) } } },
        ) else closeRequest = null
    }

    if (exitRequest) AlertDialog(
        onDismissRequest = { exitRequest = false },
        title = { Text("Unsaved files / 文件尚未保存") },
        text = { Text(viewModel.tabs.filter { it.dirty }.joinToString("\n") { "• ${it.name}" }) },
        confirmButton = { TextButton(onClick = { viewModel.current?.let(::save); exitRequest = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { Row { TextButton(onClick = { activity.finishAndRemoveTask() }) { Text(stringResource(R.string.discard)) }; TextButton(onClick = { exitRequest = false }) { Text(stringResource(R.string.cancel)) } } },
    )
}

@Composable
private fun HomeScreen(
    onOpen: () -> Unit,
    onNew: () -> Unit,
    onMaterials: () -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ThemeSelector(themeMode, onTheme, Modifier.align(Alignment.TopEnd).padding(12.dp))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AssetImage("main.png", Modifier.size(230.dp), ContentScale.Fit)
            Text("Krystals", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_file)) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_file)) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onMaterials, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Science, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.materials_project)) }
        }
    }
}

@Composable
private fun ViewerScreen(
    viewModel: KrystalsViewModel,
    onSave: (DocumentTab) -> Unit,
    onNew: () -> Unit,
    onMaterials: () -> Unit,
    onExport: () -> Unit,
    onExit: () -> Unit,
    onClose: (Int) -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onMessage: (String) -> Unit,
) {
    val tab = viewModel.current ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var toolOpen by remember(tab.id) { mutableStateOf(false) }
    var alignOpen by remember { mutableStateOf(false) }
    var measureOpen by remember { mutableStateOf(false) }
    var displayOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    val controller = rememberViewerController()
    val sceneResult = remember(tab.structure, tab.expansion) { runCatching { CrystalEngine.buildScene(tab.structure, tab.expansion) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(tab.name + if (tab.dirty) " *" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }; DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menuOpen = false; onNew() })
                DropdownMenuItem(text = { Text(stringResource(R.string.materials_project)) }, leadingIcon = { Icon(Icons.Default.Science, null) }, onClick = { menuOpen = false; onMaterials() })
                DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuOpen = false; onSave(tab) })
                DropdownMenuItem(text = { Text(stringResource(R.string.appearance)) }, leadingIcon = { Icon(Icons.Default.ColorLens, null) }, onClick = { menuOpen = false; appearanceOpen = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.export_image)) }, leadingIcon = { Icon(Icons.Default.Photo, null) }, onClick = { menuOpen = false; onExport() })
                Divider()
                DropdownMenuItem(text = { Text(stringResource(R.string.exit)) }, onClick = { menuOpen = false; onExit() })
            } } },
            actions = { ThemeSelector(themeMode, onTheme); IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Default.Settings, null) } },
        )
        DocumentTabs(viewModel, onClose)
        Box(Modifier.fillMaxSize()) {
            sceneResult.onSuccess { snapshot ->
                CrystalViewport(
                    snapshot = snapshot,
                    appearance = tab.appearance,
                    controller = controller,
                    visibility = tab.visibility,
                    selectedAtomIds = tab.selectedAtomIds,
                    measurementMode = tab.measurementMode,
                    onViewMoved = { if (tab.measurementMode != MeasurementMode.NONE) tab.selectedAtomIds = emptyList() },
                    onAtomTap = { atom ->
                        when (tab.atomEditMode) {
                            AtomEditMode.DELETE_NEXT -> {
                                viewModel.updateStructure(tab, CrystalEditor.apply(tab.structure, EditCommand.DeleteAtom(atom.siteId)).structure)
                                tab.atomEditMode = AtomEditMode.NONE
                            }
                            AtomEditMode.MODIFY_NEXT -> {
                                tab.editingSiteId = atom.siteId; tab.atomEditMode = AtomEditMode.NONE; tab.editorOpen = true
                            }
                            AtomEditMode.NONE -> {
                                val expected = when (tab.measurementMode) { MeasurementMode.LENGTH -> 2; MeasurementMode.ANGLE -> 3; MeasurementMode.DIHEDRAL -> 4; else -> 1 }
                                tab.selectedAtomIds = if (tab.selectedAtomIds.size >= expected) listOf(atom.id) else tab.selectedAtomIds + atom.id
                            }
                        }
                    },
                )
            }.onFailure { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(it.message ?: "Unable to build scene", color = MaterialTheme.colorScheme.error) } }

            Column(Modifier.align(Alignment.BottomEnd).padding(18.dp), horizontalAlignment = Alignment.End) {
                if (toolOpen) {
                    ToolButton(stringResource(R.string.align), Icons.Default.FitScreen) { alignOpen = true }
                    ToolButton(stringResource(R.string.measure), Icons.Default.Straighten) { measureOpen = true }
                    ToolButton(stringResource(R.string.display), Icons.Default.Visibility) { displayOpen = true }
                    ToolButton(stringResource(R.string.information), Icons.Default.Info) { infoOpen = true }
                    ToolButton(stringResource(R.string.modify), Icons.Default.Edit) { tab.editorOpen = true }
                    ToolButton(if (controller.locked) stringResource(R.string.unlock) else stringResource(R.string.lock), if (controller.locked) Icons.Default.LockOpen else Icons.Default.Lock) { controller.locked = !controller.locked }
                    Spacer(Modifier.height(8.dp))
                }
                FloatingActionButton(onClick = { toolOpen = !toolOpen }, shape = CircleShape) { AssetImage("icon.png", Modifier.size(48.dp), ContentScale.Crop) }
            }
            if (tab.editorOpen) EditorPanel(tab, onDismiss = { tab.editorOpen = false }, onStructure = { viewModel.updateStructure(tab, it) }, onMessage = onMessage)
        }
    }

    if (alignOpen) SimpleChoiceDialog("Align / 对齐", listOf("X", "Y", "Z"), onDismiss = { alignOpen = false }) { controller.align(it.first()); alignOpen = false }
    if (measureOpen) SimpleChoiceDialog("Measure / 测量", listOf("Length / 长度", "Angle / 角度", "Dihedral / 二面角", "Off / 关闭"), onDismiss = { measureOpen = false }) { choice ->
        tab.measurementMode = when {
            choice.startsWith("Length") -> MeasurementMode.LENGTH
            choice.startsWith("Angle") -> MeasurementMode.ANGLE
            choice.startsWith("Dihedral") -> MeasurementMode.DIHEDRAL
            else -> MeasurementMode.NONE
        }
        tab.selectedAtomIds = emptyList(); measureOpen = false
    }
    if (displayOpen) DisplayDialog(tab, onDismiss = { displayOpen = false })
    if (infoOpen) InfoDialog(tab, onDismiss = { infoOpen = false })
    if (appearanceOpen) AppearanceDialog(tab, onDismiss = { appearanceOpen = false }) { viewModel.defaultAppearance = it }
}

@Composable
private fun DocumentTabs(viewModel: KrystalsViewModel, onClose: (Int) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(46.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        itemsIndexed(viewModel.tabs, key = { _, tab -> tab.id }) { index, tab ->
            var drag by remember { mutableFloatStateOf(0f) }
            Surface(
                color = if (index == viewModel.selectedIndex) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxHeight().pointerInput(tab.id, index) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { drag = 0f },
                        onDragCancel = { drag = 0f },
                        onDrag = { change, amount ->
                            change.consume(); drag += amount.x
                            if (drag > 70f && index < viewModel.tabs.lastIndex) { viewModel.move(index, index + 1); drag = 0f }
                            if (drag < -70f && index > 0) { viewModel.move(index, index - 1); drag = 0f }
                        },
                    )
                }.clickable { viewModel.select(index) },
            ) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tab.name + if (tab.dirty) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(145.dp))
                    IconButton(onClick = { onClose(index) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    ExtendedFloatingActionButton(onClick = action, text = { Text(label) }, icon = { Icon(icon, null) }, modifier = Modifier.padding(vertical = 3.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayDialog(tab: DocumentTab, onDismiss: () -> Unit) {
    val siteGroups = tab.structure.sites.distinctBy { it.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display / 显示") },
        text = { Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text("Atoms / 原子", fontWeight = FontWeight.Bold)
            FlowRow { siteGroups.forEach { site -> Row(verticalAlignment = Alignment.CenterVertically) {
                val visible = site.id !in tab.visibility.hiddenSites
                Checkbox(visible, onCheckedChange = { checked -> tab.visibility = tab.visibility.copy(hiddenSites = if (checked) tab.visibility.hiddenSites - site.id else tab.visibility.hiddenSites + site.id) })
                Text(site.label)
            } } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(tab.visibility.showBonds, onCheckedChange = { tab.visibility = tab.visibility.copy(showBonds = it) }); Text("Bonds / 键") }
            Text("Polyhedra / 多面体", fontWeight = FontWeight.Bold)
            FlowRow { siteGroups.forEach { site -> Row(verticalAlignment = Alignment.CenterVertically) {
                val enabled = site.id in tab.visibility.polyhedronSites
                Checkbox(enabled, onCheckedChange = { checked -> tab.visibility = tab.visibility.copy(polyhedronSites = if (checked) tab.visibility.polyhedronSites + site.id else tab.visibility.polyhedronSites - site.id) })
                Text(site.label)
            } } }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}

@Composable
private fun InfoDialog(tab: DocumentTab, onDismiss: () -> Unit) {
    val info = remember(tab.structure) { CrystalEngine.info(tab.structure) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crystal information / 晶体信息") },
        text = { Text(buildString {
            appendLine("Atoms / 原子数: ${info.atomCount}")
            appendLine("Space group / 空间群: ${info.spaceGroup}")
            appendLine("a = %.5f Å, b = %.5f Å, c = %.5f Å".format(info.cell.a, info.cell.b, info.cell.c))
            appendLine("α = %.4f°, β = %.4f°, γ = %.4f°".format(info.cell.alpha, info.cell.beta, info.cell.gamma))
            appendLine("Volume / 体积: %.5f Å³".format(info.volume))
            append("Density / 密度: ${info.density?.let { "%.5f g/cm³".format(it) } ?: "N/A"}")
        }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}

@Composable
private fun SimpleChoiceDialog(title: String, choices: List<String>, onDismiss: () -> Unit, onChoice: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column { choices.forEach { choice -> TextButton(onClick = { onChoice(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice) } } } }, confirmButton = {})
}

@Composable
private fun ThemeSelector(mode: ThemeMode, onTheme: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Theme") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { item -> DropdownMenuItem(text = { Text((if (item == mode) "✓ " else "") + item.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { expanded = false; onTheme(item) }) }
        }
    }
}

@Composable
private fun AssetImage(path: String, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val image = remember(path) { runCatching { context.assets.open(path).use { stream -> BitmapFactory.decodeStream(stream) }.asImageBitmap() }.getOrNull() }
    if (image != null) Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)))
}

private fun String.ensureCifExtension() = if (endsWith(".cif", true)) this else "$this.cif"
