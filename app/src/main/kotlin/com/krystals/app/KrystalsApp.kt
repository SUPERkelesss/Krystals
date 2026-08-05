@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.VoronoiSearchLimitExceededException
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.analysis.structure.StructureAnalyzer
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.data.PeriodicTableData
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.state.InteractionReducer
import com.krystals.interaction.state.ViewerCommand
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.style.RenderPalette
import com.krystals.renderer.core.style.ViewerAppearance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.filament.FilamentRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private data class PendingOpen(val uri: Uri, val name: String, val text: String, val candidates: List<Int>)

/** Per v0.8.27: user-toggleable search filter types shown in the filter bar. */
private enum class SearchFilterType {
    FORMULA, ELEMENT_COUNT, CRYSTAL_SYSTEM, POINT_GROUP, SPACE_GROUP,
    TITLE, AUTHOR, JOURNAL, YEAR, ELEMENT_COMPOSITION, STABLE,
}

/** Per v0.8.27: default visible filters (formula + the original four). */
private val DEFAULT_VISIBLE_FILTERS = setOf(
    SearchFilterType.FORMULA, SearchFilterType.ELEMENT_COUNT, SearchFilterType.CRYSTAL_SYSTEM,
    SearchFilterType.POINT_GROUP, SearchFilterType.SPACE_GROUP,
)

/** Per v0.8.27: the full filter set offered by each search page. Per v0.8.29: COD has no
 * composition/stability filters; MP has no bibliographic filters. */
private val COD_FILTER_TYPES = setOf(
    SearchFilterType.FORMULA, SearchFilterType.ELEMENT_COUNT, SearchFilterType.CRYSTAL_SYSTEM,
    SearchFilterType.POINT_GROUP, SearchFilterType.SPACE_GROUP,
    SearchFilterType.TITLE, SearchFilterType.AUTHOR, SearchFilterType.JOURNAL, SearchFilterType.YEAR,
)
private val MP_FILTER_TYPES = setOf(
    SearchFilterType.FORMULA, SearchFilterType.ELEMENT_COUNT, SearchFilterType.CRYSTAL_SYSTEM,
    SearchFilterType.POINT_GROUP, SearchFilterType.SPACE_GROUP,
    SearchFilterType.ELEMENT_COMPOSITION, SearchFilterType.STABLE,
)

/** Per v0.6.5: filter state for search result filtering. */
private data class SearchFilterState(
    val elementCount: Int? = null,
    val crystalSystem: String? = null,
    val pointGroup: String? = null,
    val spaceGroup: String? = null,
    // Per v0.8.27: extended filters.
    val formula: String? = null,
    val title: String? = null,
    val author: String? = null,
    val journal: String? = null,
    val year: String? = null,
    val elementComposition: String? = null,
    val stable: Boolean? = null,
) {
    val isActive: Boolean get() = elementCount != null || crystalSystem != null || pointGroup != null ||
        spaceGroup != null || formula != null || title != null || author != null || journal != null ||
        year != null || elementComposition != null || stable != null
}

/** Per v0.6.5: metadata extracted from a search result for filtering. */
private data class SearchResultMeta(
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

/** Per v0.6.5: extract distinct element count from a formula string like "SiO2" or "Ca3(PO4)2". */
private fun countElementsInFormula(formula: String): Int {
    val regex = Regex("[A-Z][a-z]?")
    return regex.findAll(formula).map { it.value }.distinct().count()
}

/** Per v0.6.5: resolve crystal system and point group from a space group symbol or number. */
private fun resolveSpaceGroupMeta(sgSymbol: String, sgNumber: String? = null): Pair<String?, String?> {
    val catalog = if (!sgNumber.isNullOrBlank()) {
        sgNumber.toIntOrNull()?.let { SpaceGroupCatalog.all.getOrNull(it - 1) }
    } else null
        ?: SpaceGroupCatalog.find(sgSymbol)
    return catalog?.crystalSystem to catalog?.pointGroup
}

/** Per v0.6.5: extract metadata from MP search results. */
private fun MpSearchResult.meta(): SearchResultMeta {
    val (cs, pg) = resolveSpaceGroupMeta(spaceGroup)
    return SearchResultMeta(
        elementCount = countElementsInFormula(formula),
        crystalSystem = cs ?: crystalSystem.takeIf { it != "N/A" },
        pointGroup = pg,
        spaceGroup = spaceGroup.takeIf { it != "N/A" },
        formula = formula,
    )
}

/** Per v0.6.5: extract metadata from COD search results. */
private fun CodSearchResult.meta(): SearchResultMeta {
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
private fun buildFilterOptions(metas: List<SearchResultMeta>): SearchFilterOptions {
    val elementCounts = metas.map { it.elementCount }.distinct().sorted()
    val crystalSystems = metas.mapNotNull { it.crystalSystem }.distinct().sorted()
    val pointGroups = metas.mapNotNull { it.pointGroup }.distinct().sorted()
    val spaceGroups = metas.mapNotNull { it.spaceGroup }.distinct().sorted()
    // Per v0.8.27: extended filter options (empty when the source provides no values).
    val formulas = metas.map { it.formula }.filter { it.isNotBlank() }.distinct().sorted()
    val titles = metas.map { it.title }.filter { it.isNotBlank() }.distinct().sorted()
    val authors = metas.map { it.author }.filter { it.isNotBlank() }.distinct().sorted()
    val journals = metas.map { it.journal }.filter { it.isNotBlank() }.distinct().sorted()
    val years = metas.map { it.year }.filter { it.isNotBlank() }.distinct().sorted()
    // Per v0.8.29: element-composition options = distinct sorted element sets (e.g. "Fe O").
    val elementCompositions = metas.map { elementsInFormula(it.formula).sorted().joinToString(" ") }
        .filter { it.isNotBlank() }.distinct().sorted()
    return SearchFilterOptions(elementCounts, crystalSystems, pointGroups, spaceGroups, formulas, titles, authors, journals, years, elementCompositions)
}

private data class SearchFilterOptions(
    val elementCounts: List<Int>,
    val crystalSystems: List<String>,
    val pointGroups: List<String>,
    val spaceGroups: List<String>,
    // Per v0.8.27: extended filter options.
    val formulas: List<String> = emptyList(),
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
private data class PendingLargeOpen(val parsed: ParsedStructure, val name: String, val uri: Uri?, val expandedEstimate: Int)

/** Holds a structure-change bond recomputation whose expanded atom count exceeds the warn threshold. */
private class PendingLargeEdit(val block: suspend () -> com.krystals.crystal.analysis.editing.EditResult?, val expandedEstimate: Int)

/** Per v0.5.3b: scene build runs off the UI thread with this cap; on timeout it fails with a
 *  readable error instead of hanging the viewer. */
private const val BUILD_SCENE_TIMEOUT_MS = 15_000L

/** Per v0.5.3b: warn before opening a cell whose asymmetric expansion exceeds this many atoms. */
private const val LARGE_CELL_WARN_THRESHOLD = 1000

/** Per v0.8.25: startup update check runs at most once per 24h (saves data/battery). */
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

@Composable
fun KrystalsRoot(
    activity: MainActivity,
    incomingUri: Uri?,
    consumeIncomingUri: () -> Unit,
    viewModel: KrystalsViewModel = viewModel(),
) {
    val preferences = remember { activity.getSharedPreferences("krystals", 0) }
    var settingsValues by remember { mutableStateOf(PreferencesStore.load(preferences)) }
    var themeMode by remember {
        mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("theme", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM))
    }
    var language by remember {
        mutableStateOf(preferences.getString("language", if (java.util.Locale.getDefault().language == "zh") "zh" else "en") ?: "en")
    }
    val systemDark = isSystemInDarkTheme()
    var backgroundFollowTheme by remember {
        mutableStateOf(preferences.getBoolean("bg_follow_theme", true))
    }
    fun applyLanguage(value: String) {
        if (language == value) return
        language = value
        preferences.edit().putString("language", value).putBoolean("pending_language_restart", true).apply()
        activity.recreate()
    }
    val onSettingsChange: (SettingsValues) -> Unit = { sv ->
        settingsValues = sv
        PreferencesStore.save(preferences, sv)
        // Per v0.8.33: theme and language take effect immediately (previously only after restart).
        if (sv.theme != themeMode) {
            themeMode = sv.theme
            // Per v0.8.34: keep the follow-theme viewer background in sync when the theme is
            // switched from Preferences (applyTheme does this for the menu path).
            if (backgroundFollowTheme) {
                val dark = sv.theme == ThemeMode.DARK || sv.theme == ThemeMode.SYSTEM && systemDark
                val background = if (dark) 0xFF101014 else 0xFFF8F8FB
                viewModel.defaultAppearance = viewModel.defaultAppearance.copy(backgroundArgb = background)
                viewModel.tabs.forEach { tab -> tab.appearance = tab.appearance.copy(backgroundArgb = background) }
            }
        }
        if (sv.language != language && sv.language != "auto") applyLanguage(sv.language)
    }
    val localizedConfiguration = remember(language) {
        Configuration(activity.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(if (language == "zh") "zh-CN" else "en"))
        }
    }
    val localizedContext = remember(language) { activity.createConfigurationContext(localizedConfiguration) }
    // Per v0.8.26: show a loading overlay on language-switch restart.
    var languageSwitching by remember {
        mutableStateOf(preferences.getBoolean("pending_language_restart", false))
    }
    LaunchedEffect(languageSwitching) {
        if (languageSwitching) {
            kotlinx.coroutines.delay(500)
            preferences.edit().putBoolean("pending_language_restart", false).apply()
            languageSwitching = false
        }
    }
    // Per v0.5.2a: load the persisted global appearance once at startup (falls back to defaults).
    LaunchedEffect(Unit) {
        preferences.getString(AppearanceStore.KEY, null)?.let { json ->
            AppearanceStore.fromJson(json)?.let { ap -> viewModel.applyAppearance(ap) }
        }
    }
    fun applyViewerBackground(dark: Boolean) {
        if (!backgroundFollowTheme) return
        val background = if (dark) 0xFF101014 else 0xFFF8F8FB
        viewModel.defaultAppearance = viewModel.defaultAppearance.copy(backgroundArgb = background)
        viewModel.tabs.forEach { tab -> tab.appearance = tab.appearance.copy(backgroundArgb = background) }
    }
    fun applyBackgroundFollowTheme(value: Boolean) {
        backgroundFollowTheme = value
        preferences.edit().putBoolean("bg_follow_theme", value).apply()
        if (value) {
            val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDark
            applyViewerBackground(dark)
        }
    }
    // Per v0.5.2a: persist + globally apply a new appearance (default + every open tab).
    fun applyViewerAppearance(ap: ViewerAppearance) {
        viewModel.applyAppearance(ap)
        preferences.edit().putString(AppearanceStore.KEY, AppearanceStore.run { ap.toJson() }).apply()
    }
    // Per v0.7.1: easter egg — rapid theme switching (>10 in 5s) unlocks secret MSAA export.
    // Declared before applyTheme so the function can reference them.
    var themeSwitchTimestamps by remember { mutableStateOf<List<Long>>(emptyList()) }
    var secretUnlockTrigger by remember { mutableStateOf(0) }
    fun applyTheme(mode: ThemeMode) {
        themeMode = mode
        preferences.edit().putString("theme", mode.name).apply()
        applyViewerBackground(mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark)
        // Per v0.7.1: easter egg — track rapid theme switches (>10 in 5s unlocks secret export).
        val now = System.currentTimeMillis()
        themeSwitchTimestamps = (themeSwitchTimestamps + now).filter { it > now - 5000 }
        if (themeSwitchTimestamps.size > 10) {
            secretUnlockTrigger++
            themeSwitchTimestamps = emptyList()
        }
    }
    LaunchedEffect(themeMode, systemDark) {
        if (themeMode == ThemeMode.SYSTEM) applyViewerBackground(systemDark)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Per v0.5.0: global "计算中..." overlay shown while bond rules are recomputed (open file, add/
    // delete atom, transform, hex/rhom conversion) off the UI thread.
    // Per v0.8.1: dialog gate states are MutableState references so KrystalsRootDialogs (extracted
    // below) owns their reads — toggling one of these dialogs no longer recomposes the KrystalsRoot
    // scaffold. States only ever set by KrystalsRoot internals (pendingSaveTabId, pendingExportBitmap,
    // helpOpen, sponsorLaunchCount, aboutOpen, updateChecking) stay as plain delegated booleans.
    val computingState = remember { mutableStateOf(false) }
    var computing by computingState
    val computationJobState = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var computationJob by computationJobState
    val voronoiWarningOpenState = remember { mutableStateOf(false) }
    var voronoiWarningOpen by voronoiWarningOpenState
    val cifWarningOpenState = remember { mutableStateOf(false) }
    var cifWarningOpen by cifWarningOpenState
    val pendingOpenState = remember { mutableStateOf<PendingOpen?>(null) }
    var pendingOpen by pendingOpenState
    var pendingSaveTabId by remember { mutableStateOf<String?>(null) }
    val closeRequestState = remember { mutableStateOf<Int?>(null) }
    var closeRequest by closeRequestState
    val exitRequestState = remember { mutableStateOf(false) }
    var exitRequest by exitRequestState
    var pendingExportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    val sponsorOpenState = remember { mutableStateOf(false) }
    var sponsorOpen by sponsorOpenState
    // Per v0.6.5: unified confirmation dialog for help/feedback/sponsor links.
    val linkConfirmUrlState = remember { mutableStateOf<String?>(null) }
    var linkConfirmUrl by linkConfirmUrlState
    var sponsorLaunchCount by remember { mutableStateOf(0) }
    var aboutOpen by remember { mutableStateOf(false) }
    // Per v0.6.5: automatic update check on startup.
    val updateInfoState = remember { mutableStateOf<UpdateInfo?>(null) }
    var updateInfo by updateInfoState
    val updateDialogOpenState = remember { mutableStateOf(false) }
    var updateDialogOpen by updateDialogOpenState
    var updateChecking by remember { mutableStateOf(false) }
    val updateDownloadProgressState = remember { mutableStateOf<Float?>(null) } // null = not downloading
    var updateDownloadProgress by updateDownloadProgressState
    val updateScope = rememberCoroutineScope()
    // Per v0.2.2: prompt for sponsorship on the 5th, 20th, 50th, and every 50th launch thereafter.
    // Per v0.4.0: once the device holds a valid activation code the automatic prompt is suppressed.
    LaunchedEffect(Unit) {
        val count = preferences.getInt("launch_count", 0) + 1
        preferences.edit().putInt("launch_count", count).apply()
        val prompt = count == 5 || count == 20 || count == 50 || (count > 50 && count % 50 == 0)
        if (prompt && !ActivationManager.isActivated(activity)) { sponsorLaunchCount = count; sponsorOpen = true }
    }
    // Per v0.6.5: check for updates on startup.
    // Per v0.8.25: at most once per 24h — the timestamp is written before the request so a
    // failed check (offline etc.) still counts and isn't retried on every cold start.
    // Per v0.8.26: skip update check when autoCheckUpdate is disabled.
    LaunchedEffect(Unit) {
        if (!settingsValues.autoCheckUpdate) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val lastChecked = preferences.getLong("last_update_check_ms", 0L)
        if (now - lastChecked < UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        preferences.edit().putLong("last_update_check_ms", now).apply()
        updateScope.launch {
            val info = fetchUpdateInfo()
            if (info != null && info.versionCode > com.krystals.app.BuildConfig.VERSION_CODE) {
                val skipped = preferences.getInt("skipped_version_code", -1)
                if (info.versionCode > skipped) {
                    updateInfo = info
                    updateDialogOpen = true
                }
            }
        }
    }
    val presetOpenState = remember { mutableStateOf(false) }
    var presetOpen by presetOpenState
    val mpSearchOpenState = remember { mutableStateOf(false) }
    var mpSearchOpen by mpSearchOpenState
    val mpKeyDialogOpenState = remember { mutableStateOf(false) }
    var mpKeyDialogOpen by mpKeyDialogOpenState
    // Per v0.4.0: activation-code dialog (reached from the sponsor dialog) and the MP "premium
    // content" gate shown when MP is picked without an active code.
    val activationOpenState = remember { mutableStateOf(false) }
    var activationOpen by activationOpenState
    val mpPremiumOpenState = remember { mutableStateOf(false) }
    var mpPremiumOpen by mpPremiumOpenState
    // Per v0.4.0: one-time caution shown to activated users before the MP flow, explaining the
    // new/legacy API trade-off. Dismissable permanently via the "不再显示" checkbox.
    val mpCautionOpenState = remember { mutableStateOf(false) }
    var mpCautionOpen by mpCautionOpenState
    // Per v0.3.1: MP and COD imports share an "import from online sources" entry that opens a
    // picker; the picker routes to the COD search screen (no key) or the MP flow (key-gated).
    val onlineSourceOpenState = remember { mutableStateOf(false) }
    var onlineSourceOpen by onlineSourceOpenState
    val codSearchOpenState = remember { mutableStateOf(false) }
    var codSearchOpen by codSearchOpenState

    fun showMessage(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    // Per v0.5.2b: resolved string for the smart-ionic timeout snackbar (localized() is @Composable).
    val smartIonicTimeoutMessage = localized("智能离子计算超时，已回退键合半径", "Smart ionic timed out, fell back to bonding radii")
    val voronoiWarningMessage = localized(
        "周期 Voronoi 搜索范围过大，继续计算可能耗尽内存。请调整晶胞参数或改用键合半径。",
        "The periodic Voronoi search is too large and may exhaust memory. Adjust the cell or use bonding radii.",
    )

    // Per v0.5.3b: when an opened cell expands to more than [LARGE_CELL_WARN_THRESHOLD] atoms the
    // user is warned before the (possibly degraded) scene is built. Resolved once; reused below.
    val largeCellWarningMessage = localized("原子数较多", "Many atoms")
    val pendingLargeOpenState = remember { mutableStateOf<PendingLargeOpen?>(null) }
    var pendingLargeOpen by pendingLargeOpenState
    val pendingLargeEditState = remember { mutableStateOf<PendingLargeEdit?>(null) }
    var pendingLargeEdit by pendingLargeEditState

    /**
     * Per v0.5.0: run a bond-recomputing operation off the UI thread with the global "计算中..."
     * overlay. [block] runs on Dispatchers.Default and returns the new structure (or null to abort
     * silently, e.g. on validation failure where the caller already reported the error).
     */
    fun runWithBondComputation(block: suspend () -> EditResult?, skipLargeCheck: Boolean = false) {
        if (computing) return
        if (!skipLargeCheck) {
            val tab = viewModel.current
            if (tab != null) {
                val estimate = SymmetryExpander.expand(tab.structure).size
                if (estimate > LARGE_CELL_WARN_THRESHOLD) {
                    pendingLargeEdit = PendingLargeEdit(block, estimate)
                    return
                }
            }
        }
        computing = true
        computationJob = scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.Default) { block() })
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                Result.failure(ce)
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                computing = false
                computationJob = null
            }
            result.getOrNull()?.let { editResult ->
                viewModel.current?.let { viewModel.updateAnalysis(it, editResult) }
            }
            result.onFailure { error ->
                if (error is VoronoiSearchLimitExceededException) voronoiWarningOpen = true
                else if (error !is kotlin.coroutines.cancellation.CancellationException) showMessage(error.message ?: "Operation failed")
            }
        }
    }

    /**
     * Per v0.5.2b: open-file bond computation with a 5 s smart-ionic timeout. Falls back to bonding
     * radii on timeout and surfaces the [smartIonicTimeoutMessage] snackbar.
     * Per v0.6.1: cooperative cancellation, fast size-guarded fallback, and bonding-radius rules
     * applied if the user cancels mid-computation so the Chemical Bonds panel is never empty.
     */
    fun openWithBondComputation(
        structure: CrystalStructure,
        bondConfiguration: com.krystals.crystal.analysis.bonding.BondConfiguration,
        epsilon: Double,
        expandedSize: Int,
    ) {
        if (computing) return
        val targetTab = viewModel.current ?: return
        computing = true
        computationJob = scope.launch {
            val result = try {
                Result.success(
                    withContext(Dispatchers.Default) {
                        // Per v0.8.36: expandedSize comes from the open path (openParsed already
                        // expanded for the large-cell check) — re-expanding here doubled the
                        // expansion cost on big cells.
                        // Per v0.8.26: dispatch by user-selected bond-rule mode.
                        when (settingsValues.bondRuleMode) {
                            BondRuleMode.AUTO -> {
                                if (CrystalEditor.isAllNonMetals(structure) || expandedSize > BondValence.SMART_IONIC_ATOM_LIMIT) {
                                    CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, null)
                                } else {
                                    val smartIonic = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                                        runCatching { BondValence.smartIonicRules(structure, bondConfiguration, epsilon) }.getOrNull()
                                    }
                                    CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, smartIonic)
                                }
                            }
                            BondRuleMode.SMART_IONIC -> {
                                val smartIonic = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                                    runCatching { BondValence.smartIonicRules(structure, bondConfiguration, epsilon) }.getOrNull()
                                }
                                CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, smartIonic)
                            }
                            BondRuleMode.BONDING -> CrystalEditor.rebuildBondRules(structure, bondConfiguration, RadiusSource.BONDING, epsilon)
                            BondRuleMode.VDW -> CrystalEditor.rebuildBondRules(structure, bondConfiguration, RadiusSource.VDW, epsilon)
                        }
                    }
                )
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                val fallback = if (targetTab in viewModel.tabs) {
                    CrystalEditor.rebuildBondRules(
                        targetTab.structure,
                        targetTab.bondConfiguration,
                        RadiusSource.BONDING,
                        targetTab.bondEpsilon,
                    )
                } else null
                fallback?.let { Result.success(it) } ?: Result.failure(ce)
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                computing = false
                computationJob = null
            }
            result.onSuccess { editResult ->
                // Per v0.8.35: apply the user's default cross-cell bond-extension preference to
                // every generated rule on open — ALL extends both directions, METALS_ONLY extends
                // only the metal atom's direction, NEVER extends nothing. (Polyhedra defaults
                // already apply per-site in doOpenParsed.)
                val extended = editResult.copy(
                    bondConfiguration = editResult.bondConfiguration.copy(
                        rules = CrystalEditor.applyExtendPreference(
                            editResult.structure,
                            editResult.bondConfiguration.rules,
                            when (settingsValues.defaultExtendBonds) {
                                ExtendBondsDefault.ALL -> CrystalEditor.ExtendBondDefaultMode.ALL
                                ExtendBondsDefault.METALS_ONLY -> CrystalEditor.ExtendBondDefaultMode.METALS_ONLY
                                ExtendBondsDefault.NEVER -> CrystalEditor.ExtendBondDefaultMode.NEVER
                            },
                        ),
                    ),
                )
                if (CrystalEditor.SMART_IONIC_TIMEOUT in extended.warnings) showMessage(smartIonicTimeoutMessage)
                if (targetTab in viewModel.tabs) viewModel.updateAnalysis(targetTab, extended)
            }.onFailure { error ->
                if (error is VoronoiSearchLimitExceededException) voronoiWarningOpen = true
                else if (error !is kotlin.coroutines.cancellation.CancellationException) showMessage(error.message ?: "Operation failed")
            }
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
     *  because Kotlin local functions have no forward references.
     *  Per v0.5.2b: always synthesize bond rules, ignoring any rules carried in the CIF. */
    fun doOpenParsed(parsed: ParsedStructure, name: String, uri: Uri?, expandedEstimate: Int) {
        viewModel.add(parsed, name, uri)
        val tab = viewModel.current ?: return
        // Per v0.8.26: apply user preference defaults for the new tab.
        tab.visibility = tab.visibility.copy(showBonds = settingsValues.defaultShowBonds)
        // Default extend-bonds setting.
        tab.structuralExpansion = when (settingsValues.defaultExtendBonds) {
            ExtendBondsDefault.ALL -> true
            ExtendBondsDefault.METALS_ONLY -> parsed.structure.sites.any { isMetal(it.species.symbol) }
            ExtendBondsDefault.NEVER -> false
        }
        // Default polyhedra visibility.
        val siteIds = parsed.structure.sites.map { it.id }.toSet()
        tab.visibility = tab.visibility.copy(polyhedronSites = when (settingsValues.defaultPolyhedra) {
            PolyhedraDefault.ALL -> siteIds
            PolyhedraDefault.METALS_ONLY -> parsed.structure.sites.filter { isMetal(it.species.symbol) }.map { it.id }.toSet()
            PolyhedraDefault.NEVER -> emptySet()
        })
        // Per v0.7.0: extract user comments from CIF source.
        tab.comments = CifComments.extract(parsed.document.source)
        // Per v0.8.26: skip bond computation when the user disables auto-bond-rules.
        // Per v0.8.36: pass the already-computed expansion size (openParsed expanded for the
        // large-cell gate) so the computation path does not re-expand the cell.
        if (settingsValues.autoBondRules) openWithBondComputation(tab.structure, tab.bondConfiguration, tab.bondEpsilon, expandedEstimate)
    }

    /** Per v0.5.0: add a parsed structure, synthesizing bond rules off-UI with the computing overlay.
     *  Defined before [loadUri] because local functions must be declared before use (no forward refs).
     *  Per v0.5.3b: cells expanding past [LARGE_CELL_WARN_THRESHOLD] atoms are gated behind a
     *  confirm dialog; the user can still open them in degraded mode. */
    fun openParsed(parsed: ParsedStructure, name: String, uri: Uri?) {
        // Per v0.6.3: move SymmetryExpander.expand() off the main thread — it was the bottleneck
        // that made opening a CIF freeze the UI before the viewer appeared.
        scope.launch {
            val expandedEstimate = withContext(Dispatchers.Default) { SymmetryExpander.expand(parsed.structure).size }
            if (expandedEstimate > LARGE_CELL_WARN_THRESHOLD) {
                pendingLargeOpen = PendingLargeOpen(parsed, name, uri, expandedEstimate)
                return@launch
            }
            doOpenParsed(parsed, name, uri, expandedEstimate)
        }
    }

    fun loadUri(uri: Uri) {
        scope.launch {
            val result = runCatching {
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
            }
            val pending = result.getOrNull()
            if (pending != null) {
                if (pending.candidates.size == 1) {
                    // Per v0.6.4: parseStructure can be heavy (resolves space groups, creates
                    // symmetry operations) — run on Dispatchers.Default to avoid blocking the UI.
                    val parsed = withContext(Dispatchers.Default) { CifCodec.parseStructure(pending.text, pending.candidates.first(), autoConvertConventional = settingsValues.autoConvertCell) }
                    openParsed(parsed, pending.name, pending.uri)
                } else pendingOpen = pending
            } else {
                val error = result.exceptionOrNull()
                if (error != null && error !is CancellationException) {
                    if (error is com.krystals.crystal.core.CifParseException) cifWarningOpen = true
                    else showMessage(error.message ?: "Unable to open CIF")
                }
            }
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
                    // Per v0.7.0: inject user comments into CIF before writing.
                    val contentWithComments = CifComments.inject(content, tab.comments)
                    withContext(Dispatchers.IO) { FileRepository.write(activity.contentResolver, uri, contentWithComments) }
                    tab.uri = uri; tab.isNew = false; tab.dirty = false; tab.savedName = tab.name
                    tab.parsed = CifCodec.parseStructure(contentWithComments, tab.parsed.blockIndex, autoConvertConventional = settingsValues.autoConvertCell)
                }.onSuccess {
                    showMessage("Saved ${tab.name}")
                }.onFailure { if (it !is CancellationException) showMessage(it.message ?: "Save failed") }
            }
        }
    }

    fun save(tab: DocumentTab, afterSave: () -> Unit = {}) {
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
                // Per v0.7.0: inject user comments into CIF before writing.
                val contentWithComments = CifComments.inject(content, tab.comments)
                withContext(Dispatchers.IO) { FileRepository.write(activity.contentResolver, uri, contentWithComments) }
                tab.parsed = CifCodec.parseStructure(contentWithComments, tab.parsed.blockIndex, autoConvertConventional = settingsValues.autoConvertCell)
                tab.dirty = false
            }.onSuccess { showMessage("Saved ${tab.name}"); afterSave() }.onFailure { if (it !is CancellationException) showMessage(it.message ?: "Save failed") }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bitmap = pendingExportBitmap
        pendingExportBitmap = null
        if (granted && bitmap != null) scope.launch { runCatching { FileRepository.exportPng(activity.contentResolver, bitmap) }.onSuccess { showMessage("Exported to Pictures/Krystals") }.onFailure { if (it !is CancellationException) showMessage(it.message ?: "Export failed") } }
        else showMessage("Storage permission is required on Android 8–9")
    }
    fun requestExport(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExportBitmap = bitmap
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else scope.launch {
            runCatching { FileRepository.exportPng(activity.contentResolver, bitmap) }
                .onSuccess { showMessage("Exported to Pictures/Krystals") }
                .onFailure { if (it !is CancellationException) showMessage(it.message ?: "Export failed") }
        }
    }

    LaunchedEffect(incomingUri) {
        incomingUri?.let { loadUri(it); consumeIncomingUri() }
    }

    fun requestExit() {
        if (viewModel.tabs.any { it.dirty }) exitRequest = true else activity.finishAndRemoveTask()
    }
    // Per v0.8.1: every window dialog (AlertDialog/Dialog) handles its own back press inside its own
    // Window, so its KrystalsRoot entry was dead code. Only the inline overlay screens (COD/MP search,
    // help/sponsor) and the tab stack remain in this handler.
    BackHandler(enabled = true) {
        when {
            codSearchOpen -> codSearchOpen = false
            mpSearchOpen -> mpSearchOpen = false
            helpOpen -> helpOpen = false
            sponsorOpen -> sponsorOpen = false
            viewModel.current != null -> { val index = viewModel.selectedIndex; if (viewModel.current?.dirty == true) closeRequest = index else viewModel.close(index) }
            else -> activity.finishAndRemoveTask()
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
    ) {
    KrystalsTheme(themeMode) {
        // Per v0.7.0: set contentWindowInsets to zero so overlay panels (EditorPanel,
        // DisplayPanel) get full-screen space. The TopAppBar handles statusBars padding;
        // navigationBars padding is applied per-screen where needed. This avoids the
        // double-padding (Scaffold contentWindowInsets + TopAppBar windowInsets) that
        // compressed dialog/panel heights on edge-to-edge devices.
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.navigationBars)) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                // Per v0.8.26: language switching overlay.
                if (languageSwitching) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(localized("切换中...", "Switching..."))
                        }
                    }
                }
                if (viewModel.tabs.isEmpty()) {
                    HomeScreen(
                        onOpen = { openLauncher.launch(arrayOf("chemical/x-cif", "text/plain", "application/octet-stream")) },
                        onOpenPreset = { presetOpen = true },
                        onNew = viewModel::createNew,
                        onOnlineSource = { onlineSourceOpen = true },
                        themeMode = themeMode, onTheme = ::applyTheme, language = language,
                        onLanguage = ::applyLanguage,
                        onHelp = { linkConfirmUrl = "https://www.kelesss.art/refs/software/krystals.html" }, onAbout = { aboutOpen = true }, onSponsor = { linkConfirmUrl = "https://ifdian.net/a/krystals/plan" }, onFeedback = { linkConfirmUrl = "https://github.com/SUPERkelesss/Krystals/issues" }, onExit = ::requestExit,
                    )
                } else {
                    ViewerScreen(
                        viewModel = viewModel,
                        preferences = preferences,
                        settingsValues = settingsValues,
                        onSettingsChange = onSettingsChange,
                        onRestoreDefaults = {
                            val confirm = true // Auto-confirmed; can add dialog later
                            PreferencesStore.clearAll(preferences)
                            val defaults = SettingsValues.defaults()
                            settingsValues = defaults
                            PreferencesStore.save(preferences, defaults)
                            if (language != defaults.language && defaults.language != "auto") applyLanguage(defaults.language)
                            if (themeMode != defaults.theme) { themeMode = defaults.theme; preferences.edit().putString("theme", defaults.theme.name).apply() }
                        },
                        onSave = { save(it) },
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
                                    tab.comments,
                                )
                                showMessage("Saved to presets")
                            }.onFailure { showMessage(it.message ?: "Save failed") }
                        },
                        onNew = viewModel::createNew,
                        onOnlineSource = { onlineSourceOpen = true },
                        onExport = ::requestExport,
                        onExit = ::requestExit,
                        onClose = { index -> if (viewModel.tabs[index].dirty) closeRequest = index else viewModel.close(index) },
                        onBackCloseCurrent = {
                            when {
                                pendingOpen != null -> pendingOpen = null
                                pendingLargeOpen != null -> pendingLargeOpen = null
                                pendingLargeEdit != null -> pendingLargeEdit = null
                                cifWarningOpen -> cifWarningOpen = false
                                closeRequest != null -> closeRequest = null
                                exitRequest -> exitRequest = false
                                helpOpen -> helpOpen = false
                                sponsorOpen -> sponsorOpen = false
                                presetOpen -> presetOpen = false
                                onlineSourceOpen -> onlineSourceOpen = false
                                codSearchOpen -> codSearchOpen = false
                                mpSearchOpen -> mpSearchOpen = false
                                activationOpen -> activationOpen = false
                                mpKeyDialogOpen -> mpKeyDialogOpen = false
                                mpPremiumOpen -> mpPremiumOpen = false
                                mpCautionOpen -> mpCautionOpen = false
                                else -> { val index = viewModel.selectedIndex; if (viewModel.current?.dirty == true) closeRequest = index else viewModel.close(index) }
                            }
                        },
                        themeMode = themeMode,
                        onTheme = ::applyTheme,
                        language = language,
                        onLanguage = ::applyLanguage,
                        onMessage = ::showMessage,
                        onHelp = { linkConfirmUrl = "https://www.kelesss.art/refs/software/krystals.html" },
                        onAbout = { aboutOpen = true },
                        onSponsor = { linkConfirmUrl = "https://ifdian.net/a/krystals/plan" },
                        onFeedback = { linkConfirmUrl = "https://github.com/SUPERkelesss/Krystals/issues" },
                        onRunBondComputation = ::runWithBondComputation,
                        onApplyAppearance = ::applyViewerAppearance,
                        backgroundFollowTheme = backgroundFollowTheme,
                        onBackgroundFollowThemeChange = ::applyBackgroundFollowTheme,
                        secretUnlockTrigger = secretUnlockTrigger,
                    )
                }
            }
        }

        // Dialogs live inside KrystalsTheme so they pick up the correct color scheme (dark/light).
        KrystalsRootDialogs(
            computingState = computingState,
            computationJobState = computationJobState,
            voronoiWarningOpenState = voronoiWarningOpenState,
            cifWarningOpenState = cifWarningOpenState,
            pendingOpenState = pendingOpenState,
            pendingLargeOpenState = pendingLargeOpenState,
            pendingLargeEditState = pendingLargeEditState,
            closeRequestState = closeRequestState,
            exitRequestState = exitRequestState,
            linkConfirmUrlState = linkConfirmUrlState,
            updateInfoState = updateInfoState,
            updateDialogOpenState = updateDialogOpenState,
            updateDownloadProgressState = updateDownloadProgressState,
            presetOpenState = presetOpenState,
            mpKeyDialogOpenState = mpKeyDialogOpenState,
            activationOpenState = activationOpenState,
            mpPremiumOpenState = mpPremiumOpenState,
            mpCautionOpenState = mpCautionOpenState,
            onlineSourceOpenState = onlineSourceOpenState,
            mpSearchOpenState = mpSearchOpenState,
            codSearchOpenState = codSearchOpenState,
            sponsorOpenState = sponsorOpenState,
            autoConvertCell = settingsValues.autoConvertCell,
            viewModel = viewModel,
            activity = activity,
            preferences = preferences,
            updateScope = updateScope,
            showMessage = ::showMessage,
            openParsed = ::openParsed,
            doOpenParsed = ::doOpenParsed,
            runWithBondComputation = { block, skip -> runWithBondComputation(block, skip) },
            save = { tab, after -> save(tab, after) },
            openUrl = ::openUrl,
            proceedToMp = ::proceedToMp,
            voronoiWarningMessage = voronoiWarningMessage,
        )
    // Per v0.6.5: resolve localized strings for update dialogs in composable context.
    val msgSkipped = localized("已跳过该版本", "This version has been skipped")
    val msgLatest = localized("当前版本已是最新版", "Current version is up to date")
    val msgFailed = localized("无法连接至服务器", "Unable to connect to server")
    var aboutUpdateMessage by remember { mutableStateOf<String?>(null) }
    if (aboutOpen) {
        AboutScreen(
            onBack = { aboutOpen = false },
            onCheckUpdates = {
                updateChecking = true
                aboutUpdateMessage = null
                updateScope.launch {
                    val info = fetchUpdateInfo()
                    updateChecking = false
                    if (info != null && info.versionCode > com.krystals.app.BuildConfig.VERSION_CODE) {
                        val skipped = preferences.getInt("skipped_version_code", -1)
                        if (info.versionCode > skipped) {
                            updateInfo = info
                            updateDialogOpen = true
                        } else {
                            aboutUpdateMessage = msgSkipped
                        }
                    } else if (info != null) {
                        aboutUpdateMessage = msgLatest
                    } else {
                        aboutUpdateMessage = msgFailed
                    }
                }
            },
            isCheckingUpdates = updateChecking,
            updateMessage = aboutUpdateMessage,
        )
    }
    if (mpSearchOpen) MpSearchScreen(
        context = activity,
        viewModel = viewModel,
        autoConvertCell = settingsValues.autoConvertCell,
        onBack = { mpSearchOpen = false },
        onChangeKey = { mpSearchOpen = false; mpKeyDialogOpen = true },
        onMessage = ::showMessage,
        onOpenParsed = { parsed, name -> mpSearchOpen = false; openParsed(parsed, name, null) },
    )
    if (codSearchOpen) CodSearchScreen(
        context = activity,
        viewModel = viewModel,
        autoConvertCell = settingsValues.autoConvertCell,
        onBack = { codSearchOpen = false },
        onMessage = ::showMessage,
        onOpenParsed = { parsed, name -> codSearchOpen = false; openParsed(parsed, name, null) },
    )
    // Per v0.8.26: apply the user's COD mirror preference whenever the COD panel opens.
    LaunchedEffect(codSearchOpen) {
        if (codSearchOpen) {
            CrystallographyOpenDatabase.setMirrorMode(
                mode = settingsValues.codMirrorMode,
                customUrl = settingsValues.codCustomUrl,
                fixedIndex = settingsValues.codFixedIndex,
            )
        }
    }
    // Per v0.8.35: the preset library is a full-screen page; system back closes it.
    // Registered after ViewerScreen's handler so it wins while the page is open.
    BackHandler(enabled = presetOpen) { presetOpen = false }
    }
    }
}

/**
 * Per v0.8.1: all dialogs that live in their own Window (AlertDialog / Dialog). Extracted from
 * KrystalsRoot so toggling one only recomposes this composable. Each Window dialog consumes back
 * presses inside its own Window (the old KrystalsRoot BackHandler entries were dead code). Inline
 * overlay screens (About, COD/MP search) stay in KrystalsRoot because their back handling is
 * conditional; the states they share are passed here as MutableState references.
 */
@Composable
private fun KrystalsRootDialogs(
    computingState: MutableState<Boolean>,
    computationJobState: MutableState<kotlinx.coroutines.Job?>,
    voronoiWarningOpenState: MutableState<Boolean>,
    cifWarningOpenState: MutableState<Boolean>,
    pendingOpenState: MutableState<PendingOpen?>,
    pendingLargeOpenState: MutableState<PendingLargeOpen?>,
    pendingLargeEditState: MutableState<PendingLargeEdit?>,
    closeRequestState: MutableState<Int?>,
    exitRequestState: MutableState<Boolean>,
    linkConfirmUrlState: MutableState<String?>,
    updateInfoState: MutableState<UpdateInfo?>,
    updateDialogOpenState: MutableState<Boolean>,
    updateDownloadProgressState: MutableState<Float?>,
    presetOpenState: MutableState<Boolean>,
    mpKeyDialogOpenState: MutableState<Boolean>,
    activationOpenState: MutableState<Boolean>,
    mpPremiumOpenState: MutableState<Boolean>,
    mpCautionOpenState: MutableState<Boolean>,
    onlineSourceOpenState: MutableState<Boolean>,
    mpSearchOpenState: MutableState<Boolean>,
    codSearchOpenState: MutableState<Boolean>,
    sponsorOpenState: MutableState<Boolean>,
    autoConvertCell: Boolean,
    viewModel: KrystalsViewModel,
    activity: MainActivity,
    preferences: SharedPreferences,
    updateScope: CoroutineScope,
    showMessage: (String) -> Unit,
    openParsed: (ParsedStructure, String, Uri?) -> Unit,
    doOpenParsed: (ParsedStructure, String, Uri?, Int) -> Unit,
    runWithBondComputation: (suspend () -> com.krystals.crystal.analysis.editing.EditResult?, Boolean) -> Unit,
    save: (DocumentTab, () -> Unit) -> Unit,
    openUrl: (String) -> Unit,
    proceedToMp: () -> Unit,
    voronoiWarningMessage: String,
) {
    var computing by computingState
    var computationJob by computationJobState
    var voronoiWarningOpen by voronoiWarningOpenState
    var cifWarningOpen by cifWarningOpenState
    var pendingOpen by pendingOpenState
    var pendingLargeOpen by pendingLargeOpenState
    var pendingLargeEdit by pendingLargeEditState
    var closeRequest by closeRequestState
    var exitRequest by exitRequestState
    var linkConfirmUrl by linkConfirmUrlState
    var updateInfo by updateInfoState
    var updateDialogOpen by updateDialogOpenState
    var updateDownloadProgress by updateDownloadProgressState
    var presetOpen by presetOpenState
    var mpKeyDialogOpen by mpKeyDialogOpenState
    var activationOpen by activationOpenState
    var mpPremiumOpen by mpPremiumOpenState
    var mpCautionOpen by mpCautionOpenState
    var onlineSourceOpen by onlineSourceOpenState
    var mpSearchOpen by mpSearchOpenState
    var codSearchOpen by codSearchOpenState
    var sponsorOpen by sponsorOpenState

    if (computing) {
        androidx.compose.material3.BasicAlertDialog(
            onDismissRequest = {
                computationJob?.cancel()
                computing = false
                computationJob = null
                // Per v0.7.1: undo the modification that triggered the computation.
                viewModel.current?.undo()
            },
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
    if (voronoiWarningOpen) {
        AlertDialog(
            onDismissRequest = { voronoiWarningOpen = false },
            title = { Text(localized("计算已停止", "Calculation stopped")) },
            text = { Text(voronoiWarningMessage) },
            confirmButton = {
                TextButton(onClick = { voronoiWarningOpen = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
    if (cifWarningOpen) {
        AlertDialog(
            onDismissRequest = { cifWarningOpen = false },
            title = { Text(localized("警告", "Warning")) },
            text = { Text(localized("当前所打开的CIF文件不可读或包含异常字符串。", "The CIF file is unreadable or contains malformed strings.")) },
            confirmButton = {
                TextButton(onClick = { cifWarningOpen = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
    pendingOpen?.let { pending ->
        val document = remember(pending) { CifCodec.parse(pending.text) }
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text(localized("选择结构", "Select structure")) },
            text = { Column { pending.candidates.forEach { index -> TextButton(onClick = {
                runCatching { CifCodec.parseStructure(pending.text, index, autoConvertConventional = true) }
                    .onSuccess { parsed -> pendingOpen = null; openParsed(parsed, pending.name, pending.uri) }
                    .onFailure { if (it is com.krystals.crystal.core.CifParseException) { pendingOpen = null; cifWarningOpen = true } else showMessage(it.message ?: "Unable to open CIF") }
            }) { Text(document.blocks[index].name) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingOpen = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    pendingLargeOpen?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingLargeOpen = null },
            title = { Text(localized("警告！", "Warning!")) },
            text = { Text(localized(
                "将要打开的CIF文件包括过多的原子（约 ${pending.expandedEstimate} 个），可能导致软件异常卡顿和崩溃。确认继续吗？",
                "This CIF contains too many atoms (~${pending.expandedEstimate}), which may cause severe lag or crashes. Continue?")) },
            confirmButton = { TextButton(onClick = {
                val p = pending; pendingLargeOpen = null
                doOpenParsed(p.parsed, p.name, p.uri, p.expandedEstimate)
            }) { Text(localized("继续", "Continue")) } },
            dismissButton = { TextButton(onClick = { pendingLargeOpen = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    pendingLargeEdit?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingLargeEdit = null },
            title = { Text(localized("警告！", "Warning!")) },
            text = { Text(localized(
                "修改后晶胞将包含过多原子（约 ${pending.expandedEstimate} 个），可能导致软件异常卡顿和崩溃。确认继续吗？",
                "After modification the cell will have too many atoms (~${pending.expandedEstimate}), which may cause severe lag or crashes. Continue?")) },
            confirmButton = { TextButton(onClick = {
                val b = pending.block; pendingLargeEdit = null
                runWithBondComputation(b, true)
            }) { Text(localized("继续", "Continue")) } },
            dismissButton = { TextButton(onClick = { pendingLargeEdit = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    closeRequest?.let { index ->
        val tab = viewModel.tabs.getOrNull(index)
        if (tab != null) AlertDialog(
            onDismissRequest = { closeRequest = null },
            title = { Text(localized("保存修改？", "Save changes?")) },
            text = { Text(tab.name) },
            confirmButton = {
                // Per v0.7.0: two-row layout — Row 1: Save | Save to Presets; Row 2: Discard | Cancel.
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        TextButton(onClick = { save(tab) { viewModel.close(index); closeRequest = null } }) { Text(stringResource(R.string.save)) }
                        TextButton(onClick = {
                            runCatching {
                                PresetRepository.saveToPreset(
                                    activity, tab.parsed, tab.structure, tab.bondConfiguration,
                                    tab.renderConfiguration.toCifDisplayMetadata(), tab.name, tab.comments,
                                )
                            }.onSuccess { showMessage("Saved to presets"); viewModel.close(index); closeRequest = null }
                             .onFailure { showMessage(it.message ?: "Save failed") }
                        }) { Text(stringResource(R.string.save_to_presets)) }
                    }
                    Row {
                        TextButton(onClick = { viewModel.close(index); closeRequest = null }) { Text(stringResource(R.string.discard)) }
                        TextButton(onClick = { closeRequest = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            },
        ) else closeRequest = null
    }
    if (exitRequest) AlertDialog(
        onDismissRequest = { exitRequest = false },
        title = { Text(localized("文件尚未保存", "Unsaved files")) },
        text = { Text(viewModel.tabs.filter { it.dirty }.joinToString("\n") { "• ${it.name}" }) },
        confirmButton = { TextButton(onClick = { viewModel.current?.let { save(it) {} }; exitRequest = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { Row { TextButton(onClick = { activity.finishAndRemoveTask() }) { Text(stringResource(R.string.discard)) }; TextButton(onClick = { exitRequest = false }) { Text(stringResource(R.string.cancel)) } } },
    )
    // Per v0.6.5: unified link-confirmation dialog for help/feedback/sponsor.
    if (linkConfirmUrl != null) {
        val url = linkConfirmUrl!!
        AlertDialog(
            onDismissRequest = { linkConfirmUrl = null },
            title = { Text(localized("注意", "Caution")) },
            text = { Text(localized("将在浏览器中打开链接页面：", "This will open the following link in your browser:") + "\n" + url) },
            confirmButton = {
                Button(
                    onClick = { val u = url; linkConfirmUrl = null; openUrl(u) },
                    shape = RoundedCornerShape(12.dp),
                ) { Text(localized("前往", "Go")) }
            },
            dismissButton = {
                TextButton(onClick = { linkConfirmUrl = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    // Per v0.6.5: version update dialog.
    val msgStartDownload = localized("开始下载新版本...", "Starting download...")
    val msgDownloadFail = localized("下载失败", "Download failed")
    if (updateDialogOpen && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = { updateDialogOpen = false },
            title = { Text(localized("版本更新", "Version Update")) },
            text = {
                Column {
                    Text(localized("检测到有新版本: v${info.versionName}。是否更新？", "A new version is available: v${info.versionName}. Update now?"))
                    if (updateDownloadProgress != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(localized("下载中... ${(updateDownloadProgress!! * 100).toInt()}%", "Downloading... ${(updateDownloadProgress!! * 100).toInt()}%"))
                    }
                }
            },
            confirmButton = {
                if (updateDownloadProgress == null) {
                    Button(
                        onClick = {
                            updateScope.launch {
                                updateDownloadProgress = 0f
                                showMessage(msgStartDownload)
                                val success = downloadAndInstallApk(activity, info.packageName) { progress ->
                                    updateDownloadProgress = progress
                                }
                                if (!success) {
                                    showMessage(msgDownloadFail)
                                    updateDownloadProgress = null
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(localized("立即更新", "Update Now")) }
                }
            },
            dismissButton = {
                if (updateDownloadProgress == null) {
                    Row {
                        TextButton(onClick = {
                            preferences.edit().putInt("skipped_version_code", info.versionCode).apply()
                            updateDialogOpen = false
                        }) { Text(localized("跳过该版本", "Skip This Version")) }
                        TextButton(onClick = { updateDialogOpen = false }) { Text(localized("暂不更新", "Update Later")) }
                    }
                }
            },
        )
    }
    if (presetOpen) PresetLibraryScreen(
            context = activity,
            viewModel = viewModel,
            preferences = preferences,
            autoConvertCell = autoConvertCell,
            onDismiss = { presetOpen = false },
            onMessage = { showMessage(it) },
            onOpenParsed = { parsed, name -> openParsed(parsed, name, null) },
        )
    if (mpKeyDialogOpen) MpApiKeyDialog(
        context = activity,
        onDismiss = { mpKeyDialogOpen = false },
        onOpenUrl = { openUrl(it) },
        onConfirmed = { mpKeyDialogOpen = false; mpSearchOpen = true },
        onMessage = { showMessage(it) },
    )
    if (activationOpen) ActivationDialog(
        context = activity,
        onDismiss = { activationOpen = false },
        onConfirmed = { activationOpen = false },
        onMessage = { showMessage(it) },
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
}

@Composable
private fun HomeScreen(
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onNew: () -> Unit,
    onOnlineSource: () -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onSponsor: () -> Unit,
    onFeedback: () -> Unit,
    onExit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Box(Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.import_local)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { menuOpen = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.open_preset_library)) }, leadingIcon = { Icon(Icons.Default.Inventory2, null) }, onClick = { menuOpen = false; onOpenPreset() })
                DropdownMenuItem(text = { Text(stringResource(R.string.import_online)) }, leadingIcon = { Icon(Icons.Default.CloudDownload, null) }, onClick = { menuOpen = false; onOnlineSource() })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Default.AddCircle, null) }, onClick = { menuOpen = false; onNew() })
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                    TextButton(onClick = { menuOpen = false; onHelp() }) { Text(stringResource(R.string.help), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { menuOpen = false; onFeedback() }) { Text(stringResource(R.string.feedback), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                    TextButton(onClick = { menuOpen = false; onAbout() }) { Text(stringResource(R.string.about), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { menuOpen = false; onSponsor() }) { Text(stringResource(R.string.sponsor), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(localized("关闭所有文件并退出", "Close all files and exit")) }, onClick = { menuOpen = false; onExit() })
            }
        }
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
                Button(onClick = onOnlineSource, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.import_online)) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNew, modifier = buttonWidth.height(52.dp)) { Icon(Icons.Default.AddCircle, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.new_file)) }
            }
        }
    }
}

/** Per v0.8.1: mutually-exclusive full-screen viewer panels. Consolidates five independent
 *  booleans (align/measure/display/info/appearance) into one state so only a single write is
 *  needed per panel switch. menuOpen (dropdown) and toolOpen (floating-ball fan) stay as plain
 *  booleans because they can be open simultaneously with a full-screen panel. */
private sealed class ViewerPanel {
    object None : ViewerPanel()
    object Align : ViewerPanel()
    object Measure : ViewerPanel()
    object Display : ViewerPanel()
    object Info : ViewerPanel()
    object Appearance : ViewerPanel()
    object Settings : ViewerPanel()
}

@Composable
private fun ViewerScreen(
    viewModel: KrystalsViewModel,
    preferences: SharedPreferences,
    onSave: (DocumentTab) -> Unit,
    onOpen: () -> Unit,
    onOpenPreset: () -> Unit,
    onSaveToPreset: () -> Unit,
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
    secretUnlockTrigger: Int = 0,
    settingsValues: SettingsValues = SettingsValues.defaults(),
    onSettingsChange: (SettingsValues) -> Unit = {},
    onRestoreDefaults: () -> Unit = {},
) {
    val tab = viewModel.current ?: return
    val scope = rememberCoroutineScope()
    // Per v0.6.3: export-image loading dialog with back-button cancel.
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Secret: retained for renderer MSAA export path; entry point removed per user request.
    var secretUnlocked by remember { mutableStateOf(false) }
    // Per v0.7.1: easter egg — secretUnlockTrigger increments when user switches theme >10 times in 5s.
    LaunchedEffect(secretUnlockTrigger) {
        if (secretUnlockTrigger > 0) secretUnlocked = true
    }
    // Per v0.7.1: gradual highlight animation for the export-image menu item when secret is unlocked.
    val secretHighlightAlpha by animateFloatAsState(
        targetValue = if (secretUnlocked) 1f else 0f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "secretHighlight",
    )
    val exportingMessage = localized("导出图片中...", "Exporting image...")
    // Per v0.6.3: pre-resolve composable values for use in non-composable onClick lambdas.
    val shareLabel = localized("分享到…", "Share to…")
    val shareContext = LocalContext.current
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
                    tab.bondDrawMode != BondDrawMode.NONE -> "bonds"
                    tab.atomEditMode != AtomEditMode.NONE -> "atoms"
                    else -> null
                }
                tab.bondDrawMode = BondDrawMode.NONE
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
    LaunchedEffect(tab.id, tab.structure, tab.expansion, tab.bondConfiguration, renderedAppearance, tab.renderConfiguration, tab.visibility) {
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
            val scene = withTimeoutOrNull(BUILD_SCENE_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    val analysis = BondDetector.buildNetwork(tab.structure, tab.bondConfiguration, tab.expansion)
                    CrystalRenderSceneFactory.build(
                        analysis = analysis,
                        appearance = renderedAppearance,
                        renderConfiguration = tab.renderConfiguration,
                        hiddenSiteIds = tab.visibility.hiddenSites,
                        hiddenBondKeys = tab.visibility.hiddenBondPairs,
                        showBonds = tab.visibility.showBonds,
                        polyhedronSiteIds = tab.visibility.polyhedronSites,
                        structuralExpansion = tab.structuralExpansion,
                    )
                }
            }
            dialogJob.cancel()
            sceneRebuilding = false
            if (scene != null) Result.success(scene)
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
    val bondValenceBySiteState = produceState<Map<String, Double>>(emptyMap(), tab.structure, tab.bondConfiguration, tab.bondEpsilon) {
        value = withContext(Dispatchers.Default) {
            BondValence.bondValenceSums(tab.structure, tab.bondConfiguration, tab.bondEpsilon)
        }
    }
    val bondValenceBySite = bondValenceBySiteState.value

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Krystals", fontWeight = FontWeight.Bold, maxLines = 1) },
            navigationIcon = { Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.Menu, null) }; DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.import_local)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) }, onClick = { menuOpen = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.open_preset_library)) }, leadingIcon = { Icon(Icons.Default.Inventory2, null) }, onClick = { menuOpen = false; onOpenPreset() })
                DropdownMenuItem(text = { Text(stringResource(R.string.import_online)) }, leadingIcon = { Icon(Icons.Default.CloudDownload, null) }, onClick = { menuOpen = false; onOnlineSource() })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Default.AddCircle, null) }, onClick = { menuOpen = false; onNew() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuOpen = false; onSave(tab) })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_to_presets)) }, leadingIcon = { Icon(Icons.Default.Bookmark, null) }, onClick = { menuOpen = false; onSaveToPreset() })
                DropdownMenuItem(text = { Text(localized("分享到…", "Share to…")) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                    menuOpen = false
                    scope.launch {
                        runCatching {
                            val cifContent = CifCodec.write(
                                tab.parsed,
                                tab.structure,
                                tab.bondConfiguration,
                                tab.renderConfiguration.toCifDisplayMetadata(),
                            )
                            withContext(Dispatchers.IO) {
                                val tempFile = File(shareContext.cacheDir, "${tab.name.ensureCifExtension()}")
                                tempFile.writeText(cifContent, Charsets.UTF_8)
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(shareContext, "${shareContext.packageName}.fileprovider", tempFile))
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooserIntent = android.content.Intent.createChooser(shareIntent, shareLabel)
                                chooserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                shareContext.startActivity(chooserIntent)
                            }
                        }.onFailure { error ->
                            if (error !is CancellationException) onMessage(error.message ?: "Share failed")
                        }
                    }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.export_image), color = if (secretHighlightAlpha > 0f) MaterialTheme.colorScheme.primary.copy(alpha = secretHighlightAlpha) else androidx.compose.ui.graphics.Color.Unspecified) }, leadingIcon = { val defaultTint = androidx.compose.material3.LocalContentColor.current; Icon(Icons.Default.Photo, null, tint = if (secretHighlightAlpha > 0f) MaterialTheme.colorScheme.primary.copy(alpha = secretHighlightAlpha) else defaultTint) }, onClick = {
                    menuOpen = false
                    val useMsaa = secretUnlocked
                    secretUnlocked = false
                    sceneResult?.getOrNull()?.let { scene ->
                        exporting = true
                        exportJob = scope.launch {
                            try {
                                val renderer = activeFilamentRenderer
                                // Per v0.8.26: export quality — HIGH = full res, LOW = half res.
                                val useHigh = settingsValues.exportQuality == ExportQuality.HIGH
                                val bitmap = if (renderer != null) {
                                    runCatching { renderer.submit(scene); renderer.renderToBitmap(useMsaa = useMsaa && useHigh) }.getOrNull()?.let {
                                        if (useHigh) it else android.graphics.Bitmap.createScaledBitmap(it, it.width / 2, it.height / 2, true)
                                    }
                                } else null
                                if (bitmap == null) {
                                    onMessage("Unable to export current crystal")
                                } else {
                                    val finalBitmap = if (settingsValues.exportShowAxes || settingsValues.exportShowMeasurements) {
                                        bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { out ->
                                            ExportOverlay.apply(
                                                bitmap = out,
                                                scene = scene,
                                                state = tab.interactionState,
                                                includeAxes = settingsValues.exportShowAxes,
                                                includeMeasurements = settingsValues.exportShowMeasurements,
                                            )
                                        }
                                    } else bitmap
                                    onExport(finalBitmap)
                                }
                            } catch (error: Exception) {
                                if (error !is CancellationException) onMessage(error.message ?: "Export failed")
                            } finally {
                                exporting = false
                                exportJob = null
                            }
                        }
                    } ?: onMessage("Unable to export current crystal")
                })
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                    TextButton(onClick = { menuOpen = false; onHelp() }) { Text(stringResource(R.string.help), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { menuOpen = false; onFeedback() }) { Text(stringResource(R.string.feedback), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                    TextButton(onClick = { menuOpen = false; onAbout() }) { Text(stringResource(R.string.about), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { menuOpen = false; onSponsor() }) { Text(stringResource(R.string.sponsor), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(localized("关闭所有文件并退出", "Close all files and exit")) }, onClick = { menuOpen = false; onExit() })
            } } },
            actions = {
                // Per v0.8.1: scope the history-version read to the toolbar actions so undo/redo
                // button enablement recomposes without re-running the whole ViewerScreen. The value
                // itself is unused — the read is what subscribes this lambda to undo/redo changes.
                @Suppress("UNUSED_VARIABLE") val historyVersion = tab.historyVersion
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
                                    tab.pendingBondDrawRule = BondRule(
                                        tab.bondDrawFirstSiteId!!, atom.siteId,
                                        0.1, dist + 0.1,
                                        BondRuleSource.CUSTOM,
                                    )
                                    tab.bondDrawMode = BondDrawMode.NONE
                                    tab.bondDrawFirstSiteId = null
                                    tab.bondDrawFirstCartesian = null
                                    tab.selectedAtomIds = emptyList()
                                    persistentMessage = null
                                    // Per v0.7.1: auto-navigate to the bonds tab.
                                    tab.pendingEditorTab = "bonds"
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
                                    val key = listOf(siteA, siteB).sorted().joinToString("\u0000")
                                    val matchingRule = tab.bondConfiguration.rules.firstOrNull { it.key == key }
                                    if (matchingRule != null) {
                                        tab.recordHistory()
                                        val result = CrystalEditor.apply(tab.structure, tab.bondConfiguration, EditCommand.RemoveBondRule(key))
                                        tab.structure = result.structure
                                        tab.bondConfiguration = result.bondConfiguration
                                        tab.dirty = true
                                    } else {
                                        onMessage(noBondMessage)
                                    }
                                    tab.bondDrawMode = BondDrawMode.NONE
                                    tab.bondDrawFirstSiteId = null
                                    tab.bondDrawFirstCartesian = null
                                    tab.selectedAtomIds = emptyList()
                                    persistentMessage = null
                                    // Per v0.7.1: auto-navigate to the bonds tab.
                                    tab.pendingEditorTab = "bonds"
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
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = {
        sceneRebuilding = false
        tab.undo()
    }) {
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
                                    preferences.edit().putFloat("${floatingKey}_x", snapped.xFraction).putFloat("${floatingKey}_y", snapped.yFraction).putString("${floatingKey}_snap", snapped.snap.name).apply()
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

@Composable
private fun LanguageMenuItem(language: String, onLanguage: (String) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.language)) },
        leadingIcon = { Icon(Icons.Default.Settings, null) },
        onClick = {},
        trailingIcon = {
            Row {
                TextButton(onClick = { onLanguage("zh") }) { Text("中", fontWeight = FontWeight.Bold, color = if (language == "zh") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onLanguage("en") }) { Text("EN", fontWeight = FontWeight.Bold, color = if (language == "en") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
    )
}

@Composable
private fun ThemeMenuItem(mode: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    DropdownMenuItem(
        text = { Text(localized("主题", "Theme")) },
        leadingIcon = { Icon(Icons.Default.Contrast, null) },
        onClick = {},
        trailingIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.entries.forEach { item ->
                    val icon = when (item) { ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto; ThemeMode.LIGHT -> Icons.Default.LightMode; ThemeMode.DARK -> Icons.Default.DarkMode }
                    val tint = if (item == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = { onTheme(item) }, modifier = Modifier.size(36.dp)) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
                }
            }
        },
    )
}

/** Per v0.6.5: classify an element as metal (true) or non-metal (false). */
private fun isMetal(symbol: String): Boolean = symbol !in setOf(
    "H", "He", "B", "C", "N", "O", "F", "Ne",
    "Si", "P", "S", "Cl", "Ar",
    "Ge", "As", "Se", "Br", "Kr",
    "Sb", "Te", "I", "Xe",
    "At", "Rn", "Po",
)

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

@Composable
private fun CommentsPanel(tab: DocumentTab, onDismiss: () -> Unit) {
    // Per v0.7.0: adjustable comments panel with debounce auto-save.
    var text by remember(tab.id) { mutableStateOf(tab.comments) }
    LaunchedEffect(text) {
        if (text != tab.comments) {
            delay(500)
            tab.comments = text
            tab.dirty = true
        }
    }
    // Per v0.7.0: slide-in animation state.
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val slideProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "commentsSlide",
    )
    LaunchedEffect(visible) {
        if (!visible && dismissed) { delay(150); onDismiss() }
    }
    fun doDismiss() { dismissed = true; visible = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panelPrefs = LocalContext.current.getSharedPreferences("panel_sizes", android.content.Context.MODE_PRIVATE)
        val prefKey = "comments_panel_ratio_${if (landscape) "landscape" else "portrait"}"
        var panelRatio by remember(landscape) { mutableStateOf(panelPrefs.getFloat(prefKey, 0.5f)) }
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = slideProgress }.background(Color.Black.copy(alpha = 0.22f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::doDismiss,
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
                Column(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            localized("添加晶体备注", "Add Crystal Comments"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { doDismiss() }) { Icon(Icons.Default.Close, null) }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
value = text,
onValueChange = { text = it },
modifier = Modifier.fillMaxWidth().weight(1f),
placeholder = { Text(localized("在此输入备注…", "Type comments here…")) },
)
Spacer(Modifier.height(4.dp))
Text(
localized("Krystals 备注将在保存时写入文件中", "Krystals comments will be written to the file when saved"),
style = MaterialTheme.typography.labelSmall,
color = MaterialTheme.colorScheme.onSurfaceVariant,
)
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    handle()
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
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
    // Per v0.7.0: slide-in animation state.
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val slideProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "displaySlide",
    )
    LaunchedEffect(visible) {
        if (!visible && dismissed) { delay(150); onDismiss() }
    }
    fun doDismiss() { dismissed = true; visible = false }
    // Per v0.2.3: resizable panel — drag the handle to change how much of the screen the panel
    // occupies. Portrait: bottom sheet height fraction; landscape: right sheet width fraction.
    // Per v0.3.43: default area raised to 0.4.
    // Per v0.7.0: persist panel ratio per orientation (portrait/landscape).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panelPrefs = LocalContext.current.getSharedPreferences("panel_sizes", android.content.Context.MODE_PRIVATE)
        val prefKey = "display_panel_ratio_${if (landscape) "landscape" else "portrait"}"
        var panelRatio by remember(landscape) { mutableStateOf(panelPrefs.getFloat(prefKey, 0.40f)) }
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = slideProgress }.background(Color.Black.copy(alpha = 0.22f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::doDismiss,
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
            // content sits beside it in a Row (a Column with a fillMaxHeight first child would leave
            // no height for the tabs/content — the cause of the blank landscape panel). The content
            // itself is wrapped in a single Column so the lambda can be reused for both layouts.
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
                        val tabEntries = buildList {
                            add(DisplayTab.ATOMS to localized("原子", "Atoms"))
                            add(DisplayTab.BONDS to localized("化学键", "Bonds"))
                            add(DisplayTab.POLYHEDRA to localized("多面体", "Polyhedra"))
                            if (hbondRules.isNotEmpty()) add(DisplayTab.HBONDS to localized("氢键", "H-Bonds"))
                        }
                        tabEntries.forEach { (kind, label) -> FilterChip(selected == kind, onClick = { selected = kind }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp)) }
                        Spacer(Modifier.weight(1f)); IconButton(onClick = { doDismiss() }) { Icon(Icons.Default.Close, null) }
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
                                            val eaMetal = isMetal(ea)
                                            val ebMetal = isMetal(eb)
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
                                                { if (isMetal(it.split("—").getOrNull(0) ?: "?")) 0 else 1 },
                                                { if (isMetal(it.split("—").getOrNull(1) ?: "?")) 0 else 1 },
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
private fun InfoDialog(tab: DocumentTab, onDismiss: () -> Unit) {
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

@Composable
private fun AlignDialog(onDismiss: () -> Unit, onChoice: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("对齐", "Align")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(localized("空间直角坐标系", "Cartesian"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("X", "Y", "Z").forEach { ChoiceTile(it, onChoice, textStyle = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(14.dp))
                Text(localized("晶胞轴", "Cell axes"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("a", "b", "c").forEach { ChoiceTile(it, onChoice, textStyle = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)) }
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
    activeMode: String? = null,
    onDismiss: () -> Unit,
    onChoice: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("测量", "Measure")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(length, angle).forEach { label ->
                        ChoiceTile(label, onChoice, tileHeight = 72.dp, active = label == activeMode && activeMode != off, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(dihedral, off).forEach { label ->
                        ChoiceTile(label, onChoice, tileHeight = 72.dp, active = label == activeMode && activeMode != off, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceTile(
    label: String,
    onClick: (String) -> Unit,
    tileHeight: androidx.compose.ui.unit.Dp = 80.dp,
    active: Boolean = false,
    textStyle: androidx.compose.ui.text.TextStyle? = null,
    modifier: Modifier = Modifier,
) {
    // Per v0.6.2: in dark mode use light purple (0xFFCFA7F5) for highlights; deep purple in light mode.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val activeColor = if (dark) Color(0xFFCFA7F5) else Color(0xFF7542A5)
    val resolvedStyle = textStyle ?: if (tileHeight >= 76.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium
    Card(
        onClick = { onClick(label) },
        modifier = modifier.padding(4.dp).height(tileHeight).fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors(containerColor = activeColor.copy(alpha = 0.15f)) else CardDefaults.cardColors(),
        border = if (active) androidx.compose.foundation.BorderStroke(2.dp, activeColor) else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = resolvedStyle, color = if (active) activeColor else Color.Unspecified)
        }
    }
}

@Composable
private fun PresetLibraryScreen(
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
    var groups by remember { mutableStateOf(PresetRepository.listGroups(context)) }
    var metas by remember { mutableStateOf<Map<PresetEntry, PresetMeta>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    var selected by remember { mutableStateOf<Set<PresetEntry>>(emptySet()) }
    var newGroupOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PresetGroup?>(null) }
    var renameFileTarget by remember { mutableStateOf<PresetEntry?>(null) }
    var moveOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val EXPANDED_KEY = "preset_expanded_categories"
    var expanded by remember {
        mutableStateOf(
            preferences.getString(EXPANDED_KEY, PresetRepository.MY_PRESETS_GROUP)!!.split(",").filter { it.isNotBlank() }.toMutableSet()
        )
    }
    fun toggle(cat: String) {
        expanded = expanded.toMutableSet().apply { if (!add(cat)) remove(cat) }.also {
            preferences.edit().putString(EXPANDED_KEY, it.joinToString(",")).apply()
        }
    }
    fun refresh() { groups = PresetRepository.listGroups(context) }

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
    val formulaFilter = filterState.formula
    val filteredGroups = groups.map { group ->
        group.copy(entries = group.entries.filter { entry ->
            val meta = metas[entry]
            (searchQuery.isBlank() || entry.name.contains(searchQuery, ignoreCase = true)) &&
                (elementCountFilter == null || meta?.elementCount == elementCountFilter) &&
                (crystalSystemFilter == null || meta?.crystalSystem == crystalSystemFilter) &&
                (pointGroupFilter == null || meta?.pointGroup == pointGroupFilter) &&
                (spaceGroupFilter == null || meta?.spaceGroup == spaceGroupFilter) &&
                (formulaFilter == null || meta?.formula?.contains(formulaFilter, ignoreCase = true) == true)
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
        selected.filter { it.source == PresetSource.USER }.forEach { PresetRepository.deletePreset(context, it) }
        selected = emptySet()
        refresh()
    }
    fun moveSelected(targetGroup: String) {
        selected.filter { it.source == PresetSource.USER }.forEach { PresetRepository.movePreset(context, it, targetGroup) }
        selected = emptySet()
        refresh()
    }

    // Per v0.8.34: full-screen page (like the COD search screen). Per v0.8.35: rendered as a
    // plain page composable, NOT inside a Dialog — Compose DropdownMenus (the filter chips)
    // crash during Popup measurement when hosted inside a dialog window on some devices.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground) }
                    Text(stringResource(R.string.preset_library), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    TextButton(onClick = { newGroupOpen = true }) { Text(localized("新建组", "New group")) }
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
                            Row(Modifier.fillMaxWidth().clickable { toggle(group.name) }.padding(vertical = 4.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (group.name in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp))
                                Text(group.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp).weight(1f))
                                if (group.isUserGroup) {
                                    IconButton(onClick = { renameTarget = group }) { Icon(Icons.Default.Edit, localized("重命名组", "Rename group"), modifier = Modifier.size(18.dp)) }
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
                            )
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    HorizontalDivider()
                    // Per v0.8.35: order 删除/移动到/打开, with Open as a highlighted button.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localized("已选 ${selected.size} 项", "Selected ${selected.size}"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { deleteConfirmOpen = true }) { Text(localized("删除", "Delete")) }
                        TextButton(onClick = { moveOpen = true }) { Text(localized("移动到", "Move to")) }
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
                    if (name.isNotBlank()) {
                        if (PresetRepository.createGroup(context, name) != null) refresh()
                        else onMessage(createError)
                    }
                    newGroupOpen = false
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
                    if (!PresetRepository.renameGroup(context, group.name, name)) onMessage(renameError)
                    renameTarget = null
                    refresh()
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
                    if (!PresetRepository.renamePreset(context, entry, name)) onMessage(renameError)
                    renameFileTarget = null
                    refresh()
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
}

/** Per v0.8.34: one preset file row with a checkbox; tapping the row opens the file (v0.8.35),
 *  the checkbox toggles selection, and user files get a per-row rename button. */
@Composable
private fun PresetRow(
    entry: PresetEntry,
    meta: PresetMeta?,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
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
        } else {
            Text(stringResource(R.string.bundled), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
    HorizontalDivider()
}

/** Per v0.6.5: apply filters to MP search results. */
private fun filterMpResults(results: List<MpSearchResult>, filter: SearchFilterState): List<MpSearchResult> {
    if (!filter.isActive) return results
    return results.filter { item ->
        val meta = item.meta()
        (filter.elementCount == null || meta.elementCount == filter.elementCount) &&
        (filter.crystalSystem == null || meta.crystalSystem == filter.crystalSystem) &&
        (filter.pointGroup == null || meta.pointGroup == filter.pointGroup) &&
        (filter.spaceGroup == null || meta.spaceGroup == filter.spaceGroup) &&
        // Per v0.8.27: extended filters.
        (filter.formula == null || item.formula.contains(filter.formula, ignoreCase = true)) &&
        (filter.elementComposition == null || elementsInFormula(item.formula).containsAll(parseElementSet(filter.elementComposition))) &&
        (filter.stable == null || stableOf(item) == filter.stable)
    }
}

/** Per v0.6.5: apply filters to COD search results. */
private fun filterCodResults(results: List<CodSearchResult>, filter: SearchFilterState): List<CodSearchResult> {
    if (!filter.isActive) return results
    return results.filter { item ->
        val meta = item.meta()
        (filter.elementCount == null || meta.elementCount == filter.elementCount) &&
        (filter.crystalSystem == null || meta.crystalSystem == filter.crystalSystem) &&
        (filter.pointGroup == null || meta.pointGroup == filter.pointGroup) &&
        (filter.spaceGroup == null || meta.spaceGroup == filter.spaceGroup) &&
        // Per v0.8.27: extended filters.
        (filter.formula == null || item.formula.contains(filter.formula, ignoreCase = true)) &&
        (filter.title == null || item.title.contains(filter.title, ignoreCase = true)) &&
        (filter.author == null || item.author.contains(filter.author, ignoreCase = true)) &&
        (filter.journal == null || item.journal.contains(filter.journal, ignoreCase = true)) &&
        (filter.year == null || item.year == filter.year)
    }
}

/** Per v0.8.27: distinct element symbols in a formula like "Fe2O3" → {Fe, O}. */
private fun elementsInFormula(formula: String): Set<String> =
    Regex("[A-Z][a-z]?").findAll(formula).map { it.value }.toSet()

/** Per v0.8.27: parse a composition filter input like "Fe O" into element symbols. */
private fun parseElementSet(input: String): Set<String> = elementsInFormula(input)

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
        // Per v0.8.35: custom Popup instead of DropdownMenu — DropdownMenu sizes its content via
        // intrinsic measurement (IntrinsicSize.Min), and any lazy list inside throws
        // "intrinsic measurements of SubcomposeLayout layouts". A plain Popup has no intrinsic
        // sizing step; width is fixed, height caps at 360dp with scrolling.
        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
                offset = IntOffset(0, with(LocalDensity.current) { 48.dp.roundToPx() }),
            ) {
                Surface(
                    Modifier.width(280.dp).heightIn(max = 360.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
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
        // Per v0.8.35: custom Popup (see FilterDropdownChip for why DropdownMenu is avoided).
        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
                offset = IntOffset(0, with(LocalDensity.current) { 48.dp.roundToPx() }),
            ) {
                Surface(
                    Modifier.width(280.dp).heightIn(max = 360.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
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
    }
}

/** Per v0.8.27: localized label for a filter type. */
@Composable
private fun filterTypeLabel(type: SearchFilterType): String = when (type) {
    SearchFilterType.FORMULA -> localized("化学式", "Formula")
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
private fun SearchFilterBar(
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
        tonalElevation = 2.dp,
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
            if (SearchFilterType.FORMULA in visibleFilters) {
                item {
                    FilterDropdownChip(
                        label = filterTypeLabel(SearchFilterType.FORMULA),
                        selectedValue = filterState.formula,
                        options = options.formulas,
                        onSelect = { v -> onFilterChange(filterState.copy(formula = v)) },
                    )
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
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var testingConnection by remember { mutableStateOf(true) }
    var connectionError by remember { mutableStateOf(false) }
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    // Per v0.8.27: filter-item visibility (persisted for the session) + help panel state.
    var visibleFilters by remember { mutableStateOf(DEFAULT_VISIBLE_FILTERS) }
    var helpOpen by remember { mutableStateOf(false) }
    var helpAnchor by remember { mutableStateOf(IntOffset.Zero) }
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).onGloballyPositioned { helpAnchor = IntOffset(0, it.boundsInParent().bottom.roundToInt() + 4) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(fuzzySearch, onCheckedChange = { fuzzySearch = it })
                Text(localized("模糊搜索", "Fuzzy search"), style = MaterialTheme.typography.bodyMedium)
                // Per v0.8.27: help button toggles the wildcard hint panel.
                IconButton(onClick = { helpOpen = !helpOpen }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Help, localized("帮助", "Help"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                // Per v0.8.29: matching-count caption under the filter bar.
                Text(
                    localized("符合条件的", "Matching") + " ${filtered?.size ?: 0} " + localized("个结果: ", "results: "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索中…", "Searching…")) }
                filtered == null -> {}
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    // Per v0.8.1: stable key so filter/search updates keep item identity (scroll + reuse).
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
        // Per v0.8.27: help panel — tap anywhere to dismiss. Per v0.8.31: animated open/close.
        AnimatedVisibility(
            visible = helpOpen,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(initialAlpha = 0f),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)).clickable { helpOpen = false })
                Surface(
                    modifier = Modifier.offset { helpAnchor }.padding(horizontal = 16.dp).fillMaxWidth().clickable { helpOpen = false },
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 4.dp,
                ) {
                    Text(
                        localized(
                            "使用 * 进行元素通配搜索（如SiO*）。精确搜索中，* 仅代表一种元素；模糊搜索显示 * 代表多种元素的结果。",
                            "Use * for element wildcard search (e.g. SiO*). In exact search, * represents a single element; fuzzy search shows results where * matches multiple elements.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
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
private fun CodSearchScreen(
    context: Context,
    viewModel: KrystalsViewModel,
    autoConvertCell: Boolean,
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
    var testingMirrors by remember { mutableStateOf(true) }
    var connectionError by remember { mutableStateOf(false) }
    var filterState by remember { mutableStateOf(SearchFilterState()) }
    // Per v0.8.27: filter-item visibility (persisted for the session) + help panel state.
    var visibleFilters by remember { mutableStateOf(DEFAULT_VISIBLE_FILTERS) }
    var helpOpen by remember { mutableStateOf(false) }
    var helpAnchor by remember { mutableStateOf(IntOffset.Zero) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val mirror = CrystallographyOpenDatabase.testMirrors()
        if (mirror != null) {
            CrystallographyOpenDatabase.selectMirror(mirror)
            testingMirrors = false
        } else {
            testingMirrors = false
            connectionError = true
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
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val result = CrystallographyOpenDatabase.search(query, mode, if (mode == CrystallographyOpenDatabase.SearchMode.ELEMENT) maxElements else null)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            searching = false
                            result.onSuccess { results = it }.onFailure { onMessage(it.message ?: "Search failed") }
                        }
                    }
                }, enabled = !searching) { Text(localized("搜索", "Search")) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).onGloballyPositioned { helpAnchor = IntOffset(0, it.boundsInParent().bottom.roundToInt() + 4) }, verticalAlignment = Alignment.CenterVertically) {
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
                // Per v0.8.27: help button toggles the search-mode hint panel.
                IconButton(onClick = { helpOpen = !helpOpen }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Help, localized("帮助", "Help"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                // Per v0.8.29: matching-count caption under the filter bar.
                Text(
                    localized("符合条件的", "Matching") + " ${filtered?.size ?: 0} " + localized("个结果: ", "results: "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索中…", "Searching…")) }
                filtered == null -> {}
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(localized("搜索结果为空", "No results")) }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    // Per v0.8.1: stable key so filter/search updates keep item identity (scroll + reuse).
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
                                if (isExact) Modifier.border(2.dp, Color(0xFF7542A5), RoundedCornerShape(12.dp)) else Modifier
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
        // Per v0.8.27: help panel — tap anywhere to dismiss. Per v0.8.31: animated open/close.
        AnimatedVisibility(
            visible = helpOpen,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(initialAlpha = 0f),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)).clickable { helpOpen = false })
                Surface(
                    modifier = Modifier.offset { helpAnchor }.padding(horizontal = 16.dp).fillMaxWidth().clickable { helpOpen = false },
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 4.dp,
                ) {
                    Text(
                        localized(
                            "化学式如 SiO2（自动转为 Hill 顺序 O2 Si）。元素以空格分开，如 Si O。文本如 quartz，匹配矿物名/化学名/标题。",
                            "Formula e.g. SiO2 (auto-converted to Hill order: O2 Si). Elements separated by space, e.g. Si O. Text e.g. quartz, matches mineral/chemical names and titles.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
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
private fun OnlineSourcePickerDialog(
    onDismiss: () -> Unit,
    onPickCod: () -> Unit,
    onPickMp: () -> Unit,
) {
    // Per v0.8.31: plain dialog — the v0.8.29 expand/shrink animation was removed per user request.
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
                        "Krystals使用Materials Project的新API进行晶体搜索和下载。新API需要apikey才能使用。\n\n· 搜索结果中的晶胞为原始素晶胞，Krystals会自动将其转换为正当晶胞后展示。\n\n· 若转换失败，将回退为素晶胞展示。",
                        "Krystals uses the Materials Project new API for crystal search and download. The new API requires an API key.\n\n· Search results return primitive cells; Krystals automatically converts them to conventional cells before display.\n\n· If conversion fails, the primitive cell is shown instead."
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
private fun AboutScreen(onBack: () -> Unit, onCheckUpdates: () -> Unit = {}, isCheckingUpdates: Boolean = false, updateMessage: String? = null) {
    BackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.navigationBars)) {
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
            Spacer(Modifier.height(16.dp))
            // Per v0.7.1: "Check for Updates" button moved below the software description.
            Button(
                onClick = onCheckUpdates,
                enabled = !isCheckingUpdates,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isCheckingUpdates) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(localized("检查更新", "Check for Updates"))
            }
            // Per v0.7.1: show update check result directly on the About screen
            // (previously used showMessage/Snackbar which was hidden behind this full-screen overlay).
            if (updateMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(updateMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
private fun AssetImage(path: String, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val image = remember(path) { runCatching { context.assets.open(path).use { stream -> BitmapFactory.decodeStream(stream) }.asImageBitmap() }.getOrNull() }
    if (image != null) Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)))
}

private fun String.ensureCifExtension() = if (endsWith(".cif", true)) this else "$this.cif"

// ── Per v0.6.5: Update check and APK download/install helpers ──────────────────

/** Version info fetched from the remote JSON. */
private data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val packageName: String,
)

/** Fetch version info from the remote JSON. Returns null on failure. */
private suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("https://www.kelesss.art/lib/krystals-release/current_version.json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)
            UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName", ""),
                packageName = json.optString("packageName", ""),
            ).takeIf { it.versionCode > 0 && it.packageName.isNotBlank() }
        }
    }.getOrNull()
}

/** Download the APK and trigger installation. Returns true on success. */
private suspend fun downloadAndInstallApk(
    context: android.content.Context,
    packageName: String,
    onProgress: (Float) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val downloadUrl = "https://www.kelesss.art/lib/krystals-release/$packageName"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext false
            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()
            val apkDir = java.io.File(context.cacheDir, "apk_updates").apply { mkdirs() }
            val apkFile = java.io.File(apkDir, packageName)
            body.byteStream().use { input ->
                java.io.FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            onProgress((totalRead.toFloat() / contentLength).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }
            // Trigger APK installation.
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }
    }.onFailure { it.printStackTrace() }.getOrDefault(false)
}
