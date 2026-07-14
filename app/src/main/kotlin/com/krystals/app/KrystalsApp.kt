@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
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
import com.krystals.core.PeriodicTable
import com.krystals.renderer.CrystalViewport
import com.krystals.renderer.CrystalImageExporter
import com.krystals.renderer.MeasurementMode
import com.krystals.renderer.ViewerController
import com.krystals.renderer.ViewerVisibility
import com.krystals.renderer.rememberViewerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class PendingOpen(val uri: Uri, val name: String, val text: String, val candidates: List<Int>)
private data class RecentEntry(val name: String, val uri: String)

private const val RECENTS_KEY = "recent_files"
private const val MAX_RECENTS = 10

private fun loadRecents(preferences: android.content.SharedPreferences): List<RecentEntry> {
    return runCatching {
        org.json.JSONArray(preferences.getString(RECENTS_KEY, "[]") ?: "[]").let { array ->
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                RecentEntry(obj.getString("name"), obj.getString("uri"))
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveRecents(preferences: android.content.SharedPreferences, recents: List<RecentEntry>) {
    val array = org.json.JSONArray().apply {
        recents.forEach { entry ->
            put(org.json.JSONObject().apply {
                put("name", entry.name)
                put("uri", entry.uri)
            })
        }
    }
    preferences.edit().putString(RECENTS_KEY, array.toString()).apply()
}

private fun addRecent(preferences: android.content.SharedPreferences, entry: RecentEntry): List<RecentEntry> {
    val current = loadRecents(preferences).filterNot { it.uri == entry.uri }
    val updated = listOf(entry) + current.take(MAX_RECENTS - 1)
    saveRecents(preferences, updated)
    return updated
}

private fun removeRecent(preferences: android.content.SharedPreferences, uri: String): List<RecentEntry> {
    val updated = loadRecents(preferences).filterNot { it.uri == uri }
    saveRecents(preferences, updated)
    return updated
}

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
    var language by remember {
        mutableStateOf(preferences.getString("language", if (java.util.Locale.getDefault().language == "zh") "zh" else "en") ?: "en")
    }
    val systemDark = isSystemInDarkTheme()
    fun applyViewerBackground(dark: Boolean) {
        val background = if (dark) 0xFF101014 else 0xFFF8F8FB
        viewModel.defaultAppearance = viewModel.defaultAppearance.copy(backgroundArgb = background)
        viewModel.tabs.forEach { tab -> tab.appearance = tab.appearance.copy(backgroundArgb = background) }
    }
    fun applyTheme(mode: ThemeMode) {
        themeMode = mode
        preferences.edit().putString("theme", mode.name).apply()
        applyViewerBackground(mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark)
    }
    LaunchedEffect(themeMode, systemDark) {
        if (themeMode == ThemeMode.SYSTEM) applyViewerBackground(systemDark)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingOpen by remember { mutableStateOf<PendingOpen?>(null) }
    var pendingSaveTabId by remember { mutableStateOf<String?>(null) }
    var closeRequest by remember { mutableStateOf<Int?>(null) }
    var exitRequest by remember { mutableStateOf(false) }
    var pendingExportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var recents by remember { mutableStateOf(loadRecents(preferences)) }
    var helpOpen by remember { mutableStateOf(false) }
    var sponsorOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }
    var mpSearchOpen by remember { mutableStateOf(false) }
    var mpKeyDialogOpen by remember { mutableStateOf(false) }

    fun showMessage(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    fun openUrl(url: String) {
        runCatching { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { showMessage("Unable to open browser") }
    }

    fun rememberOpen(uri: Uri, name: String) { recents = addRecent(preferences, RecentEntry(name, uri.toString())) }

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
                    rememberOpen(result.uri, result.name)
                } else pendingOpen = result
            }.onFailure { showMessage(it.message ?: "Unable to open CIF") }
        }
    }

    fun openRecent(entry: RecentEntry) {
        runCatching { Uri.parse(entry.uri) }.getOrNull()?.let(::loadUri)
            ?: run { recents = removeRecent(preferences, entry.uri); showMessage("Unable to open recent file") }
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
                }.onSuccess {
                    showMessage("Saved ${tab.name}")
                    rememberOpen(uri, tab.name)
                }.onFailure { showMessage(it.message ?: "Save failed") }
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bitmap = pendingExportBitmap
        pendingExportBitmap = null
        if (granted && bitmap != null) scope.launch { runCatching { FileRepository.exportPng(activity.contentResolver, bitmap) }.onSuccess { showMessage("Exported to Pictures/Krystals") }.onFailure { showMessage(it.message ?: "Export failed") } }
        else showMessage("Storage permission is required on Android 8–9")
    }
    fun requestExport(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExportBitmap = bitmap
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else scope.launch {
            runCatching { FileRepository.exportPng(activity.contentResolver, bitmap) }
                .onSuccess { showMessage("Exported to Pictures/Krystals") }
                .onFailure { showMessage(it.message ?: "Export failed") }
        }
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
                        onOpenPreset = { presetOpen = true },
                        onNew = viewModel::createNew,
                        onMaterials = {
                            if (MaterialsProject.hasKey(activity)) mpSearchOpen = true else mpKeyDialogOpen = true
                        },
                        recents = recents,
                        onRecent = ::openRecent,
                    )
                } else {
                    ViewerScreen(
                        viewModel = viewModel,
                        onSave = ::save,
                        onOpen = { openLauncher.launch(arrayOf("chemical/x-cif", "text/plain", "application/octet-stream")) },
                        onOpenPreset = { presetOpen = true },
                        onSaveToPreset = {
                            val tab = viewModel.current ?: return@ViewerScreen
                            runCatching {
                                PresetRepository.saveToPreset(activity, tab.parsed, tab.structure, tab.name)
                                showMessage("Saved to presets")
                            }.onFailure { showMessage(it.message ?: "Save failed") }
                        },
                        onNew = viewModel::createNew,
                        onMaterials = {
                            if (MaterialsProject.hasKey(activity)) mpSearchOpen = true else mpKeyDialogOpen = true
                        },
                        onExport = ::requestExport,
                        onExit = ::requestExit,
                        onClose = { index -> if (viewModel.tabs[index].dirty) closeRequest = index else viewModel.close(index) },
                        themeMode = themeMode,
                        onTheme = ::applyTheme,
                        language = language,
                        onLanguage = { value -> language = value; preferences.edit().putString("language", value).apply(); activity.recreate() },
                        onMessage = ::showMessage,
                        recents = recents,
                        onRecent = ::openRecent,
                        onHelp = { helpOpen = true },
                        onAbout = { aboutOpen = true },
                        onSponsor = { sponsorOpen = true },
                    )
                }
            }
        }
    }

    pendingOpen?.let { pending ->
        val document = remember(pending) { CifCodec.parse(pending.text) }
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text(localized("选择结构", "Select structure")) },
            text = { Column { pending.candidates.forEach { index -> TextButton(onClick = {
                val parsed = CifCodec.parseStructure(pending.text, index)
                viewModel.add(parsed, pending.name, pending.uri)
                rememberOpen(pending.uri, pending.name)
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
            title = { Text(localized("保存修改？", "Save changes?")) },
            text = { Text(tab.name) },
            confirmButton = { TextButton(onClick = { save(tab); closeRequest = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { Row { TextButton(onClick = { viewModel.close(index); closeRequest = null }) { Text(stringResource(R.string.discard)) }; TextButton(onClick = { closeRequest = null }) { Text(stringResource(R.string.cancel)) } } },
        ) else closeRequest = null
    }

    if (exitRequest) AlertDialog(
        onDismissRequest = { exitRequest = false },
        title = { Text(localized("文件尚未保存", "Unsaved files")) },
        text = { Text(viewModel.tabs.filter { it.dirty }.joinToString("\n") { "• ${it.name}" }) },
        confirmButton = { TextButton(onClick = { viewModel.current?.let(::save); exitRequest = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { Row { TextButton(onClick = { activity.finishAndRemoveTask() }) { Text(stringResource(R.string.discard)) }; TextButton(onClick = { exitRequest = false }) { Text(stringResource(R.string.cancel)) } } },
    )

    if (helpOpen) HelpDialog(
        onDismiss = { helpOpen = false },
        onConfirm = { helpOpen = false; openUrl("https://www.kelesss.art") },
    )
    if (sponsorOpen) SponsorDialog(onDismiss = { sponsorOpen = false })
    if (aboutOpen) AboutScreen(onBack = { aboutOpen = false })
    if (presetOpen) PresetLibraryDialog(
        context = activity,
        viewModel = viewModel,
        onDismiss = { presetOpen = false },
        onMessage = ::showMessage,
    )
    if (mpKeyDialogOpen) MpApiKeyDialog(
        context = activity,
        onDismiss = { mpKeyDialogOpen = false },
        onOpenUrl = ::openUrl,
        onConfirmed = { mpKeyDialogOpen = false; mpSearchOpen = true },
        onMessage = ::showMessage,
    )
    if (mpSearchOpen) MpSearchScreen(
        context = activity,
        viewModel = viewModel,
        onBack = { mpSearchOpen = false },
        onChangeKey = { mpSearchOpen = false; mpKeyDialogOpen = true },
        onMessage = ::showMessage,
    )
}

@Composable
private fun HomeScreen(
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onNew: () -> Unit,
    onMaterials: () -> Unit,
    recents: List<RecentEntry>,
    onRecent: (RecentEntry) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AssetImage("main.png", Modifier.size(230.dp), ContentScale.Fit)
            Text("Krystals", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_file)) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenPreset, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_preset_library)) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_file)) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onMaterials, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.Science, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.materials_project)) }
            if (recents.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(localized("历史打开文件", "Recent files"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(recents, key = { _, item -> item.uri }) { _, entry ->
                        Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 3.dp, modifier = Modifier.clickable { onRecent(entry) }) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp))
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerScreen(
    viewModel: KrystalsViewModel,
    onSave: (DocumentTab) -> Unit,
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onSaveToPreset: () -> Unit,
    onNew: () -> Unit,
    onMaterials: () -> Unit,
    onExport: (Bitmap) -> Unit,
    onExit: () -> Unit,
    onClose: (Int) -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    onMessage: (String) -> Unit,
    recents: List<RecentEntry>,
    onRecent: (RecentEntry) -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onSponsor: () -> Unit,
) {
    val tab = viewModel.current ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var toolOpen by remember(tab.id) { mutableStateOf(false) }
    var alignOpen by remember { mutableStateOf(false) }
    var measureOpen by remember { mutableStateOf(false) }
    var displayOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var floatingX by remember(tab.id) { mutableFloatStateOf(0f) }
    var floatingY by remember(tab.id) { mutableFloatStateOf(0f) }
    var legendExpanded by remember(tab.id) { mutableStateOf(false) }
    val lengthChoice = localized("长度", "Length")
    val angleChoice = localized("角度", "Angle")
    val dihedralChoice = localized("二面角", "Dihedral")
    val offChoice = localized("关闭", "Off")
    val controller = rememberViewerController()
    val sceneResult = remember(tab.structure, tab.expansion) { runCatching { CrystalEngine.buildScene(tab.structure, tab.expansion) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Krystals", fontWeight = FontWeight.Bold, maxLines = 1) },
            navigationIcon = { Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }; DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.import_local)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { menuOpen = false; onOpen() })
                if (recents.isNotEmpty()) {
                    HorizontalDivider()
                    Text(localized("历史打开文件", "Recent files"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    recents.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { menuOpen = false; onRecent(entry) },
                        )
                    }
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.open_preset_library)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { menuOpen = false; onOpenPreset() })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menuOpen = false; onNew() })
                DropdownMenuItem(text = { Text(stringResource(R.string.materials_project)) }, leadingIcon = { Icon(Icons.Default.Science, null) }, onClick = { menuOpen = false; onMaterials() })
                DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuOpen = false; onSave(tab) })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_to_presets)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuOpen = false; onSaveToPreset() })
                DropdownMenuItem(text = { Text(stringResource(R.string.export_image)) }, leadingIcon = { Icon(Icons.Default.Photo, null) }, onClick = {
                    menuOpen = false
                    sceneResult.getOrNull()?.let { snapshot ->
                        onExport(CrystalImageExporter.render(snapshot, tab.appearance, controller, tab.visibility, tab.selectedAtomIds, tab.measurementMode, tab.measurementLocked, tab.inspectedAtomId, tab.inspectionLocked))
                    } ?: onMessage("Unable to export current crystal")
                })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.language)) }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { }, trailingIcon = {
                    Row {
                        TextButton(onClick = { menuOpen = false; onLanguage("zh") }, enabled = language != "zh") { Text("中", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = { menuOpen = false; onLanguage("en") }, enabled = language != "en") { Text("EN", fontWeight = FontWeight.Bold) }
                    }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.help)) }, onClick = { menuOpen = false; onHelp() })
                DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { menuOpen = false; onAbout() })
                DropdownMenuItem(text = { Text(stringResource(R.string.sponsor)) }, onClick = { menuOpen = false; onSponsor() })
                DropdownMenuItem(text = { Text(stringResource(R.string.exit)) }, onClick = { menuOpen = false; onExit() })
            } } },
            actions = {
                IconButton(onClick = { appearanceOpen = true }) { Icon(Icons.Default.ColorLens, null) }
                ThemeSelector(themeMode, onTheme)
            },
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
                    measurementLocked = tab.measurementLocked,
                    onMeasurementLockToggle = { tab.measurementLocked = !tab.measurementLocked },
                    inspectedAtomId = tab.inspectedAtomId,
                    inspectionLocked = tab.inspectionLocked,
                    onInspectAtom = { atom -> tab.inspectedAtomId = atom.id; tab.inspectionLocked = false },
                    onInspectionLockToggle = { tab.inspectionLocked = !tab.inspectionLocked },
                    onViewMoved = {
                        if (tab.measurementMode != MeasurementMode.NONE && !tab.measurementLocked) tab.selectedAtomIds = emptyList()
                        if (!tab.inspectionLocked) tab.inspectedAtomId = null
                    },
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

            val legendElements = sceneResult.getOrNull()?.atoms
                ?.filterNot { it.siteId in tab.visibility.hiddenSites }
                ?.map { it.element }?.distinct()?.sorted().orEmpty()
            ElementLegend(
                elements = legendElements,
                expanded = legendExpanded,
                onToggle = { legendExpanded = !legendExpanded },
                elementArgbOverrides = tab.structure.elementArgbOverrides,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            )

            val floatingAlpha by animateFloatAsState(targetValue = if (toolOpen) 1f else 0.45f, label = "floatingAlpha")
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .size(180.dp)
                    .offset { IntOffset(floatingX.roundToInt(), floatingY.roundToInt()) }
                    .alpha(floatingAlpha)
                    .pointerInput(tab.id) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            floatingX += amount.x
                            floatingY += amount.y
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (toolOpen) {
                    val radius = 62.dp
                    val tools = listOf(
                        Icons.Default.FitScreen to { alignOpen = true },
                        Icons.Default.Straighten to { measureOpen = true },
                        Icons.Default.Visibility to { displayOpen = true },
                        Icons.Default.Info to { infoOpen = true },
                        Icons.Default.Edit to { tab.editorOpen = true },
                        (if (controller.locked) Icons.Default.LockOpen else Icons.Default.Lock) to { controller.locked = !controller.locked },
                    )
                    tools.forEachIndexed { index, (icon, action) ->
                        val angle = Math.PI / 180 * (index * 60 - 90)
                        FloatingActionButton(
                            onClick = action,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(40.dp)
                                .offset(
                                    x = (radius.value * cos(angle)).dp,
                                    y = (radius.value * sin(angle)).dp,
                                ),
                        ) { Icon(icon, null) }
                    }
                }
                FloatingActionButton(
                    onClick = { toolOpen = !toolOpen },
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp),
                ) { AssetImage("icon.png", Modifier.size(44.dp), ContentScale.Crop) }
            }
            if (tab.editorOpen) EditorPanel(tab, onDismiss = { tab.editorOpen = false }, onStructure = { viewModel.updateStructure(tab, it) }, onMessage = onMessage)
        }
    }

    if (alignOpen) AlignDialog(
        onDismiss = { alignOpen = false },
        onChoice = { choice ->
            when (choice) {
                "X", "Y", "Z" -> controller.align(choice.first())
                "a", "b", "c" -> controller.alignCellAxis(choice.first(), tab.structure.cell)
            }
            alignOpen = false
        },
    )
    if (measureOpen) MeasureDialog(
        length = lengthChoice,
        angle = angleChoice,
        dihedral = dihedralChoice,
        off = offChoice,
        onDismiss = { measureOpen = false },
        onChoice = { choice ->
            tab.measurementMode = when {
                choice == lengthChoice -> MeasurementMode.LENGTH
                choice == angleChoice -> MeasurementMode.ANGLE
                choice == dihedralChoice -> MeasurementMode.DIHEDRAL
                else -> MeasurementMode.NONE
            }
            tab.selectedAtomIds = emptyList(); measureOpen = false
        },
    )
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
private fun ElementLegend(elements: List<String>, expanded: Boolean, onToggle: () -> Unit, elementArgbOverrides: Map<String, Long>, modifier: Modifier = Modifier) {
    Surface(modifier.alpha(0.84f), shape = RoundedCornerShape(14.dp), tonalElevation = 5.dp) {
        Column(Modifier.width(if (expanded) 130.dp else 140.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, modifier = Modifier.size(18.dp))
                Text(localized("图例", "Legend"), modifier = Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(18.dp))
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    elements.forEach { element ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(androidx.compose.ui.graphics.Color(PeriodicTable.resolveArgb(element, elementArgbOverrides)), CircleShape))
                            Text(element, modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayDialog(tab: DocumentTab, onDismiss: () -> Unit) {
    val sites = tab.structure.sites
    val siteIds = remember(tab.structure) { sites.map { it.id }.toSet() }
    val rules = tab.structure.bondRules
    val allSitesVisible = siteIds.isNotEmpty() && tab.visibility.hiddenSites.intersect(siteIds).isEmpty()
    val allBondsVisible = tab.visibility.showBonds && tab.visibility.hiddenBondPairs.none { key -> rules.any { it.key == key } }
    val allPolyhedraEnabled = siteIds.isNotEmpty() && siteIds.all { it in tab.visibility.polyhedronSites }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("显示", "Display")) },
        text = { Column(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localized("原子", "Atoms"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allSitesVisible, onCheckedChange = { checked ->
                        tab.visibility = tab.visibility.copy(hiddenSites = if (checked) emptySet() else siteIds)
                    })
                    Text(stringResource(R.string.select_all))
                }
            }
            FlowRow { sites.forEach { site -> Row(verticalAlignment = Alignment.CenterVertically) {
                val visible = site.id !in tab.visibility.hiddenSites
                Checkbox(visible, onCheckedChange = { checked -> tab.visibility = tab.visibility.copy(hiddenSites = if (checked) tab.visibility.hiddenSites - site.id else tab.visibility.hiddenSites + site.id) })
                Text(site.label)
            } } }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localized("化学键", "Bonds"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allBondsVisible, onCheckedChange = { checked ->
                        tab.visibility = tab.visibility.copy(
                            showBonds = checked,
                            hiddenBondPairs = if (checked) emptySet() else rules.map { it.key }.toSet(),
                        )
                    })
                    Text(stringResource(R.string.select_all))
                }
            }
            if (rules.isEmpty()) {
                Text(localized("无化学键规则", "No bond rules"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            } else {
                FlowRow { rules.forEach { rule ->
                    val labelA = sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                    val labelB = sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                    val label = "$labelA—$labelB %.3f–%.3f Å".format(rule.minAngstrom, rule.maxAngstrom)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val visible = tab.visibility.showBonds && rule.key !in tab.visibility.hiddenBondPairs
                        Checkbox(visible, onCheckedChange = { checked ->
                            tab.visibility = tab.visibility.copy(
                                showBonds = true,
                                hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - rule.key else tab.visibility.hiddenBondPairs + rule.key,
                            )
                        })
                        Text(label)
                    }
                } }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localized("多面体", "Polyhedra"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allPolyhedraEnabled, onCheckedChange = { checked ->
                        tab.visibility = tab.visibility.copy(polyhedronSites = if (checked) siteIds else emptySet())
                    })
                    Text(stringResource(R.string.select_all))
                }
            }
            FlowRow { sites.forEach { site -> Row(verticalAlignment = Alignment.CenterVertically) {
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
        title = { Text(localized("晶体信息", "Crystal information")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                InfoSection(title = localized("化学组成", "Composition"), value = info.composition, valueStyle = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                InfoSection(title = localized("空间群", "Space group"), value = info.spaceGroup, valueStyle = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text(localized("晶胞参数", "Cell parameters"), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    InfoCell(label = "a", value = "%.5f Å".format(info.cell.a), Modifier.weight(1f))
                    InfoCell(label = "b", value = "%.5f Å".format(info.cell.b), Modifier.weight(1f))
                    InfoCell(label = "c", value = "%.5f Å".format(info.cell.c), Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    InfoCell(label = "α", value = "%.4f°".format(info.cell.alpha), Modifier.weight(1f))
                    InfoCell(label = "β", value = "%.4f°".format(info.cell.beta), Modifier.weight(1f))
                    InfoCell(label = "γ", value = "%.4f°".format(info.cell.gamma), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                InfoRow(label = localized("体积", "Volume"), value = "%.5f Å³".format(info.volume))
                InfoRow(label = localized("密度", "Density"), value = info.density?.let { "%.5f g/cm³".format(it) } ?: "N/A")
                InfoRow(label = localized("原子数", "Atoms"), value = info.atomCount.toString())
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

@Composable
private fun SimpleChoiceDialog(title: String, choices: List<String>, onDismiss: () -> Unit, onChoice: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column { choices.forEach { choice -> TextButton(onClick = { onChoice(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice) } } } }, confirmButton = {})
}

@Composable
private fun AlignDialog(onDismiss: () -> Unit, onChoice: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("对齐", "Align")) },
        text = {
            Column {
                Text(localized("空间直角坐标系", "Cartesian"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(104.dp)) {
                    items(listOf("X", "Y", "Z")) { ChoiceTile(it, onChoice) }
                }
                Spacer(Modifier.height(14.dp))
                Text(localized("晶胞轴", "Cell axes"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(104.dp)) {
                    items(listOf("a", "b", "c")) { ChoiceTile(it, onChoice) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MeasureDialog(
    length: String,
    angle: String,
    dihedral: String,
    off: String,
    onDismiss: () -> Unit,
    onChoice: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("测量", "Measure")) },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.height(156.dp)) {
                items(listOf(length, angle, dihedral, off)) { ChoiceTile(it, onChoice, aspect = 1.8f) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceTile(label: String, onClick: (String) -> Unit, aspect: Float = 1f) {
    Card(
        onClick = { onClick(label) },
        modifier = Modifier.padding(4.dp).aspectRatio(aspect),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = if (aspect == 1f) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PresetLibraryDialog(
    context: Context,
    viewModel: KrystalsViewModel,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var presets by remember { mutableStateOf(PresetRepository.listPresets(context)) }
    var pendingDelete by remember { mutableStateOf<PresetEntry?>(null) }
    fun refresh() { presets = PresetRepository.listPresets(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_library)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(presets, key = { it.name + it.source }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable {
                            runCatching { PresetRepository.openPreset(context, entry) }
                                .onSuccess { parsed ->
                                    viewModel.add(parsed, entry.name, null, isNew = true)
                                    onDismiss()
                                }
                                .onFailure { onMessage(it.message ?: "Unable to open preset") }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.name, modifier = Modifier.weight(1f))
                        Text(
                            if (entry.source == PresetSource.BUNDLED) stringResource(R.string.bundled) else stringResource(R.string.user_saved),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        if (entry.source == PresetSource.USER) {
                            IconButton(onClick = { pendingDelete = entry }) { Icon(Icons.Default.Delete, null) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_preset_title)) },
            text = { Text(entry.name) },
            confirmButton = { TextButton(onClick = { PresetRepository.deletePreset(entry); pendingDelete = null; refresh() }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MpApiKeyDialog(
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
                TextButton(onClick = { onOpenUrl("https://www.kelesss.art") }) { Text(localized("如何创建 apikey？", "How to create an API key?")) }
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
private fun MpSearchScreen(
    context: Context,
    viewModel: KrystalsViewModel,
    onBack: () -> Unit,
    onChangeKey: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MpSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.Close, null) }
            Text(localized("Materials Project 搜索", "Materials Project search"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onChangeKey) { Text(localized("修改 apikey…", "Change API key…")) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text(localized("搜索", "Search")) }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (query.isBlank()) return@Button
                searching = true
                results = null
                scope.launch {
                    MaterialsProject.search(context, query)
                        .onSuccess { results = it }
                        .onFailure { onMessage(it.message ?: "Search failed") }
                    searching = false
                }
            }) { Text(localized("搜索", "Search")) }
        }
        Spacer(Modifier.height(12.dp))
        when {
            searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索中…", "Searching…")) }
            results == null -> {}
            results!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results!!) { item ->
                    Card(
                        onClick = {
                            scope.launch {
                                val target = File(context.cacheDir, "${item.materialId}.cif")
                                MaterialsProject.downloadCif(context, item.materialId, target)
                                    .onSuccess { parsed ->
                                        viewModel.add(parsed, "${item.materialId}.cif", Uri.fromFile(target), isNew = true)
                                        onBack()
                                    }
                                    .onFailure { onMessage(it.message ?: "Download failed") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.materialId, fontWeight = FontWeight.Bold)
                            Text("${item.formula}  ${item.crystalSystem}  ${item.spaceGroup}  ${item.nsites} sites")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.help_title)) },
        text = { Text(stringResource(R.string.help_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SponsorDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sponsor_title)) },
        text = { Text("Sponsor page placeholder.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    BackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.about)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            AssetImage("icon.png", Modifier.size(96.dp), ContentScale.Fit)
            Spacer(Modifier.height(16.dp))
            Text("Krystals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${stringResource(R.string.version)} ${com.krystals.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(32.dp))
            Text("© 2026 kelesss.art", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeSelector(mode: ThemeMode, onTheme: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(when (mode) { ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto; ThemeMode.LIGHT -> Icons.Default.LightMode; ThemeMode.DARK -> Icons.Default.DarkMode }, "Theme")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { item ->
                val label = when (item) { ThemeMode.SYSTEM -> localized("跟随系统", "System"); ThemeMode.LIGHT -> localized("浅色", "Light"); ThemeMode.DARK -> localized("深色", "Dark") }
                DropdownMenuItem(text = { Text((if (item == mode) "✓ " else "") + label) }, onClick = { expanded = false; onTheme(item) })
            }
        }
    }
}

@Composable
private fun LanguageSelector(language: String, onLanguage: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(if (language == "zh") "中" else "EN", fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(if (language == "zh") "✓ 中文" else "中文") }, onClick = { expanded = false; onLanguage("zh") })
            DropdownMenuItem(text = { Text(if (language == "en") "✓ English" else "English") }, onClick = { expanded = false; onLanguage("en") })
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
