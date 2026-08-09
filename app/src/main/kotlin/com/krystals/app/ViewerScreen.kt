@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import androidx.core.content.edit
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.app.ui.AppPalette
import com.krystals.app.ui.ThemeMode
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.VoronoiAbortedException
import com.krystals.crystal.analysis.bonding.isMolecularCrystal
import com.krystals.crystal.analysis.bonding.toMolecules
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.periodic.Int3
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.state.InteractionReducer
import com.krystals.interaction.state.ViewerCommand
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.style.RenderPalette
import com.krystals.renderer.core.style.ViewerAppearance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.filament.FilamentRenderer
import com.krystals.renderer.filament.exportRenderSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal sealed class ViewerPanel {
    object None : ViewerPanel()
    object Align : ViewerPanel()
    object Measure : ViewerPanel()
    object Display : ViewerPanel()
    object Info : ViewerPanel()
    object Appearance : ViewerPanel()
    object Settings : ViewerPanel()
}


@Composable
internal fun ViewerScreen(
    viewModel: KrystalsViewModel,
    preferences: SharedPreferences,
    onSave: (DocumentTab) -> Unit,
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onSaveToPreset: () -> Unit,
    // Per v0.8.44: share goes through the file-name rename dialog (handled in KrystalsRoot).
    onShare: (DocumentTab) -> Unit,
    onNew: () -> Unit,
    onOnlineSource: () -> Unit,
    onExport: (Bitmap) -> Unit,
    onExit: () -> Unit,
    onClose: (Int) -> Unit,
    onBackCloseCurrent: () -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    onMessage: (String) -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onSponsor: () -> Unit,
    onFeedback: () -> Unit,
    onRunBondComputation: ((suspend () -> EditResult?) -> Unit),
    onApplyAppearance: (ViewerAppearance) -> Unit,
    backgroundFollowTheme: Boolean,
    onBackgroundFollowThemeChange: (Boolean) -> Unit,
    settingsValues: SettingsValues = SettingsValues.defaults(),
    onSettingsChange: (SettingsValues) -> Unit = {},
    onRestoreDefaults: () -> Unit = {},
) {
    val tab = viewModel.current ?: return
    val scope = rememberCoroutineScope()
    // Per v0.6.3: export-image loading dialog with back-button cancel.
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val exportingMessage = localized("导出图片中...", "Exporting image...")
    // Per v0.6.3: pre-resolve composable values for use in non-composable onClick lambdas.
    val shareLabel = localized("分享到…", "Share to…")
    var activeFilamentRenderer by remember { mutableStateOf<FilamentRenderer?>(null) }
    val filamentErrorMessage = localized(
        "Filament 渲染引擎初始化失败",
        "Filament rendering engine failed to initialize",
    )
    fun selectTab(index: Int) {
        if (index == viewModel.selectedIndex) return
        viewModel.select(index)
    }
    var menuOpen by remember { mutableStateOf(false) }
    var toolOpen by remember(tab.id) { mutableStateOf(false) }
    // Per v0.8.1: single state for the mutually-exclusive full-screen panels.
    var activePanel by remember { mutableStateOf<ViewerPanel>(ViewerPanel.None) }
    // Per v0.7.1: persistent overlay message shown during atom-edit / bond-draw flows.
    var persistentMessage by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = true) {
        when {
            activePanel != ViewerPanel.None -> activePanel = ViewerPanel.None
            // Per v0.7.1: exit bond-draw / atom-edit mode and return to the editor panel.
            // Priority is higher than floating-ball secondary menu retraction.
            tab.bondDrawMode != BondDrawMode.NONE || tab.atomEditMode != AtomEditMode.NONE || persistentMessage != null -> {
                tab.pendingEditorTab = when {
                    // Per v0.8.x: return to the tab matching the draw target type.
                    tab.bondDrawMode != BondDrawMode.NONE -> if (tab.bondDrawTargetIsHbond) "hbonds" else "bonds"
                    tab.atomEditMode != AtomEditMode.NONE -> "atoms"
                    else -> null
                }
                tab.bondDrawMode = BondDrawMode.NONE
                // Per v0.8.x: exiting draw/delete mode resets the target type flag so the next
                // entry (from the covalent or hbond sub-menu) starts from a clean state.
                tab.bondDrawTargetIsHbond = false
                tab.bondDrawFirstSiteId = null
                tab.bondDrawFirstCartesian = null
                tab.selectedAtomIds = emptyList()
                tab.atomEditMode = AtomEditMode.NONE
                persistentMessage = null
                if (tab.pendingEditorTab != null) tab.editorOpen = true
            }
            menuOpen -> menuOpen = false
            toolOpen -> toolOpen = false
            tab.commentsOpen -> tab.commentsOpen = false
            tab.editorOpen -> tab.editorOpen = false
            else -> onBackCloseCurrent()
        }
    }
    // Per v0.5.2a: while non-null, the viewer renders with this edited appearance (press-and-hold
    // "Preview" in the Appearance dialog sets it and hides the dialog); null = use tab.appearance.
    var previewAppearance by remember { mutableStateOf<ViewerAppearance?>(null) }
    var floatingX by remember(tab.id) { mutableFloatStateOf(0f) }
    var floatingY by remember(tab.id) { mutableFloatStateOf(0f) }
    var floatingDragging by remember(tab.id) { mutableStateOf(false) }
    val floatingAnimation = if (floatingDragging) snap<Float>() else tween(durationMillis = 220, easing = FastOutSlowInEasing)
    val renderedFloatingX by animateFloatAsState(floatingX, floatingAnimation, label = "floatingX")
    val renderedFloatingY by animateFloatAsState(floatingY, floatingAnimation, label = "floatingY")
    val floatingKey = remember(tab.id, tab.uri, tab.name) { "floating_${tab.uri ?: tab.name}" }
    LaunchedEffect(tab.id) {
        tab.floatingPosition = FloatingBallPosition(
            preferences.getFloat("${floatingKey}_x", tab.floatingPosition.xFraction),
            preferences.getFloat("${floatingKey}_y", tab.floatingPosition.yFraction),
            runCatching { FloatingBallSnap.valueOf(preferences.getString("${floatingKey}_snap", tab.floatingPosition.snap.name)!!) }.getOrDefault(FloatingBallSnap.FREE),
        )
    }
    // Per v0.4.2: only the main ball (the 54dp center FAB) must stay on-screen — the radial tool
    // fan is allowed to overhang the edges when expanded, so the ball can roam across the whole
    // screen instead of being penned in by the 180dp container frame.
    var ballParentSize by remember(tab.id) { mutableStateOf(IntSize.Zero) }
    var ballOuterSize by remember(tab.id) { mutableStateOf(IntSize.Zero) }
    val mainBallPx = with(LocalDensity.current) { 54.dp.toPx() }
    val snapThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    // Re-clamp on rotation / tab swap / first layout so an out-of-range offset snaps back in bounds.
    LaunchedEffect(ballParentSize, ballOuterSize, mainBallPx, tab.floatingPosition) {
        if (ballParentSize != IntSize.Zero && ballOuterSize != IntSize.Zero) {
            // Container is align(BottomEnd); the main ball is centered in it. The allowed offset
            // range keeps only the main ball's edges inside the parent, letting the container itself
            // (and the expanded tool fan) overhang the screen edges.
            val minX = (ballOuterSize.width + mainBallPx) / 2f - ballParentSize.width
            val maxX = (ballOuterSize.width - mainBallPx) / 2f
            val minY = (ballOuterSize.height + mainBallPx) / 2f - ballParentSize.height
            val maxY = (ballOuterSize.height - mainBallPx) / 2f
            val usableWidth = (ballParentSize.width - mainBallPx).coerceAtLeast(1f)
            val usableHeight = (ballParentSize.height - mainBallPx).coerceAtLeast(1f)
            val centerX = mainBallPx / 2f + tab.floatingPosition.xFraction * usableWidth
            val centerY = mainBallPx / 2f + tab.floatingPosition.yFraction * usableHeight
            floatingX = (centerX - (ballParentSize.width - ballOuterSize.width / 2f)).coerceIn(minX, maxX)
            floatingY = (centerY - (ballParentSize.height - ballOuterSize.height / 2f)).coerceIn(minY, maxY)
        }
    }
    var legendExpanded by remember(tab.id) { mutableStateOf(false) }
    val lengthChoice = localized("长度", "Length")
    val angleChoice = localized("角度", "Angle")
    val dihedralChoice = localized("二面角", "Dihedral")
    val offChoice = localized("关闭", "Off")
    fun dispatchViewerCommand(command: ViewerCommand) {
        val before = tab.interactionState
        val after = InteractionReducer.reduce(before, command)
        if (after == before) return
        if (after.document != before.document) tab.recordHistory()
        tab.interactionState = after
    }
    // Per v0.5.3b: build the scene off the UI thread with a timeout. Previously this ran synchronously
    // on the Main thread inside `remember`, so a large cell (materialising ~77k shell atoms) froze
    // the UI and OOM'd with no way to cancel. Now a key change cancels the prior build (the stale
    // result is discarded). The last successful scene stays mounted during rebuilds so the
    // Filament Surface and Engine are not torn down for every appearance change.
    val renderedAppearance = previewAppearance ?: tab.appearance
    var sceneResult by remember(tab.id) {
        mutableStateOf<Result<RenderScene>?>(null)
    }
    // Per v0.6.3: scene build timeout/failure dialog with undo.
    var sceneBuildError by remember(tab.id) { mutableStateOf<String?>(null) }
    val sceneTimeoutMessage = localized(
        "构建场景原子数过多，回退回前列场景。",
        "Too many atoms in scene, reverting to previous scene.",
    )
    // Per v0.7.1: scene rebuild loading dialog — shows after 300ms delay to avoid flicker
    // on fast rebuilds. Back button undoes the last change.
    var sceneRebuilding by remember(tab.id) { mutableStateOf(false) }
    LaunchedEffect(tab.id, tab.structure, tab.expansion, tab.bondConfiguration, renderedAppearance, tab.renderConfiguration, tab.visibility, tab.moleculeExtend, tab.hbondAngleThreshold) {
        // Per v0.6.3: removed currentOrientation key + delay — the Filament viewport already
        // handles size changes via onSizeChanged → SetViewport, so rebuilding the entire scene on
        // rotation was unnecessary and caused the freeze. The renderer's updateInteraction handles
        // viewport changes without needing a new scene.
        // Per v0.6.2: use try-catch instead of runCatching so that CancellationException
        // (thrown when the effect is cancelled due to a key change) is rethrown, not stored
        // as a failure. Previously runCatching swallowed it, briefly showing "the coroutine
        // scope … was cancelled" in the error Text below whenever tab.structure changed.
        // Per v0.7.1: delayed dialog — only show if rebuild takes > 300ms.
        // Per v0.8.1: launch in this effect's scope (cancelled on key change / leaving composition)
        // instead of GlobalScope, so a stale dialog can't mutate a disposed composition.
        val dialogJob = launch {
            kotlinx.coroutines.delay(300)
            sceneRebuilding = true
        }
        sceneResult = try {
            val sceneStart = System.currentTimeMillis()
            val scene = withTimeoutOrNull(BUILD_SCENE_TIMEOUT_MS) {
                // Per v0.8.43 (issue #11): the open pipeline (network build + scene build) runs
                // on openDispatcher — its own small pool, isolated from heavy bond computations.
                withContext(openDispatcher) {
                    val analysis = BondDetector.buildNetwork(
                        tab.structure, tab.bondConfiguration, tab.expansion,
                        hbondAngleThreshold = tab.hbondAngleThreshold,
                    )
                    // 分子晶体分析(打开晶体、原子与键加载完毕后):检查一次 isMolecularCrystal,
                    // 为 false 走固定流程;为 true 则 parse 得到分子列表(与原子/键并列储存),
                    // 供后续分子接口使用。惰性:仅首次(或结构变更后)计算一次,复用本次键网络。
                    if (tab.moleculeAnalysisPending) {
                        tab.isMolecularCrystal = analysis.isMolecularCrystal()
                        tab.molecules = if (tab.isMolecularCrystal) analysis.toMolecules() else emptyList()
                        // 分子 → 原胞原子 siteId 集合:先建 cellOffset==(0,0,0) 原子 id→siteId
                        // 映射,再逐分子查询(避免对每个分子重复扫描全部原子)。
                        tab.moleculeSiteIds = if (tab.isMolecularCrystal) {
                            val cellAtomSiteByAtomId = analysis.atoms
                                .filter { !it.isShell && it.cellOffset == Int3(0, 0, 0) }
                                .associate { it.id.toInt() to it.siteId }
                            tab.molecules.map { m -> m.atoms.mapNotNull { cellAtomSiteByAtomId[it.id] }.toSet() }
                        } else emptyList()
                        // 分子晶体默认启用"按分子展开"：仅新打开/结构变更(undo/redo、自动键规则)
                        // 后分析时重置一次;编辑(含"扩展到晶胞外"全选)不再联动 moleculeExtend。
                        // Per v0.8.27: 由偏好设置"分子晶体默认按分子延伸"决定(默认 true)。
                        if (tab.moleculeExtendDefaultPending) {
                            tab.moleculeExtend = tab.isMolecularCrystal && settingsValues.defaultMoleculeExtend
                            tab.moleculeExtendDefaultPending = false
                        }
                        tab.moleculeAnalysisPending = false
                    }
                    CrystalRenderSceneFactory.build(
                        analysis = analysis,
                        appearance = renderedAppearance,
                        renderConfiguration = tab.renderConfiguration,
                        hiddenSiteIds = tab.visibility.hiddenSites,
                        hiddenBondKeys = tab.visibility.hiddenBondPairs,
                        showBonds = tab.visibility.showBonds,
                        polyhedronSiteIds = tab.visibility.polyhedronSites,
                        structuralExpansion = tab.structuralExpansion,
                        moleculeExtend = tab.moleculeExtend,
                        molecules = tab.molecules,
                        hbondAngleThreshold = tab.hbondAngleThreshold,
                    )
                }
            }
            dialogJob.cancel()
            sceneRebuilding = false
            if (scene != null) {
                debugLog(CIF_OPEN_TAG) { "Scene build done (${scene.atoms.size} atoms, ${scene.bonds.size} bonds, ${scene.meshes.size} meshes) [scene +${System.currentTimeMillis() - sceneStart}ms]" }
                Result.success(scene)
            }
            else {
                // Per v0.6.3: scene build timed out — show dialog and undo.
                sceneBuildError = sceneTimeoutMessage
                Result.failure(IllegalStateException(sceneTimeoutMessage))
            }
        } catch (ce: CancellationException) {
            dialogJob.cancel()
            sceneRebuilding = false
            throw ce
        } catch (e: Throwable) {
            dialogJob.cancel()
            sceneRebuilding = false
            // Per v0.6.3: scene build failed — show dialog and undo.
            sceneBuildError = e.message ?: sceneTimeoutMessage
            Result.failure(e)
        }
    }
    // Per v0.5.0: per-site bond-valence sums for the atom-info window (s = X.XX).
    // Per v0.6.4: moved to produceState + Dispatchers.Default because SymmetryExpander.expand()
    // (called inside bondValenceSums) blocks the main thread for structures with many symmetry
    // operations (e.g. Fm-3m = 192 ops), causing 576-frame skips when opening CIF files.
    // Per v0.7.2: lazy — only run the full periodic-Voronoi BVS pass when the user has selected
    // an atom (atom-info window visible); empty map otherwise. Previously every open (and every
    // bondConfiguration change) computed BVS concurrently with the background smart-ionic pass,
    // doubling the Voronoi CPU load during file open.
    val bondValenceBySiteState = produceState<Map<String, Double>>(
        emptyMap(),
        // Per v0.8.45: bondEpsilon is deliberately NOT a key — bondValenceSums never uses it, so
        // epsilon-slider changes previously restarted (and piled up) the full periodic-Voronoi
        // BVS pass, leaving the old CPU-bound producers running to completion after cancellation.
        tab.structure, tab.bondConfiguration, tab.selectedAtomIds.isNotEmpty(),
    ) {
        value = if (tab.selectedAtomIds.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                // Per v0.8.45: cancelCheck aborts the Voronoi search when this producer is
                // restarted/cancelled; VoronoiAbortedException is swallowed (empty map) while
                // CancellationException keeps propagating to produceState.
                try {
                    BondValence.bondValenceSums(tab.structure, tab.bondConfiguration, cancelCheck = { coroutineContext.isActive })
                } catch (_: VoronoiAbortedException) {
                    emptyMap()
                }
            }
        } else emptyMap()
    }
    val bondValenceBySite = bondValenceBySiteState.value

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Krystals", fontWeight = FontWeight.Bold, maxLines = 1) },
            navigationIcon = { Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }; AppMenu(
                menuOpen = menuOpen,
                onDismissMenu = { menuOpen = false },
                onOpen = onOpen,
                onOpenPreset = onOpenPreset,
                onOnlineSource = onOnlineSource,
                onNew = onNew,
                onHelp = onHelp,
                onFeedback = onFeedback,
                onAbout = onAbout,
                onSponsor = onSponsor,
                onExit = onExit,
                fileActions = {
                    DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuOpen = false; onSave(tab) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_to_presets)) }, leadingIcon = { Icon(Icons.Default.Bookmark, null) }, onClick = { menuOpen = false; onSaveToPreset() })
                    DropdownMenuItem(text = { Text(shareLabel) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                        menuOpen = false
                        // Per v0.8.44: share first confirms the file name (rename window like save).
                        onShare(tab)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_image)) }, leadingIcon = { Icon(Icons.Default.Photo, null) }, onClick = {
                        menuOpen = false
                        sceneResult?.getOrNull()?.let { scene ->
                            exporting = true
                            exportJob = scope.launch {
                                try {
                                    debugLog(EXPORT_IMAGE_TAG) { "ExportImage 1/4: export started (${scene.atoms.size} atoms, ${scene.bonds.size} bonds)" }
                                    val renderer = activeFilamentRenderer
                                    // HIGH uses a larger aspect-preserving offscreen target. Keep
                                    // it single-sampled: multisampled readPixels crashes on some
                                    // Android GPU drivers, while supersampling already smooths edges.
                                    val useHigh = settingsValues.exportQuality == ExportQuality.HIGH
                                    val bitmap = if (renderer != null) {
                                        runCatching {
                                            renderer.submit(scene)
                                            if (useHigh) {
                                                val (w, h) = exportRenderSize(
                                                    tab.interactionState.session.viewportWidth,
                                                    tab.interactionState.session.viewportHeight,
                                                    high = true,
                                                )
                                                renderer.renderToBitmap(w, h, msaaSamples = 1)
                                            } else {
                                                renderer.renderToBitmap()
                                            }
                                        }.getOrNull()
                                    } else null
                                    if (bitmap == null) {
                                        warnLog(EXPORT_IMAGE_TAG) { "ExportImage: render failed, no bitmap" }
                                        onMessage("Unable to export current crystal")
                                    } else {
                                        debugLog(EXPORT_IMAGE_TAG) { "ExportImage 2/4: render done (${bitmap.width}x${bitmap.height})" }
                                        // Per v0.8.43 (issue #9): the readback bitmap is already mutable —
                                        // draw the overlay directly on it instead of copy()ing a third
                                        // 64MB (4096²) bitmap on the main thread.
                                        if (settingsValues.exportShowAxes || settingsValues.exportShowMeasurements) {
                                            ExportOverlay.apply(
                                                bitmap = bitmap,
                                                scene = scene,
                                                state = tab.interactionState,
                                                includeAxes = settingsValues.exportShowAxes,
                                                includeMeasurements = settingsValues.exportShowMeasurements,
                                                bondValenceBySite = bondValenceBySite,
                                            )
                                            debugLog(EXPORT_IMAGE_TAG) { "ExportImage 3/4: overlay applied (axes=${settingsValues.exportShowAxes}, measurements=${settingsValues.exportShowMeasurements})" }
                                        }
                                        onExport(bitmap)
                                    }
                                } catch (error: Throwable) {
                                    // Per v0.8.43 (issue #9): catch Throwable (was Exception) — an
                                    // OutOfMemoryError during export used to crash the process instead
                                    // of degrading to the failure message.
                                    if (error !is CancellationException) onMessage(error.message ?: "Export failed")
                                } finally {
                                    exporting = false
                                    exportJob = null
                                }
                            }
                        } ?: onMessage("Unable to export current crystal")
                    })
                },
            ) } },
            actions = {
                // Per v0.8.1: reading historyVersion subscribes this lambda to undo/redo changes
                // so the buttons' enablement recomposes without re-running the whole ViewerScreen.
                tab.historyVersion
                IconButton(onClick = { tab.undo() }, enabled = tab.history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, localized("撤回", "Undo")) }
                IconButton(onClick = { tab.redo() }, enabled = tab.history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, localized("前进", "Redo")) }
                IconButton(onClick = { activePanel = ViewerPanel.Appearance }) { Icon(Icons.Default.ColorLens, null) }
                IconButton(onClick = { activePanel = ViewerPanel.Settings }) { Icon(Icons.Default.Settings, null) }
            },
        )
        DocumentTabs(viewModel, onClose, ::selectTab)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            val current = sceneResult
            when {
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                current.isSuccess -> {
                    val scene = current.getOrThrow()
                    val noBondMessage = localized("所选原子间没有成键", "No bond between the selected atoms")
                    val handleAtomTap: (com.krystals.crystal.core.model.AtomImage) -> Boolean = { atom ->
                        when {
                            // Per v0.7.1: bond draw mode — first tap selects atom A (with highlight),
                            // second tap calculates distance and opens BondRuleDialog with preset values.
                            tab.bondDrawMode == BondDrawMode.DRAWING -> {
                                if (tab.bondDrawFirstSiteId == null) {
                                    tab.bondDrawFirstSiteId = atom.siteId
                                    tab.bondDrawFirstCartesian = atom.cartesianCoordinate.toVec3()
                                    // Per v0.7.1: highlight the first selected atom.
                                    tab.selectedAtomIds = listOf(atom.id)
                                } else {
                                    val firstCartesian = tab.bondDrawFirstCartesian!!
                                    val dist = (atom.cartesianCoordinate.toVec3() - firstCartesian).length()
                                    // Per v0.8.x: capture the target type before the flag resets,
                                    // then auto-navigate to the matching tab.
                                    val drawTargetIsHbond = tab.bondDrawTargetIsHbond
                                    tab.pendingBondDrawRule = BondRule(
                                        tab.bondDrawFirstSiteId!!, atom.siteId,
                                        0.1, dist + 0.1,
                                        BondRuleSource.CUSTOM,
                                        isHBond = drawTargetIsHbond,
                                    )
                                    tab.bondDrawMode = BondDrawMode.NONE
                                    tab.bondDrawTargetIsHbond = false
                                    tab.bondDrawFirstSiteId = null
                                    tab.bondDrawFirstCartesian = null
                                    tab.selectedAtomIds = emptyList()
                                    persistentMessage = null
                                    // Per v0.7.1: auto-navigate to the bond tab matching the draw target.
                                    tab.pendingEditorTab = if (drawTargetIsHbond) "hbonds" else "bonds"
                                    tab.editorOpen = true
                                }
                                true
                            }
                            // Per v0.7.1: bond delete mode — first tap selects atom A (with highlight),
                            // second tap checks if a bond rule exists between the two atoms.
                            tab.bondDrawMode == BondDrawMode.DELETING -> {
                                if (tab.bondDrawFirstSiteId == null) {
                                    tab.bondDrawFirstSiteId = atom.siteId
                                    // Per v0.7.1: highlight the first selected atom.
                                    tab.selectedAtomIds = listOf(atom.id)
                                } else {
                                    val siteA = tab.bondDrawFirstSiteId!!
                                    val siteB = atom.siteId
                                    // Per v0.8.x: capture the target type before the flag resets.
                                    val deleteTargetIsHbond = tab.bondDrawTargetIsHbond
                                    // Per v0.8.x: hbond rules carry the "\u0000hbond" key suffix -
                                    // match by the draw target type so deleting an H-bond never
                                    // hits a normal rule for the same site pair (or vice versa).
                                    val key = listOf(siteA, siteB).sorted().joinToString("\u0000") +
                                        if (deleteTargetIsHbond) "\u0000hbond" else ""
                                    val matchingRule = tab.bondConfiguration.rules.firstOrNull { it.key == key }
                                    if (matchingRule != null) {
                                        tab.recordHistory()
                                        val result = CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.RemoveBondRule(key))
                                        tab.structure = result.structure
                                        tab.bondConfiguration = result.bondConfiguration
                                        tab.dirty = true
                                        tab.moleculeAnalysisPending = true
                                    } else {
                                        onMessage(noBondMessage)
                                    }
                                    tab.bondDrawMode = BondDrawMode.NONE
                                    tab.bondDrawTargetIsHbond = false
                                    tab.bondDrawFirstSiteId = null
                                    tab.bondDrawFirstCartesian = null
                                    tab.selectedAtomIds = emptyList()
                                    persistentMessage = null
                                    // Per v0.7.1: auto-navigate to the bond tab matching the draw target.
                                    tab.pendingEditorTab = if (deleteTargetIsHbond) "hbonds" else "bonds"
                                    tab.editorOpen = true
                                }
                                true
                            }
                            tab.atomEditMode == AtomEditMode.DELETE_NEXT -> {
                                tab.atomEditMode = AtomEditMode.NONE
                                persistentMessage = null
                                val deleted = runCatching {
                                    CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.DeleteAtom(atom.siteId))
                                }.getOrNull()
                                // Per v0.7.1: deleting an atom no longer regenerates all bond rules.
                                if (deleted != null) {
                                    tab.recordHistory()
                                    tab.structure = deleted.structure
                                    tab.bondConfiguration = deleted.bondConfiguration
                                    tab.dirty = true
                                    tab.selectedAtomIds = emptyList()
                                    // Per v0.7.1: return to the atom editor page after deletion.
                                    tab.pendingEditorTab = "atoms"
                                    tab.editorOpen = true
                                }
                                true
                            }
                            tab.atomEditMode == AtomEditMode.MODIFY_NEXT -> {
                                tab.editingSiteId = atom.siteId; tab.atomEditMode = AtomEditMode.NONE; persistentMessage = null; tab.editorOpen = true
                                true
                            }
                            else -> false
                        }
                    }
                    val context = LocalContext.current
                    val onFilamentFailure: (Throwable) -> Unit = { onMessage(filamentErrorMessage) }
                    val filamentResult = remember { runCatching { FilamentRenderer(context) } }
                    LaunchedEffect(filamentResult) {
                        filamentResult.exceptionOrNull()?.let(onFilamentFailure)
                    }
                    val renderer = remember(filamentResult) { filamentResult.getOrNull() }
                    if (renderer == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(filamentErrorMessage, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        RendererHost(
                            renderer = renderer,
                            scene = scene,
                            state = tab.interactionState,
                            onCommand = ::dispatchViewerCommand,
                            onAtomTap = handleAtomTap,
                            bondValenceBySite = bondValenceBySite,
                            onFilamentRendererChanged = { activeFilamentRenderer = it },
                            onFilamentFailure = onFilamentFailure,
                        )
                    }
                }
                else -> {
                    // Per v0.6.2: defensively suppress CancellationException messages (should
                    // never reach here after the fix above, but guard against future regressions).
                    val error = current.exceptionOrNull()
                    val displayMessage = if (error is CancellationException) "Unable to build scene"
                        else error?.message ?: "Unable to build scene"
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(displayMessage, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Per v0.7.0: lock button at viewer top-right.
            // Per v0.8.1: inactive = 50% opacity; active = solid circular background + hollow icon.
            // Per v0.8.26: hidden when the user hides it, unless already locked (to prevent lock-in).
            if (settingsValues.showLockButton || tab.interactionState.session.locked) {
                IconButton(
                    onClick = { dispatchViewerCommand(ViewerCommand.ToggleLock) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    // Per v0.8.33: animated lock ↔ unlock transition (scale + fade), so the
                    // state change reads clearly instead of snapping. (The initial 180° rotation
                    // idea was dropped: it rendered the unlock state upside down.)
                    AnimatedContent(
                        targetState = tab.interactionState.session.locked,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.4f, animationSpec = tween(220)) +
                                fadeIn(animationSpec = tween(180))) togetherWith
                                (scaleOut(targetScale = 0.4f, animationSpec = tween(220)) +
                                    fadeOut(animationSpec = tween(180)))
                        },
                        label = "lockIcon",
                    ) { locked ->
                        if (locked) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.LockOpen,
                                    null,
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

    // Per v0.7.1: scene rebuild loading dialog — blocks user interaction during rebuild.
    if (sceneRebuilding) {
        androidx.compose.material3.BasicAlertDialog(
            onDismissRequest = {
                sceneRebuilding = false
                tab.undo()
            },
            // Per v0.8.36: loading dialogs dismiss only via the system back button.
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        ) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(localized("计算中...", "Computing..."))
                }
            }
        }
    }
    // Per v0.6.3: scene build timeout/failure dialog — undo to previous state.
    sceneBuildError?.let { message ->
        AlertDialog(
            onDismissRequest = {
                sceneBuildError = null
                tab.undo()
            },
            title = { Text(localized("场景构建失败", "Scene build failed")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    sceneBuildError = null
                    tab.undo()
                }) { Text(localized("回退", "Undo")) }
            },
        )
    }

            // Per v0.6.3: export-image loading dialog with back-button cancel.
            if (exporting) {
                androidx.compose.material3.BasicAlertDialog(onDismissRequest = {
                    exportJob?.cancel()
                    exporting = false
                    exportJob = null
                }) {
                    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
                        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            androidx.compose.material3.CircularProgressIndicator()
                            Text(exportingMessage)
                        }
                    }
                }
            }

            // Legend groups by element, sourced from the edited structure (not the
            // rendered atoms) so it doesn't churn as visibility changes. Per v0.8.30: when every
            // site of an element shares the same color, the element collapses into one row
            // {X  [color]}; otherwise each site gets its own row labeled by site.label (X1, X2...).
            val legendEntries = remember(tab.structure, tab.visibility) {
                val visibleSites = tab.structure.sites.filterNot { it.id in tab.visibility.hiddenSites }
                visibleSites
                    .groupBy { it.species.symbol }
                    .toSortedMap()
                    .flatMap { (element, sites) ->
                        val distinctArgbs = sites.map { RenderPalette.resolveSiteArgb(it.id, element, tab.renderConfiguration) }.distinct()
                        if (distinctArgbs.size == 1) {
                            listOf(LegendEntry(element, distinctArgbs.first()))
                        } else {
                            sites.sortedBy { it.label }.map { site ->
                                LegendEntry(site.label, RenderPalette.resolveSiteArgb(site.id, element, tab.renderConfiguration))
                            }
                        }
                    }
            }
            // Per v0.8.26: legend toggle respects user preference.
            if (settingsValues.showLegend) {
                ElementLegend(
                    entries = legendEntries,
                    expanded = legendExpanded,
                    onToggle = { legendExpanded = !legendExpanded },
                    onExpand = { legendExpanded = true },
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                )
            }

            // Per v0.7.1: persistent overlay message for atom-edit / bond-draw flows.
            persistentMessage?.let { msg ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Per v0.8.26: use user-configured collapsed alpha.
            val floatingAlpha by animateFloatAsState(targetValue = if (toolOpen) 1f else settingsValues.ballCollapsedAlpha, label = "floatingAlpha")
            // Per v0.2.2: floating-ball palette uses the project's two purples. Dark mode = deep
            // bg + light icon; light mode = light bg + deep icon. (see AppPalette.floatingBall)
            val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            val (containerArgb, contentArgb) = AppPalette.floatingBall(dark)
            val baseContainer = Color(containerArgb)
            val baseContent = Color(contentArgb)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .onGloballyPositioned { coords ->
                        ballOuterSize = coords.size
                        ballParentSize = coords.parentCoordinates?.size ?: IntSize.Zero
                    }
                    .padding(18.dp)
                    .size(180.dp)
                    .offset { IntOffset(renderedFloatingX.roundToInt(), renderedFloatingY.roundToInt()) }
                    .graphicsLayer { this.alpha = floatingAlpha; this.clip = false },
                contentAlignment = Alignment.Center,
            ) {
                // Per v0.2: the Measure button shows an active (light bg / dark icon) state while a
                // measurement mode is active; the Lock button does the same while the view is locked.
                data class Tool(val icon: androidx.compose.ui.graphics.vector.ImageVector, val active: Boolean, val action: () -> Unit)
                val tools = listOf(
                    Tool(Icons.Default.ControlCamera, active = false) { activePanel = ViewerPanel.Align },
                    Tool(Icons.Default.Straighten, active = tab.measurementMode != MeasurementMode.NONE) { activePanel = ViewerPanel.Measure },
                    Tool(Icons.AutoMirrored.Filled.Comment, active = false) { tab.commentsOpen = true },
                    Tool(Icons.Default.Info, active = false) { activePanel = ViewerPanel.Info },
                    Tool(Icons.Default.Edit, active = false) { tab.editorOpen = true },
                    Tool(Icons.Default.Visibility, active = false) { activePanel = ViewerPanel.Display },
                )
                val offsets = FloatingBallLayout.toolOffsets(tab.floatingPosition.snap)
                // Tier split: tier 1 = inner ring (radius ≤ 80px), tier 2 = outer ring.
                val tier1Count = run {
                    val firstOuter = offsets.indexOfFirst { sqrt(it.x * it.x + it.y * it.y) > 80f }
                    if (firstOuter > 0) firstOuter else offsets.size
                }
                // Main ball pulse on open: 1.0 → 0.9 → 1.0 in 60ms.
                val ballScale = remember(tab.id) { Animatable(1f) }
                LaunchedEffect(toolOpen) {
                    if (toolOpen) {
                        ballScale.snapTo(1f)
                        ballScale.animateTo(0.9f, tween(30, easing = FastOutSlowInEasing))
                        ballScale.animateTo(1.0f, tween(30, easing = FastOutSlowInEasing))
                    }
                }
                tools.forEachIndexed { index, (icon, active, action) ->
                    val toolOffset = offsets[index]
                    val animProgress = remember(tab.id, index) { Animatable(0f) }
                    val isTier2 = index >= tier1Count
                    // Parent tier 1 offset for tier 2 tools (nearest inner-ring button).
                    val parentOffset = if (isTier2) {
                        var bestDist = Float.MAX_VALUE
                        var best = offsets[0]
                        for (i in 0 until tier1Count) {
                            val dx = offsets[i].x - toolOffset.x
                            val dy = offsets[i].y - toolOffset.y
                            val d = dx * dx + dy * dy
                            if (d < bestDist) { bestDist = d; best = offsets[i] }
                        }
                        best
                    } else FloatPoint(0f, 0f)
                    LaunchedEffect(toolOpen) {
                        if (toolOpen) {
                            if (isTier2) {
                                // Per v0.6.2: tier 2 starts at a layout-dependent delay after release.
                                // sideTriangles/horizontalTriangles: 180ms; cornerFan/radial: 120ms.
                                val tier2BaseDelay = when (tab.floatingPosition.snap) {
                                    FloatingBallSnap.LEFT, FloatingBallSnap.RIGHT,
                                    FloatingBallSnap.TOP, FloatingBallSnap.BOTTOM -> 180L
                                    else -> 120L
                                }
                                val tier2Index = index - tier1Count
                                delay(tier2BaseDelay + tier2Index * 30L)
                                animProgress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                            } else {
                                // Tier 1: start after ball pulse (60ms), stagger 30ms.
                                delay(60L + index * 30L)
                                animProgress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                            }
                        } else {
                            // Close: tier 2 recedes first, then tier 1. Slightly faster than open.
                            if (isTier2) {
                                delay((tools.lastIndex - index) * 10L)
                                animProgress.animateTo(0f, tween(100, easing = FastOutSlowInEasing))
                            } else {
                                delay(60L + (tier1Count - 1 - index) * 30L)
                                animProgress.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
                            }
                        }
                    }
                    val progress = animProgress.value
                    val visibilityAlpha = (progress / 0.3f).coerceIn(0f, 1f)
                    val scale = 0.6f + 0.4f * progress
                    // Tier 2 flies from parent; tier 1 flies from ball centre.
                    val startX = parentOffset.x
                    val startY = parentOffset.y
                    val offsetX = startX + (toolOffset.x - startX) * progress
                    val offsetY = startY + (toolOffset.y - startY) * progress
                    FloatingActionButton(
                        onClick = { if (progress > 0.5f) action() },
                        shape = CircleShape,
                        containerColor = if (active) baseContent else baseContainer,
                        contentColor = if (active) baseContainer else baseContent,
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                this.alpha = visibilityAlpha
                                this.scaleX = scale
                                this.scaleY = scale
                            }
                            .offset(
                                x = offsetX.dp,
                                y = offsetY.dp,
                            ),
                    ) { Icon(icon, null) }
                }
                FloatingActionButton(
                    onClick = { toolOpen = !toolOpen },
                    shape = CircleShape,
                    containerColor = baseContainer,
                    contentColor = baseContent,
                    modifier = Modifier.size(54.dp)
                        .graphicsLayer {
                            this.scaleX = ballScale.value
                            this.scaleY = ballScale.value
                        }
                        .pointerInput(tab.id) {
                            detectDragGestures(
                                onDragStart = { floatingDragging = true },
                                onDragEnd = {
                                    val center = FloatPoint(ballParentSize.width - ballOuterSize.width / 2f + floatingX, ballParentSize.height - ballOuterSize.height / 2f + floatingY)
                                    val snapped = FloatingBallLayout.snap(center, ballParentSize.width.toFloat(), ballParentSize.height.toFloat(), mainBallPx / 2f + snapThresholdPx)
                                    tab.floatingPosition = snapped
                                    preferences.edit { putFloat("${floatingKey}_x", snapped.xFraction); putFloat("${floatingKey}_y", snapped.yFraction); putString("${floatingKey}_snap", snapped.snap.name) }
                                    floatingDragging = false
                                },
                                onDragCancel = { floatingDragging = false },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val minX = (ballOuterSize.width + mainBallPx) / 2f - ballParentSize.width
                                    val maxX = (ballOuterSize.width - mainBallPx) / 2f
                                    val minY = (ballOuterSize.height + mainBallPx) / 2f - ballParentSize.height
                                    val maxY = (ballOuterSize.height - mainBallPx) / 2f
                                    floatingX = (floatingX + amount.x).coerceIn(minX, maxX)
                                    floatingY = (floatingY + amount.y).coerceIn(minY, maxY)
                                },
                            )
                        },
                ) { AssetImage("icon_trans_release.png", Modifier.size(50.dp), ContentScale.Fit) }
            }
        }
    }

    // Per v0.7.0: EditorPanel moved outside Column so it gets full-screen space
    // (not constrained by TopAppBar/DocumentTabs height or navigationBars padding).
    if (tab.editorOpen) EditorPanel(tab, onDismiss = { tab.editorOpen = false }, onStructure = { viewModel.updateAnalysis(tab, it) }, onMessage = onMessage, onRunBondComputation = onRunBondComputation, onPersistentMessage = { persistentMessage = it })
    if (tab.commentsOpen) CommentsPanel(tab, onDismiss = { tab.commentsOpen = false })
    if (activePanel == ViewerPanel.Align) AlignDialog(
        onDismiss = { activePanel = ViewerPanel.None },
        onChoice = { choice ->
            when (choice) {
                "X", "Y", "Z" -> dispatchViewerCommand(ViewerCommand.AlignCartesian(choice.first()))
                "a", "b", "c" -> dispatchViewerCommand(ViewerCommand.AlignCellAxis(choice.first(), tab.structure.lattice))
            }
            activePanel = ViewerPanel.None
        },
    )
    if (activePanel == ViewerPanel.Measure) MeasureDialog(
        length = lengthChoice,
        angle = angleChoice,
        dihedral = dihedralChoice,
        off = offChoice,
        activeMode = when (tab.measurementMode) {
            MeasurementMode.LENGTH -> lengthChoice
            MeasurementMode.ANGLE -> angleChoice
            MeasurementMode.DIHEDRAL -> dihedralChoice
            else -> null
        },
        onDismiss = { activePanel = ViewerPanel.None },
        onChoice = { choice ->
            // Per v0.2.3: switching mode keeps any locked measurement; only the active selection resets.
            val mode = when {
                choice == lengthChoice -> MeasurementMode.LENGTH
                choice == angleChoice -> MeasurementMode.ANGLE
                choice == dihedralChoice -> MeasurementMode.DIHEDRAL
                else -> MeasurementMode.NONE
            }
            dispatchViewerCommand(ViewerCommand.SetMeasurementMode(mode)); activePanel = ViewerPanel.None
        },
    )
    if (activePanel == ViewerPanel.Display) DisplayPanel(tab, viewModel, onDismiss = { activePanel = ViewerPanel.None })
    if (activePanel == ViewerPanel.Info) InfoDialog(tab, onDismiss = { activePanel = ViewerPanel.None })
    // Per v0.5.3a: the dialog stays mounted (it self-hides via alpha) so the preview press-and-hold
    // gesture survives; the viewer uses previewAppearance while non-null.
    if (activePanel == ViewerPanel.Appearance) AppearanceDialog(
        tab,
        onDismiss = { activePanel = ViewerPanel.None },
        onApplied = { appearance -> viewModel.tabs.forEach { it.recordHistory() }; onApplyAppearance(appearance) },
        onPreviewStart = { previewAppearance = it },
        onPreviewEnd = { previewAppearance = null },
        backgroundFollowTheme = backgroundFollowTheme,
        onBackgroundFollowThemeChange = onBackgroundFollowThemeChange,
    )
    if (activePanel == ViewerPanel.Settings) SettingsPanel(
        settings = settingsValues,
        onChange = onSettingsChange,
        onDismiss = { activePanel = ViewerPanel.None },
        onRestoreDefaults = onRestoreDefaults,
    )
}


@Composable
private fun DocumentTabs(viewModel: KrystalsViewModel, onClose: (Int) -> Unit, onSelect: (Int) -> Unit) {
    val singleTab = viewModel.tabs.size == 1
    val tabBackground = if (singleTab) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    // Per v0.7.0: expand button on the left that opens a dropdown listing all tabs.
    var tabsExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Per v0.6.5: auto-scroll to the selected tab so newly opened files are visible.
    LaunchedEffect(viewModel.tabs.size, viewModel.selectedIndex) {
        if (viewModel.tabs.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.selectedIndex)
        }
    }
    val expandRotation by animateFloatAsState(
        targetValue = if (tabsExpanded) 180f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "tabsExpandRotation",
    )
    Row(Modifier.fillMaxWidth().height(46.dp)) {
        Box {
            IconButton(
                onClick = { tabsExpanded = !tabsExpanded },
                modifier = Modifier.fillMaxHeight(),
            ) {
                Icon(
                    Icons.Default.ExpandMore,
                    null,
                    modifier = Modifier.graphicsLayer { rotationZ = expandRotation },
                )
            }
            DropdownMenu(
                expanded = tabsExpanded,
                onDismissRequest = { tabsExpanded = false },
            ) {
                viewModel.tabs.forEachIndexed { index, tab ->
                    val isCurrent = index == viewModel.selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSelect(index); tabsExpanded = false }
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.name + if (tab.dirty) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(180.dp),
                    )
                    IconButton(onClick = { onClose(index) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            }
        }
        LazyRow(Modifier.weight(1f).background(tabBackground), state = listState) {
            itemsIndexed(viewModel.tabs, key = { _, tab -> tab.id }) { index, tab ->
                var drag by remember { mutableFloatStateOf(0f) }
                val tabColor = if (singleTab) MaterialTheme.colorScheme.surfaceVariant else if (index == viewModel.selectedIndex) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                Surface(
                    color = tabColor,
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
                    }.clickable { onSelect(index) },
                ) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.name + if (tab.dirty) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(145.dp))
                        IconButton(onClick = { onClose(index) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}


private data class LegendEntry(val label: String, val argb: Long)


@Composable
private fun ElementLegend(entries: List<LegendEntry>, expanded: Boolean, onToggle: () -> Unit, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val defaultHeight = 220.dp
    val collapseThreshold = 40.dp
    // Per v0.7.1: remembered height for next click-expand. Updated during drag when above threshold.
    var legendHeight by remember { mutableStateOf(defaultHeight) }
    // Per v0.7.1: local expanded state for immediate updates during drag (avoids recomposition lag).
    var localExpanded by remember { mutableStateOf(expanded) }
    // Per v0.7.1: drag state — when true, height follows finger via dragHeight (raw, instant).
    var isDragging by remember { mutableStateOf(false) }
    var dragHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // Per v0.7.1: track latest values for gesture callback without restarting pointerInput.
    val onToggleLatest by rememberUpdatedState(onToggle)
    val onExpandLatest by rememberUpdatedState(onExpand)
    // Sync localExpanded with external expanded parameter (for click toggle).
    LaunchedEffect(expanded) { localExpanded = expanded }
    // Per v0.7.0: smooth icon rotation (0° → 180°) over 150ms.
    val legendRotation by animateFloatAsState(
        targetValue = if (localExpanded) 180f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "legendRotation",
    )
    // Per v0.7.1: animated height — during drag, target is dragHeight with snap() so
    // animateDpAsState tracks it closely. When not dragging, target is the final state
    // (legendHeight or 0) with tween(150) for smooth click expand/collapse.
    val animatedHeight by animateDpAsState(
        targetValue = if (isDragging) dragHeight else (if (localExpanded) legendHeight else 0.dp),
        animationSpec = if (isDragging) snap() else tween(150, easing = FastOutSlowInEasing),
        label = "legendHeight",
    )
    // Per v0.7.1: use raw dragHeight during drag for zero-frame-delay finger tracking.
    // animatedHeight (≈dragHeight via snap) is used when drag ends, providing a smooth
    // transition into the tween animation toward the final target.
    val displayedHeight = if (isDragging) dragHeight else animatedHeight
    // Per v0.7.1: fade alpha — snap during drag, tween otherwise.
    val contentAlpha by animateFloatAsState(
        targetValue = if (localExpanded) 1f else 0f,
        animationSpec = if (isDragging) snap() else tween(150),
        label = "legendAlpha",
    )
    Surface(modifier.alpha(0.84f), shape = RoundedCornerShape(14.dp), tonalElevation = 5.dp) {
        Column(Modifier.width(130.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                // Initialize dragHeight to current displayed height.
                                dragHeight = if (localExpanded) legendHeight else 0.dp
                            },
                            onDragEnd = {
                                // Per v0.7.1: record legendHeight only at drag end, not during drag.
                                // If above threshold (expanded), save the final finger height for
                                // next click-expand. If below threshold (collapsed), keep the
                                // previous legendHeight unchanged.
                                if (dragHeight > collapseThreshold) {
                                    legendHeight = dragHeight
                                }
                                isDragging = false
                            },
                            onDragCancel = { isDragging = false },
                        ) { change, amount ->
                            change.consume()
                            val newHeight = (dragHeight - with(density) { amount.y.toDp() }).coerceIn(0.dp, 600.dp)
                            dragHeight = newHeight
                            if (newHeight > collapseThreshold) {
                                // Above threshold: expanded state.
                                // Per v0.7.1: do NOT update legendHeight here — only at drag end.
                                // Updating during drag would overwrite the remembered height as the
                                // user drags down to collapse (e.g. 500→41), so the next click-expand
                                // would restore to 41 instead of 500.
                                if (!localExpanded) {
                                    localExpanded = true
                                    onExpandLatest()
                                }
                            } else {
                                // Below threshold: collapsed state.
                                if (localExpanded) {
                                    localExpanded = false
                                    onToggleLatest()
                                }
                            }
                        }
                    }
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Category, null, modifier = Modifier.size(18.dp))
                Text(localized("图例", "Legend"), modifier = Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = legendRotation })
            }
            if (displayedHeight > 0.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(displayedHeight)
                        .clipToBounds()
                        .graphicsLayer { alpha = contentAlpha }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
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

