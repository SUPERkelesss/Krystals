@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.app.ui.KrystalsTheme
import com.krystals.app.ui.ThemeMode
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.analysis.structure.StructureAnalyzer
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import com.krystals.crystal.renderer.CrystalViewport
import com.krystals.crystal.renderer.CrystalImageExporter
import com.krystals.crystal.renderer.LockedMeasurement
import com.krystals.crystal.renderer.MeasurementMode
import com.krystals.crystal.renderer.RenderPalette
import com.krystals.crystal.renderer.ViewerAppearance
import com.krystals.crystal.renderer.ViewerVisibility
import com.krystals.crystal.renderer.rememberViewerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class PendingOpen(val uri: Uri, val name: String, val text: String, val candidates: List<Int>)

/** Per v0.5.3b: holds an open request whose expanded atom count exceeds the warn threshold until
 *  the user confirms or cancels. */
private data class PendingLargeOpen(val parsed: ParsedStructure, val name: String, val uri: Uri?, val expandedEstimate: Int)

/** Per v0.5.3b: scene build runs off the UI thread with this cap; on timeout it fails with a
 *  readable error instead of hanging the viewer. */
private const val BUILD_SCENE_TIMEOUT_MS = 15_000L

/** Per v0.5.3b: warn before opening a cell whose asymmetric expansion exceeds this many atoms. */
private const val LARGE_CELL_WARN_THRESHOLD = 1000

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
    // Per v0.5.2a: load the persisted global appearance once at startup (falls back to defaults).
    LaunchedEffect(Unit) {
        preferences.getString(AppearanceStore.KEY, null)?.let { json ->
            AppearanceStore.fromJson(json)?.let { ap -> viewModel.applyAppearance(ap) }
        }
    }
    val systemDark = isSystemInDarkTheme()
    fun applyViewerBackground(dark: Boolean) {
        val background = if (dark) 0xFF101014 else 0xFFF8F8FB
        viewModel.defaultAppearance = viewModel.defaultAppearance.copy(backgroundArgb = background)
        viewModel.tabs.forEach { tab -> tab.appearance = tab.appearance.copy(backgroundArgb = background) }
    }
    // Per v0.5.2a: persist + globally apply a new appearance (default + every open tab).
    fun applyViewerAppearance(ap: ViewerAppearance) {
        viewModel.applyAppearance(ap)
        preferences.edit().putString(AppearanceStore.KEY, AppearanceStore.run { ap.toJson() }).apply()
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
    // Per v0.5.0: global "计算中..." overlay shown while bond rules are recomputed (open file, add/
    // delete atom, transform, hex/rhom conversion) off the UI thread.
    var computing by remember { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf<PendingOpen?>(null) }
    var pendingSaveTabId by remember { mutableStateOf<String?>(null) }
    var closeRequest by remember { mutableStateOf<Int?>(null) }
    var exitRequest by remember { mutableStateOf(false) }
    var pendingExportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    var sponsorOpen by remember { mutableStateOf(false) }
    var sponsorLaunchCount by remember { mutableStateOf(0) }
    var aboutOpen by remember { mutableStateOf(false) }
    // Per v0.2.2: prompt for sponsorship on the 5th, 20th, 50th, and every 50th launch thereafter.
    // Per v0.4.0: once the device holds a valid activation code the automatic prompt is suppressed.
    LaunchedEffect(Unit) {
        val count = preferences.getInt("launch_count", 0) + 1
        preferences.edit().putInt("launch_count", count).apply()
        val prompt = count == 5 || count == 20 || count == 50 || (count > 50 && count % 50 == 0)
        if (prompt && !ActivationManager.isActivated(activity)) { sponsorLaunchCount = count; sponsorOpen = true }
    }
    var presetOpen by remember { mutableStateOf(false) }
    var mpSearchOpen by remember { mutableStateOf(false) }
    var mpKeyDialogOpen by remember { mutableStateOf(false) }
    // Per v0.4.0: activation-code dialog (reached from the sponsor dialog) and the MP "premium
    // content" gate shown when MP is picked without an active code.
    var activationOpen by remember { mutableStateOf(false) }
    var mpPremiumOpen by remember { mutableStateOf(false) }
    // Per v0.4.0: one-time caution shown to activated users before the MP flow, explaining the
    // new/legacy API trade-off. Dismissable permanently via the "不再显示" checkbox.
    var mpCautionOpen by remember { mutableStateOf(false) }
    // Per v0.3.1: MP and COD imports share an "import from online sources" entry that opens a
    // picker; the picker routes to the COD search screen (no key) or the MP flow (key-gated).
    var onlineSourceOpen by remember { mutableStateOf(false) }
    var codSearchOpen by remember { mutableStateOf(false) }

    fun showMessage(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    // Per v0.5.2b: resolved string for the smart-ionic timeout snackbar (localized() is @Composable).
    val smartIonicTimeoutMessage = localized("智能离子计算超时，已回退键合半径", "Smart ionic timed out, fell back to bonding radii")

    // Per v0.5.3b: when an opened cell expands to more than [LARGE_CELL_WARN_THRESHOLD] atoms the
    // user is warned before the (possibly degraded) scene is built. Resolved once; reused below.
    val largeCellWarningMessage = localized("原子数较多", "Many atoms")
    var pendingLargeOpen by remember { mutableStateOf<PendingLargeOpen?>(null) }

    /**
     * Per v0.5.0: run a bond-recomputing operation off the UI thread with the global "计算中..."
     * overlay. [block] runs on Dispatchers.Default and returns the new structure (or null to abort
     * silently, e.g. on validation failure where the caller already reported the error).
     */
    fun runWithBondComputation(block: suspend () -> EditResult?) {
        if (computing) return
        computing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { block() } }
            computing = false
            result.getOrNull()?.let { editResult ->
                viewModel.current?.let { viewModel.updateAnalysis(it, editResult) }
            }
            result.onFailure { showMessage(it.message ?: "Operation failed") }
        }
    }

    /**
     * Per v0.5.2b: open-file bond computation with a 5 s smart-ionic timeout. Falls back to bonding
     * radii on timeout and surfaces the [smartIonicTimeoutMessage] snackbar.
     */
    fun openWithBondComputation(
        structure: CrystalStructure,
        bondConfiguration: com.krystals.crystal.analysis.bonding.BondConfiguration,
        epsilon: Double,
    ) {
        if (computing) return
        computing = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    // Per v0.5.2b: cap smart-ionic at 5 s; on timeout pass null so the editor falls
                    // back to bonding radii and flags the timeout.
                    val smartIonic = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                        BondValence.smartIonicRules(structure, bondConfiguration, epsilon)
                    }
                    CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, smartIonic)
                }
            }
            computing = false
            result.onSuccess { editResult ->
                if (CrystalEditor.SMART_IONIC_TIMEOUT in editResult.warnings) showMessage(smartIonicTimeoutMessage)
                viewModel.current?.let { viewModel.updateAnalysis(it, editResult) }
            }.onFailure { showMessage(it.message ?: "Operation failed") }
        }
    }


    // Per v0.4.0: resume the MP flow after the caution dialog — hasKey ? search : enter key.
    fun proceedToMp() {
        if (MaterialsProject.hasKey(activity)) mpSearchOpen = true else mpKeyDialogOpen = true
    }

    fun openUrl(url: String) {
        runCatching { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { showMessage("Unable to open browser") }
    }

    /** Per v0.5.3b: the actual tab insertion + bond computation, split out of [openParsed] so the
     *  large-cell warning can re-enter here after the user confirms. Declared before [openParsed]
     *  because Kotlin local functions have no forward references. */
    fun doOpenParsed(parsed: ParsedStructure, name: String, uri: Uri?) {
        // Add the tab immediately (so the empty structure shows), then compute rules if needed.
        viewModel.add(parsed, name, uri)
        val tab = viewModel.current ?: return
        if (tab.bondConfiguration.rules.isEmpty()) {
            // Per v0.5.2b: open-file path uses the 5 s smart-ionic timeout variant.
            openWithBondComputation(tab.structure, tab.bondConfiguration, tab.bondEpsilon)
        }
    }

    /** Per v0.5.0: add a parsed structure, synthesizing bond rules off-UI with the computing overlay.
     *  Defined before [loadUri] because local functions must be declared before use (no forward refs).
     *  Per v0.5.3b: cells expanding past [LARGE_CELL_WARN_THRESHOLD] atoms are gated behind a
     *  confirm dialog; the user can still open them in degraded mode. */
    fun openParsed(parsed: ParsedStructure, name: String, uri: Uri?) {
        val expandedEstimate = SymmetryExpander.expand(parsed.structure).size
        if (expandedEstimate > LARGE_CELL_WARN_THRESHOLD) {
            pendingLargeOpen = PendingLargeOpen(parsed, name, uri, expandedEstimate)
            return
        }
        doOpenParsed(parsed, name, uri)
    }

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
                    openParsed(parsed, result.name, result.uri)
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
                    val content = CifCodec.write(
                        tab.parsed,
                        tab.structure,
                        tab.bondConfiguration,
                        tab.renderConfiguration.toCifDisplayMetadata(),
                    )
                    withContext(Dispatchers.IO) { FileRepository.write(activity.contentResolver, uri, content) }
                    tab.uri = uri; tab.isNew = false; tab.dirty = false; tab.savedName = tab.name
                    tab.parsed = CifCodec.parseStructure(content, tab.parsed.blockIndex)
                }.onSuccess {
                    showMessage("Saved ${tab.name}")
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
                val content = CifCodec.write(
                    tab.parsed,
                    tab.structure,
                    tab.bondConfiguration,
                    tab.renderConfiguration.toCifDisplayMetadata(),
                )
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
                        onOnlineSource = { onlineSourceOpen = true },
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
                                PresetRepository.saveToPreset(
                                    activity,
                                    tab.parsed,
                                    tab.structure,
                                    tab.bondConfiguration,
                                    tab.renderConfiguration.toCifDisplayMetadata(),
                                    tab.name,
                                )
                                showMessage("Saved to presets")
                            }.onFailure { showMessage(it.message ?: "Save failed") }
                        },
                        onNew = viewModel::createNew,
                        onOnlineSource = { onlineSourceOpen = true },
                        onExport = ::requestExport,
                        onExit = ::requestExit,
                        onClose = { index -> if (viewModel.tabs[index].dirty) closeRequest = index else viewModel.close(index) },
                        themeMode = themeMode,
                        onTheme = ::applyTheme,
                        language = language,
                        onLanguage = { value -> language = value; preferences.edit().putString("language", value).apply(); activity.recreate() },
                        onMessage = ::showMessage,
                        onHelp = { helpOpen = true },
                        onAbout = { aboutOpen = true },
                        onSponsor = { sponsorOpen = true },
                        onRunBondComputation = ::runWithBondComputation,
                        onApplyAppearance = ::applyViewerAppearance,
                    )
                }
            }
        }

        // Dialogs live inside KrystalsTheme so they pick up the correct color scheme (dark/light).
        if (computing) {
            androidx.compose.material3.BasicAlertDialog(onDismissRequest = {}) {
                androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Text(localized("计算中...", "Computing..."))
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
                pendingOpen = null
                openParsed(parsed, pending.name, pending.uri)
            }) { Text(document.blocks[index].name) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingLargeOpen?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingLargeOpen = null },
            title = { Text(largeCellWarningMessage) },
            text = { Text(localized(
                "该晶胞展开后约 ${pending.expandedEstimate} 个原子，可能卡顿或崩溃。确认继续？",
                "This cell has ~${pending.expandedEstimate} expanded atoms; it may lag or crash. Continue?")) },
            confirmButton = { TextButton(onClick = {
                val p = pending; pendingLargeOpen = null
                doOpenParsed(p.parsed, p.name, p.uri)
            }) { Text(localized("继续", "Continue")) } },
            dismissButton = { TextButton(onClick = { pendingLargeOpen = null }) { Text(stringResource(R.string.cancel)) } },
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
    if (sponsorOpen) SponsorDialog(onDismiss = { sponsorOpen = false }, launchCount = sponsorLaunchCount, onSponsor = { openUrl("https://ifdian.net/a/krystals/plan"); sponsorOpen = false }, onAlreadySponsored = { sponsorOpen = false; activationOpen = true })
    if (aboutOpen) AboutScreen(onBack = { aboutOpen = false })
    if (presetOpen) PresetLibraryDialog(
        context = activity,
        viewModel = viewModel,
        preferences = preferences,
        onDismiss = { presetOpen = false },
        onMessage = ::showMessage,
        onOpenParsed = { parsed, name -> presetOpen = false; openParsed(parsed, name, null) },
    )
    if (mpKeyDialogOpen) MpApiKeyDialog(
        context = activity,
        onDismiss = { mpKeyDialogOpen = false },
        onOpenUrl = ::openUrl,
        onConfirmed = { mpKeyDialogOpen = false; mpSearchOpen = true },
        onMessage = ::showMessage,
    )
    if (activationOpen) ActivationDialog(
        context = activity,
        onDismiss = { activationOpen = false },
        onConfirmed = { activationOpen = false },
        onMessage = ::showMessage,
    )
    if (mpPremiumOpen) MpPremiumDialog(
        onDismiss = { mpPremiumOpen = false },
        onSponsor = { mpPremiumOpen = false; sponsorOpen = true },
    )
    if (mpCautionOpen) MpCautionDialog(
        preferences = preferences,
        onDismiss = { mpCautionOpen = false },
        onContinue = { mpCautionOpen = false; proceedToMp() },
    )
    if (mpSearchOpen) MpSearchScreen(
        context = activity,
        viewModel = viewModel,
        onBack = { mpSearchOpen = false },
        onChangeKey = { mpSearchOpen = false; mpKeyDialogOpen = true },
        onMessage = ::showMessage,
        onOpenParsed = { parsed, name -> mpSearchOpen = false; openParsed(parsed, name, null) },
    )
    if (onlineSourceOpen) OnlineSourcePickerDialog(
        onDismiss = { onlineSourceOpen = false },
        onPickCod = { onlineSourceOpen = false; codSearchOpen = true },
        onPickMp = {
            onlineSourceOpen = false
            // Per v0.4.0: Materials Project import is sponsor-gated. Without a valid activation
            // code, show the premium-content dialog instead of the API-key flow.
            when {
                !ActivationManager.isActivated(activity) -> mpPremiumOpen = true
                !preferences.getBoolean("mp_caution_dismissed", false) -> mpCautionOpen = true
                MaterialsProject.hasKey(activity) -> mpSearchOpen = true
                else -> mpKeyDialogOpen = true
            }
        },
    )
    if (codSearchOpen) CodSearchScreen(
        context = activity,
        viewModel = viewModel,
        onBack = { codSearchOpen = false },
        onMessage = ::showMessage,
        onOpenParsed = { parsed, name -> codSearchOpen = false; openParsed(parsed, name, null) },
    )
    }
}

@Composable
private fun HomeScreen(
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onNew: () -> Unit,
    onOnlineSource: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Per v0.4.3: in landscape the four import buttons span the full (very wide) screen and
        // look stretched; cap their width to half the screen there. Portrait keeps fillMaxWidth.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val buttonWidth = if (landscape) Modifier.fillMaxWidth(0.5f) else Modifier.fillMaxWidth()
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AssetImage("main.png", Modifier.size(230.dp), ContentScale.Fit)
                Text("Krystals", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(28.dp))
                // Per v0.3.3: order matches the app menu — import local, preset library, online, new.
                Button(onClick = onOpen, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_local)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenPreset, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.Inventory2, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_preset_library)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOnlineSource, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.Science, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_online)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNew, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_file)) }
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
    onOnlineSource: () -> Unit,
    onExport: (Bitmap) -> Unit,
    onExit: () -> Unit,
    onClose: (Int) -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    onMessage: (String) -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onSponsor: () -> Unit,
    onRunBondComputation: ((suspend () -> EditResult?) -> Unit),
    onApplyAppearance: (ViewerAppearance) -> Unit,
) {
    val tab = viewModel.current ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var toolOpen by remember(tab.id) { mutableStateOf(false) }
    var alignOpen by remember { mutableStateOf(false) }
    var measureOpen by remember { mutableStateOf(false) }
    var displayOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    // Per v0.5.2a: while non-null, the viewer renders with this edited appearance (press-and-hold
    // "Preview" in the Appearance dialog sets it and hides the dialog); null = use tab.appearance.
    var previewAppearance by remember { mutableStateOf<ViewerAppearance?>(null) }
    var floatingX by remember(tab.id) { mutableFloatStateOf(0f) }
    var floatingY by remember(tab.id) { mutableFloatStateOf(0f) }
    // Per v0.4.2: only the main ball (the 54dp center FAB) must stay on-screen — the radial tool
    // fan is allowed to overhang the edges when expanded, so the ball can roam across the whole
    // screen instead of being penned in by the 180dp container frame.
    var ballParentSize by remember(tab.id) { mutableStateOf(IntSize.Zero) }
    var ballOuterSize by remember(tab.id) { mutableStateOf(IntSize.Zero) }
    val mainBallPx = with(LocalDensity.current) { 54.dp.toPx() }
    // Re-clamp on rotation / tab swap / first layout so an out-of-range offset snaps back in bounds.
    LaunchedEffect(ballParentSize, ballOuterSize, mainBallPx) {
        if (ballParentSize != IntSize.Zero && ballOuterSize != IntSize.Zero) {
            // Container is align(BottomEnd); the main ball is centered in it. The allowed offset
            // range keeps only the main ball's edges inside the parent, letting the container itself
            // (and the expanded tool fan) overhang the screen edges.
            val minX = (ballOuterSize.width + mainBallPx) / 2f - ballParentSize.width
            val maxX = (ballOuterSize.width - mainBallPx) / 2f
            val minY = (ballOuterSize.height + mainBallPx) / 2f - ballParentSize.height
            val maxY = (ballOuterSize.height - mainBallPx) / 2f
            floatingX = floatingX.coerceIn(minX, maxX)
            floatingY = floatingY.coerceIn(minY, maxY)
        }
    }
    var legendExpanded by remember(tab.id) { mutableStateOf(false) }
    val lengthChoice = localized("长度", "Length")
    val angleChoice = localized("角度", "Angle")
    val dihedralChoice = localized("二面角", "Dihedral")
    val offChoice = localized("关闭", "Off")
    val controller = rememberViewerController()
    // Per v0.5.3b: build the scene off the UI thread with a timeout. Previously this ran synchronously
    // on the Main thread inside `remember`, so a large cell (materialising ~77k shell atoms) froze
    // the UI and OOM'd with no way to cancel. Now a key change cancels the prior build (the stale
    // result is discarded) and shows a spinner while the new one computes.
    var sceneResult by remember(tab.structure, tab.expansion) { mutableStateOf<Result<BondNetwork>?>(null) }
    LaunchedEffect(tab.structure, tab.expansion) {
        sceneResult = null
        sceneResult = runCatching {
            withTimeoutOrNull(BUILD_SCENE_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    BondDetector.buildNetwork(tab.structure, tab.bondConfiguration, tab.expansion)
                }
            } ?: throw IllegalStateException("Scene build timed out after ${BUILD_SCENE_TIMEOUT_MS / 1000}s")
        }
    }
    // Per v0.5.0: per-site bond-valence sums for the atom-info window (s = X.XX). Recomputed when
    // the structure changes; cheap relative to scene build.
    val bondValenceBySite = remember(tab.structure, tab.bondConfiguration, tab.bondEpsilon) {
        BondValence.bondValenceSums(tab.structure, tab.bondConfiguration, tab.bondEpsilon)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Krystals", fontWeight = FontWeight.Bold, maxLines = 1) },
            navigationIcon = { Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }; DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.import_local)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { menuOpen = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.open_preset_library)) }, leadingIcon = { Icon(Icons.Default.Inventory2, null) }, onClick = { menuOpen = false; onOpenPreset() })
                DropdownMenuItem(text = { Text(stringResource(R.string.import_online)) }, leadingIcon = { Icon(Icons.Default.Science, null) }, onClick = { menuOpen = false; onOnlineSource() })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menuOpen = false; onNew() })
                DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuOpen = false; onSave(tab) })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_to_presets)) }, leadingIcon = { Icon(Icons.Default.Bookmark, null) }, onClick = { menuOpen = false; onSaveToPreset() })
                DropdownMenuItem(text = { Text(stringResource(R.string.export_image)) }, leadingIcon = { Icon(Icons.Default.Photo, null) }, onClick = {
                    menuOpen = false
                    sceneResult?.getOrNull()?.let { snapshot ->
                        onExport(CrystalImageExporter.render(snapshot, tab.appearance, tab.renderConfiguration, controller, tab.visibility, tab.selectedAtomIds, tab.measurementMode, tab.inspectedAtomId, tab.lockedMeasurements, tab.lockedInspectedAtomIds, bondValenceBySite))
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
            val current = sceneResult
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                current.isSuccess -> {
                    val snapshot = current.getOrThrow()
                    CrystalViewport(
                        snapshot = snapshot,
                        appearance = previewAppearance ?: tab.appearance,
                        renderConfiguration = tab.renderConfiguration,
                        controller = controller,
                        visibility = tab.visibility,
                        selectedAtomIds = tab.selectedAtomIds,
                        measurementMode = tab.measurementMode,
                        lockedMeasurements = tab.lockedMeasurements,
                        onMeasurementLockToggle = { measurement, isLocked ->
                            // Tapping an unlocked (active) measurement box locks it into the list and
                            // clears the active selection; tapping a locked box removes just that one.
                            if (isLocked && measurement != null) {
                                tab.lockedMeasurements = tab.lockedMeasurements.filterNot { it == measurement }
                            } else if (!isLocked) {
                                if (tab.measurementMode != MeasurementMode.NONE && tab.selectedAtomIds.isNotEmpty()) {
                                    tab.lockedMeasurements = tab.lockedMeasurements + LockedMeasurement(tab.selectedAtomIds, tab.measurementMode)
                                    tab.selectedAtomIds = emptyList()
                                }
                            }
                        },
                        inspectedAtomId = tab.inspectedAtomId,
                        lockedInspectedAtomIds = tab.lockedInspectedAtomIds,
                        bondValenceBySite = bondValenceBySite,
                        onInspectAtom = { atom ->
                            // Per v0.2.4: double-tap opens an unlocked info window for the atom. Any
                            // already-locked windows are preserved; the active window is replaceable.
                            tab.inspectedAtomId = atom.id
                        },
                        onInspectionLockToggle = { atomId, isLocked ->
                            // Tapping an unlocked (active) info box locks it into the persistent list and
                            // clears the active window; tapping a locked box removes just that one.
                            if (isLocked) {
                                tab.lockedInspectedAtomIds = tab.lockedInspectedAtomIds - atomId
                            } else {
                                tab.lockedInspectedAtomIds = tab.lockedInspectedAtomIds + atomId
                                tab.inspectedAtomId = null
                            }
                        },
                        onViewMoved = {
                            if (tab.measurementMode != MeasurementMode.NONE) tab.selectedAtomIds = emptyList()
                            // Moving the view dismisses the unlocked info window; locked ones persist.
                            tab.inspectedAtomId = null
                        },
                        onAtomTap = { atom ->
                            when (tab.atomEditMode) {
                                AtomEditMode.DELETE_NEXT -> {
                                    tab.atomEditMode = AtomEditMode.NONE
                                    val deleted = runCatching {
                                        CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.DeleteAtom(atom.siteId))
                                    }.getOrNull()
                                    if (deleted != null) onRunBondComputation {
                                        CrystalEditor.ensureAutoBondRules(deleted.structure, deleted.bondConfiguration)
                                    }
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
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(current.exceptionOrNull()?.message ?: "Unable to build scene", color = MaterialTheme.colorScheme.error)
                }
            }

            // Legend groups by (element, resolved color), sourced from the edited structure (not the
            // rendered atoms) so it doesn't churn as visibility changes. Sites of the same element
            // sharing a color collapse into one element row; per-site color overrides split into rows
            // labeled by site.label (e.g. C1, C2).
            val legendEntries = remember(tab.structure, tab.visibility) {
                val visibleSites = tab.structure.sites.filterNot { it.id in tab.visibility.hiddenSites }
                visibleSites
                    .groupBy { site ->
                        site.species.symbol to RenderPalette.resolveSiteArgb(
                            site.id,
                            site.species.symbol,
                            tab.renderConfiguration,
                        )
                    }
                    .toSortedMap(compareBy({ it.first }, { it.second }))
                    .flatMap { (key, sites) ->
                        val (element, argb) = key
                        if (sites.size == 1 && sites.first().species.symbol == element) listOf(LegendEntry(element, argb))
                        else sites.sortedBy { it.label }.map { LegendEntry(it.label, argb) }
                    }
            }
            ElementLegend(
                entries = legendEntries,
                expanded = legendExpanded,
                onToggle = { legendExpanded = !legendExpanded },
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            )

            val floatingAlpha by animateFloatAsState(targetValue = if (toolOpen) 1f else 0.45f, label = "floatingAlpha")
            // Per v0.2.2: floating-ball palette uses the project's two purples (deep 0xFF7542A5 /
            // light 0xFFCFA7F5). Dark mode = deep bg + light icon; light mode = light bg + deep icon.
            // The active (measure/lock) tool buttons invert this pairing.
            val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            val baseContainer = Color(if (dark) 0xFF7542A5 else 0xFFCFA7F5)
            val baseContent = Color(if (dark) 0xFFCFA7F5 else 0xFF7542A5)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .onGloballyPositioned { coords ->
                        ballOuterSize = coords.size
                        ballParentSize = coords.parentCoordinates?.size ?: IntSize.Zero
                    }
                    .padding(18.dp)
                    .size(180.dp)
                    .offset { IntOffset(floatingX.roundToInt(), floatingY.roundToInt()) }
                    .alpha(floatingAlpha)
                    .pointerInput(tab.id) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            // Only the main ball (centered 54dp FAB) is kept on-screen; the
                            // expanded radial tool fan may overhang the screen edges.
                            val minX = (ballOuterSize.width + mainBallPx) / 2f - ballParentSize.width
                            val maxX = (ballOuterSize.width - mainBallPx) / 2f
                            val minY = (ballOuterSize.height + mainBallPx) / 2f - ballParentSize.height
                            val maxY = (ballOuterSize.height - mainBallPx) / 2f
                            floatingX = (floatingX + amount.x).coerceIn(minX, maxX)
                            floatingY = (floatingY + amount.y).coerceIn(minY, maxY)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (toolOpen) {
                    val radius = 62.dp
                    // Per v0.2: the Measure button shows an active (light bg / dark icon) state while a
                    // measurement mode is active; the Lock button does the same while the view is locked.
                    data class Tool(val icon: androidx.compose.ui.graphics.vector.ImageVector, val active: Boolean, val action: () -> Unit)
                    val tools = listOf(
                        Tool(Icons.Default.FitScreen, active = false) { alignOpen = true },
                        Tool(Icons.Default.Straighten, active = tab.measurementMode != MeasurementMode.NONE) { measureOpen = true },
                        Tool(Icons.Default.Visibility, active = false) { displayOpen = true },
                        Tool(Icons.Default.Info, active = false) { infoOpen = true },
                        Tool(Icons.Default.Edit, active = false) { tab.editorOpen = true },
                        Tool(if (controller.locked) Icons.Default.LockOpen else Icons.Default.Lock, active = controller.locked) { controller.locked = !controller.locked },
                    )
                    tools.forEachIndexed { index, (icon, active, action) ->
                        val angle = Math.PI / 180 * (index * 60 - 90)
                        FloatingActionButton(
                            onClick = action,
                            shape = CircleShape,
                            containerColor = if (active) baseContent else baseContainer,
                            contentColor = if (active) baseContainer else baseContent,
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
                    containerColor = baseContainer,
                    contentColor = baseContent,
                    modifier = Modifier.size(54.dp),
                ) { AssetImage("icon_trans.png", Modifier.size(43.dp), ContentScale.Fit) }
            }
            if (tab.editorOpen) EditorPanel(tab, onDismiss = { tab.editorOpen = false }, onStructure = { viewModel.updateAnalysis(tab, it) }, onMessage = onMessage, onRunBondComputation = onRunBondComputation)
        }
    }

    if (alignOpen) AlignDialog(
        onDismiss = { alignOpen = false },
        onChoice = { choice ->
            when (choice) {
                "X", "Y", "Z" -> controller.align(choice.first())
                "a", "b", "c" -> controller.alignCellAxis(choice.first(), tab.structure.lattice)
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
            // Per v0.2.3: switching mode keeps any locked measurement; only the active selection resets.
            tab.measurementMode = when {
                choice == lengthChoice -> MeasurementMode.LENGTH
                choice == angleChoice -> MeasurementMode.ANGLE
                choice == dihedralChoice -> MeasurementMode.DIHEDRAL
                else -> MeasurementMode.NONE
            }
            tab.selectedAtomIds = emptyList(); measureOpen = false
        },
    )
    if (displayOpen) DisplayPanel(tab, viewModel, onDismiss = { displayOpen = false })
    if (infoOpen) InfoDialog(tab, onDismiss = { infoOpen = false })
    // Per v0.5.3a: the dialog stays mounted (it self-hides via alpha) so the preview press-and-hold
    // gesture survives; the viewer uses previewAppearance while non-null.
    if (appearanceOpen) AppearanceDialog(
        tab,
        onDismiss = { appearanceOpen = false },
        onApplied = { onApplyAppearance(it) },
        onPreviewStart = { previewAppearance = it },
        onPreviewEnd = { previewAppearance = null },
    )
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

private data class LegendEntry(val label: String, val argb: Long)

@Composable
private fun ElementLegend(entries: List<LegendEntry>, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.alpha(0.84f), shape = RoundedCornerShape(14.dp), tonalElevation = 5.dp) {
        Column(Modifier.width(if (expanded) 130.dp else 140.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, modifier = Modifier.size(18.dp))
                Text(localized("图例", "Legend"), modifier = Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(18.dp))
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    entries.forEach { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).background(Color(entry.argb), CircleShape))
                            Text(entry.label, modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayPanel(tab: DocumentTab, viewModel: KrystalsViewModel, onDismiss: () -> Unit) {
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
    val allBondsVisible = tab.visibility.showBonds && tab.visibility.hiddenBondPairs.none { key -> rules.any { it.key == key } }
    val allPolyhedraEnabled = siteIds.isNotEmpty() && siteIds.all { it in tab.visibility.polyhedronSites }
    var selected by remember { mutableStateOf(DisplayTab.ATOMS) }
    // Per v0.3.44: per-group collapse state for the ATOMS/POLYHEDRA/BONDS grouped lists. Keyed by
    // element (ATOMS/POLYHEDRA) or element-pair (BONDS). A group is expanded when its key is absent
    // (default expanded); toggling inserts/removes the key.
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    // Per v0.2.3: resizable panel — drag the handle to change how much of the screen the panel
    // occupies. Portrait: bottom sheet height fraction; landscape: right sheet width fraction.
    // Per v0.3.43: default area raised to 0.4.
    var panelRatio by remember { mutableStateOf(0.40f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
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
            // content sits beside it in a Row (a Column with a fillMaxHeight first child would leave
            // no height for the tabs/content — the cause of the blank landscape panel). The content
            // itself is wrapped in a single Column so the lambda can be reused for both layouts.
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
                            DisplayTab.ATOMS to localized("原子", "Atoms"),
                            DisplayTab.BONDS to localized("化学键", "Bonds"),
                            DisplayTab.POLYHEDRA to localized("多面体", "Polyhedra"),
                        ).forEach { (kind, label) -> FilterChip(selected == kind, onClick = { selected = kind }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                        Spacer(Modifier.weight(1f)); IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        when (selected) {
                            DisplayTab.ATOMS -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allSitesVisible, onCheckedChange = { checked ->
                                        tab.visibility = tab.visibility.copy(hiddenSites = if (checked) emptySet() else siteIds)
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { tab.visibility = tab.visibility.copy(hiddenSites = siteIds - tab.visibility.hiddenSites) }) { Text(localized("反选", "Invert")) }
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
                                // Per v0.2.2: only list rules whose two sites still exist (others don't affect rendering).
                                val visibleRules = rules.filter { rule ->
                                    BondRuleMatching.hasMatchingBond(
                                        rule,
                                        tab.structure,
                                        tab.bondConfiguration,
                                        bondGrid.second,
                                        bondGrid.first,
                                    )
                                }.distinctBy { it.key } // Per v0.5.4b: see EditorPanels — duplicate
                                // site-pair keys crash the LazyColumn with "Key was already used".
                                val allExtend = visibleRules.isNotEmpty() && visibleRules.all { it.extendAcrossCell }
                                // Per v0.3.43: display select-all/invert and extend select-all/invert on one
                                // row, mirroring the ATOMS/POLYHEDRA style (Checkbox + 全选 + 反选), placed
                                // side by side.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allBondsVisible, onCheckedChange = { checked ->
                                        tab.visibility = tab.visibility.copy(
                                            showBonds = checked,
                                            hiddenBondPairs = if (checked) emptySet() else visibleRules.map { it.key }.toSet(),
                                        )
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(onClick = {
                                        val allKeys = visibleRules.map { it.key }.toSet()
                                        tab.visibility = tab.visibility.copy(showBonds = true, hiddenBondPairs = allKeys - tab.visibility.hiddenBondPairs)
                                    }) { Text(localized("反选", "Invert")) }
                                    if (visibleRules.isNotEmpty()) {
                                        Spacer(Modifier.width(12.dp))
                                        Checkbox(allExtend, onCheckedChange = { checked ->
                                            var working = tab.bondConfiguration
                                            visibleRules.forEach { rule ->
                                                if (rule.extendAcrossCell != checked) {
                                                    working = CrystalEditor.apply(
                                                        tab.structure,
                                                        working,
                                                        EditCommand.SetBondRule(rule.copy(extendAcrossCell = checked)),
                                                    ).bondConfiguration
                                                }
                                            }
                                            viewModel.updateAnalysis(tab, EditResult(tab.structure, working))
                                        })
                                        Text(stringResource(R.string.extend_across_cell), style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(4.dp))
                                        TextButton(onClick = {
                                            var working = tab.bondConfiguration
                                            visibleRules.forEach { rule ->
                                                working = CrystalEditor.apply(
                                                    tab.structure,
                                                    working,
                                                    EditCommand.SetBondRule(rule.copy(extendAcrossCell = !rule.extendAcrossCell)),
                                                ).bondConfiguration
                                            }
                                            viewModel.updateAnalysis(tab, EditResult(tab.structure, working))
                                        }) { Text(localized("反选", "Invert")) }
                                    }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                if (visibleRules.isEmpty()) {
                                    Text(localized("无化学键规则", "No bond rules"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                } else {
                                    // Per v0.3.44: group bond rules by element pair (e.g. C-O, Cs-Cl). Each
                                    // group is a collapsible header (group visibility checkbox) + per-rule rows.
                                    val elementOf = remember(sites) { sites.associate { it.id to it.species.symbol } }
                                    val groupedRules = remember(visibleRules, elementOf) {
                                        visibleRules.groupBy { rule ->
                                            val ea = elementOf[rule.siteA] ?: "?"
                                            val eb = elementOf[rule.siteB] ?: "?"
                                            if (ea <= eb) "$ea—$eb" else "$eb—$ea"
                                        }.toSortedMap()
                                    }
                                    groupedRules.forEach { (pairLabel, groupRules) ->
                                        val expanded = collapsedGroups["B:$pairLabel"] != true
                                        val allGroupVisible = groupRules.all { it.key !in tab.visibility.hiddenBondPairs }
                                        CollapsibleGroupHeader(
                                            title = "$pairLabel (${groupRules.size})",
                                            expanded = expanded,
                                            onToggle = { collapsedGroups["B:$pairLabel"] = expanded },
                                            checked = allGroupVisible,
                                            onCheckChange = { checked ->
                                                tab.visibility = tab.visibility.copy(
                                                    showBonds = true,
                                                    hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - groupRules.map { it.key }.toSet()
                                                    else tab.visibility.hiddenBondPairs + groupRules.map { it.key }.toSet(),
                                                )
                                            },
                                        ) { Spacer(Modifier.width(22.dp)) }
                                        if (expanded) {
                                            groupRules.forEach { rule ->
                                                val labelA = sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                                                val labelB = sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                                                val label = "$labelA—$labelB"
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 28.dp)) {
                                                    val visible = tab.visibility.showBonds && rule.key !in tab.visibility.hiddenBondPairs
                                                    Checkbox(visible, onCheckedChange = { checked ->
                                                        tab.visibility = tab.visibility.copy(
                                                            showBonds = true,
                                                            hiddenBondPairs = if (checked) tab.visibility.hiddenBondPairs - rule.key else tab.visibility.hiddenBondPairs + rule.key,
                                                        )
                                                    })
                                                    Text(label, modifier = Modifier.weight(1f))
                                                    Text(stringResource(R.string.extend_across_cell), style = MaterialTheme.typography.bodySmall)
                                                    Spacer(Modifier.width(4.dp))
                                                    Checkbox(rule.extendAcrossCell, onCheckedChange = { extend ->
                                                        val updated = rule.copy(extendAcrossCell = extend)
                                                        viewModel.updateAnalysis(
                                                            tab,
                                                            CrystalEditor.apply(
                                                                tab.structure,
                                                                tab.bondConfiguration,
                                                                EditCommand.SetBondRule(updated),
                                                            ),
                                                        )
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            DisplayTab.POLYHEDRA -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(allPolyhedraEnabled, onCheckedChange = { checked ->
                                        tab.visibility = tab.visibility.copy(polyhedronSites = if (checked) siteIds else emptySet())
                                    })
                                    Text(stringResource(R.string.select_all))
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { tab.visibility = tab.visibility.copy(polyhedronSites = siteIds - tab.visibility.polyhedronSites) }) { Text(localized("反选", "Invert")) }
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
                                                Checkbox(enabled, onCheckedChange = { checked -> tab.visibility = tab.visibility.copy(polyhedronSites = if (checked) tab.visibility.polyhedronSites + site.id else tab.visibility.polyhedronSites - site.id) })
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

private enum class DisplayTab { ATOMS, BONDS, POLYHEDRA }

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
private fun InfoDialog(tab: DocumentTab, onDismiss: () -> Unit) {
    val info = remember(tab.structure) { StructureAnalyzer.info(tab.structure) }
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
    preferences: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenParsed: (ParsedStructure, String) -> Unit,
) {
    var presets by remember { mutableStateOf(PresetRepository.listPresets(context)) }
    var pendingDelete by remember { mutableStateOf<PresetEntry?>(null) }
    fun refresh() { presets = PresetRepository.listPresets(context) }
    // Per v0.2.3: collapsible category sections, default all collapsed (user group expanded).
    // Expansion state persists across opens via SharedPreferences.
    val EXPANDED_KEY = "preset_expanded_categories"
    var expanded by remember {
        mutableStateOf(
            preferences.getString(EXPANDED_KEY, "__user__")!!.split(",").filter { it.isNotBlank() }.toMutableSet()
        )
    }
    fun toggle(cat: String) {
        expanded = expanded.toMutableSet().apply { if (!add(cat)) remove(cat) }.also {
            preferences.edit().putString(EXPANDED_KEY, it.joinToString(",")).apply()
        }
    }
    // Group by category; user presets (__user__) first, then bundled categories in directory order.
    val grouped = presets.groupBy { it.category ?: "__user__" }
    val userGroup = grouped["__user__"].orEmpty()
    val bundledGroups = grouped.filterKeys { it != "__user__" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_library)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                if (userGroup.isNotEmpty()) {
                    item(key = "header___user__") {
                        Row(Modifier.fillMaxWidth().clickable { toggle("__user__") }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if ("__user__" in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp))
                            Text(localized("我的预设", "My presets"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    if ("__user__" in expanded) items(userGroup, key = { "u_" + it.name }) { entry -> PresetRow(entry, context, viewModel, onDismiss, onMessage, { pendingDelete = entry }, onOpenParsed) }
                }
                bundledGroups.forEach { (category, entries) ->
                    item(key = "header_$category") {
                        Row(Modifier.fillMaxWidth().clickable { toggle(category) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (category in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp))
                            Text(category, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    if (category in expanded) items(entries, key = { category + "_" + it.name }) { entry -> PresetRow(entry, context, viewModel, onDismiss, onMessage, { pendingDelete = entry }, onOpenParsed) }
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
private fun PresetRow(
    entry: PresetEntry,
    context: Context,
    viewModel: KrystalsViewModel,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onDelete: () -> Unit,
    onOpenParsed: (ParsedStructure, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable {
            runCatching { PresetRepository.openPreset(context, entry) }
                .onSuccess { parsed ->
                    onDismiss()
                    onOpenParsed(parsed, entry.name)
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
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null) }
        }
    }
    HorizontalDivider()
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
private fun MpSearchScreen(
    context: Context,
    viewModel: KrystalsViewModel,
    onBack: () -> Unit,
    onChangeKey: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenParsed: (ParsedStructure, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var fuzzySearch by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MpSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Full-screen surface so the screen covers the whole viewport (status bar area
    // included via the Scaffold insets already applied above) instead of a padded column.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
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
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
            }
            Text(
                localized(
                    "精确搜索匹配约化化学式（如 SiO2）。模糊搜索用 * 通配，如 *O2、Si*、*SiO*。",
                    "Exact matches the reduced formula (e.g. SiO2). Fuzzy uses * wildcards, e.g. *O2, Si*, *SiO*.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            // Per v0.3.3: snapshot `results` into a local val so the LazyColumn's item lambda never
            // re-reads a null `results` during a Compose snapshot-apply (which threw NPE on the 2nd
            // search when `results = null` invalidated the list mid-recomposition).
            val current = results
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索中…", "Searching…")) }
                current == null -> {}
                current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(current) { item ->
                        Card(
                            enabled = downloadingId == null,
                            onClick = {
                                if (downloadingId != null) return@Card
                                downloadingId = item.materialId
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val target = File(context.cacheDir, "${item.materialId}.cif")
                                    val result = MaterialsProject.downloadCif(context, item.materialId, target)
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
                                if (downloadingId == item.materialId) {
                                    Text(localized("下载中…", "Downloading…"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodSearchScreen(
    context: Context,
    viewModel: KrystalsViewModel,
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
    var maxElements by remember { mutableStateOf(8) }
    var results by remember { mutableStateOf<List<CodSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
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
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
            }
            // Per v0.3.3: element mode exposes a "max element count" cap (COD nel2). Slider + text
            // field mirror ExpansionCluster's integer-input pattern (range 1..8, default 8).
            if (mode == CrystallographyOpenDatabase.SearchMode.ELEMENT) {
                var elementsText by remember(maxElements) { mutableStateOf(maxElements.toString()) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized("最大元素数量", "Max elements"), fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
                    OutlinedTextField(
                        elementsText,
                        { input -> elementsText = input; input.toIntOrNull()?.let { n -> if (n in 1..8) maxElements = n } },
                        singleLine = true,
                        modifier = Modifier.width(64.dp),
                    )
                    Slider(
                        value = maxElements.toFloat(),
                        onValueChange = { v -> val n = v.toInt().coerceIn(1, 8); maxElements = n; elementsText = n.toString() },
                        valueRange = 1f..8f,
                        steps = 6,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
            Text(
                localized(
                    "化学式如 SiO2（自动转为 Hill 顺序 O2 Si）。元素以空格分开，如 Si O。文本如 quartz，匹配矿物名/化学名/标题。",
                    "Formula e.g. SiO2 (auto-converted to Hill order: O2 Si). Element sperating by space, e.g. Si O. Text e.g. quartz, matches mineral/chemical names and titles.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            // Per v0.3.3: snapshot `results` into a local val so the LazyColumn's item lambda never
            // re-reads a null `results` during a Compose snapshot-apply (which threw NPE on the 2nd
            // search when `results = null` invalidated the list mid-recomposition).
            val current = results
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索中…", "Searching…")) }
                current == null -> {}
                current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(current) { item ->
                        Card(
                            enabled = downloadingId == null,
                            onClick = {
                                if (downloadingId != null) return@Card
                                downloadingId = item.fileId
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val target = File(context.cacheDir, "cod-${item.fileId}.cif")
                                    val result = CrystallographyOpenDatabase.downloadCif(item.fileId, target)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        downloadingId = null
                                        result.onSuccess { parsed ->
                                            onBack()
                                            onOpenParsed(parsed, "cod-${item.fileId}.cif")
                                        }.onFailure { onMessage(it.message ?: "Download failed") }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.fileId, fontWeight = FontWeight.Bold)
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
    }
}

@Composable
private fun OnlineSourcePickerDialog(
    onDismiss: () -> Unit,
    onPickCod: () -> Unit,
    onPickMp: () -> Unit,
) {
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
private fun SponsorDialog(onDismiss: () -> Unit, launchCount: Int = 0, onSponsor: () -> Unit = {}, onAlreadySponsored: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sponsor_title)) },
        text = {
            Column {
                Text(
                    if (launchCount > 0) localized("Krystals 已经为您启动了 $launchCount 次啦！如果想要支持开发，请多多赞助作者 kelesss 哦！", "Krystals has been launched $launchCount times! If you'd like to support development, please sponsor kelesss!")
                    else localized("支付一点大米让kelesss猫猫努力工作的说……ο(=•ω＜=)ρ⌒☆", "Toss a little money to keep kelesss working hard…ο(=•ω＜=)ρ⌒☆")
                )
                Spacer(Modifier.height(12.dp))
                Text(localized("· 赞助后可永久关闭赞助提醒，并可以接入materials project检索。未赞助不影响绝大部分功能的使用。", "· Sponsoring permanently dismisses this prompt and unlocks Materials Project search. Not sponsoring does not affect most features."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSponsor) { Text(localized("我要赞助！", "Sponsor!")) }
                TextButton(onClick = onAlreadySponsored) { Text(localized("我已赞助", "I've sponsored!")) }
                TextButton(onClick = onDismiss) { Text(localized("狠心拒绝", "Maybe later")) }
            }
        },
    )
}

@Composable
private fun ActivationDialog(
    context: Context,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var code by remember { mutableStateOf(ActivationManager.getActivationCode(context) ?: "") }
    // Pre-resolve the bilingual toast strings in the @Composable body; `localized` is itself a
    // @Composable function, so it cannot be called inside the non-composable onClick lambda.
    val activationSuccessText = localized("激活成功！感谢♪(^∇^*)", "Activated! Thanks ♪(^∇^*)")
    val activationInvalidText = localized("激活码无效", "Invalid activation code")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("输入激活码", "Enter activation code")) },
        text = {
            Column {
                Text(localized("赞助后请输入您收到的 16 位激活码。", "Enter the 16-char activation code you received after sponsoring."), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(code, { code = it.uppercase() }, label = { Text(localized("激活码", "Activation code")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank(),
                onClick = {
                    if (ActivationManager.activate(context, code)) {
                        onMessage(activationSuccessText)
                        onConfirmed()
                    } else {
                        onMessage(activationInvalidText)
                    }
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MpPremiumDialog(onDismiss: () -> Unit, onSponsor: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("高级内容", "Premium content")) },
        text = {
            Text(localized("Materials Project中导入晶体需要获取apikey，属于高级内容，并且尚未完全开发，使用COD数据库完全可以解决大部分问题。\n · 如果需要使用，请赞助一点点以支持开发！", "Importing crystals from Materials Project requires an API key and is a premium feature, and has not been developed compeletely yet — the COD handles most needs.\n · To use it, please sponsor a little to support development!"))
        },
        confirmButton = { TextButton(onClick = onSponsor) { Text(localized("我要赞助！", "Sponsor!")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized("再考虑一下…", "Maybe later…")) } },
    )
}

@Composable
private fun MpCautionDialog(preferences: SharedPreferences, onDismiss: () -> Unit, onContinue: () -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("注意", "Note")) },
        text = {
            Column {
                Text(
                    localized(
                        "由于Materials Project官方接口存在两种API，其中新API需要apikey才能使用，旧API可以直接接入；但旧API只能返回对称性为P1的非对称晶胞。因此Krystals的Materials Project接口需要新API使用。\n\n· 由于新API不时有bug，所以有时会回退成旧API。\n\n· 如果旧API被mp官方废除，将导致部分晶体无法下载，请及时更新软件。\n\n· 若查找到旧API数据库截止结果，将自动转入新API，存在丢失正当晶胞的问题",
                        "Materials Project exposes two APIs: the new one (requires an API key) and a legacy one (direct access) that only returns P1 asymmetric cells. Krystals therefore requires the new API.\n\n· The new API is occasionally buggy and may fall back to the legacy API.\n\n· If the legacy API is retired by MP, some crystals will no longer be downloadable — please update the app.\n\n· If the searched results do NOT contain in the legacy API's data, will automatically transmit to the new-gen API. Some symmetry information will be discarded."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text(localized("不再显示", "Don't show again"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (dontShow) preferences.edit().putBoolean("mp_caution_dismissed", true).apply()
                onContinue()
            }) { Text(localized("我知道了", "Comfirm")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    BackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.about)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            AssetImage("icon_foreground.png", Modifier.size(96.dp), ContentScale.Fit)
            Spacer(Modifier.height(16.dp))
            Text("Krystals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("${stringResource(R.string.version)} ${com.krystals.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            Text(localized("Krystals 是由 凯楽斯kelesss 与 AI辅助开发的一款 Android 平台轻量级晶体结构查看和编辑工具。", "Krystals is a lightweight Android CIF crystal structure viewer and editor, developed by kelesss with AI assistance."), style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            Text(localized("关于作者", "About the author"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp))
            // Per v0.2.3: links in one horizontal row, separated by " | " (dropped Bilibili live + Zhihu).
            val links = listOf(
                localized("个人网站", "Website") to "https://www.kelesss.art",
                "GitHub" to "https://github.com/SUPERkelesss",
                "Bilibili" to "https://space.bilibili.com/334614292",
                localized("Q群", "QQ group") to "https://qm.qq.com/q/YXattqg3Kg",
            )
            val context = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                links.forEachIndexed { index, (label, url) ->
                    if (index > 0) Text("  |  ", color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(localized("鸣谢名单", "Acknowledgements"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 4.dp))
            Text(localized("我自己。", "Myself."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(32.dp))
            Text(localized("© 2026 made with ♥ by kelesss", "© 2026 made with ♥ by kelesss"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(localized("软件使用 ChatGPT Codex 和 Kimi Code 辅助构建。", "Built with assistance from ChatGPT Codex and Kimi Code."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun AssetImage(path: String, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val image = remember(path) { runCatching { context.assets.open(path).use { stream -> BitmapFactory.decodeStream(stream) }.asImageBitmap() }.getOrNull() }
    if (image != null) Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)))
}

private fun String.ensureCifExtension() = if (endsWith(".cif", true)) this else "$this.cif"
