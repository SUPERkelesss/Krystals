@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import androidx.core.content.edit
import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.Manifest
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.krystals.app.ui.AppPalette
import com.krystals.app.ui.KrystalsTheme
import com.krystals.app.ui.ThemeMode
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.VoronoiSearchLimitExceededException
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.data.PeriodicTableData
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import com.krystals.renderer.core.style.ViewerAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Per v0.8.43 (issue #11): the open pipeline (CIF parse / symmetry expansion / scene build)
// runs on its own small pool — a limited view of Dispatchers.Default — so a large cell's
// heavy bond computation (smartIonic 15s + Voronoi fallback) can no longer starve
// subsequent opens. 2 concurrent slots are enough for user-paced opens.
internal val openDispatcher = Dispatchers.Default.limitedParallelism(2)

// Per v0.8.43 (issue #11): heavy bond computations are globally limited to one in flight —
// the second queues (Semaphore.acquire suspends, never blocking the main thread or the open
// pipeline). App-wide single instance: KrystalsRoot is the only consumer.
private val bondComputeGate = Semaphore(1)

/** Per v0.8.43 (issue #11): an in-flight bond computation tracked for cancel-all (overlay
 *  dialog) and stale-abandon (a new file opened). [tabId] is null for the edit path, whose
 *  jobs are never stale-abandoned (a cancelled edit would leave the tab half-applied). */
private data class ActiveComputation(
    val job: kotlinx.coroutines.Job,
    val tabId: String?,
    val startedAt: Long,
)

/** Per v0.8.43 (issue #11): cancellation cause for computations abandoned because a new file
 *  was opened — the catch distinguishes it from a user cancel so the v0.6.1 fallback
 *  (bonding-radius rules on user cancel) is NOT triggered for abandoned work. */
private class StaleComputationCancelled : kotlin.coroutines.cancellation.CancellationException()

/** Per v0.8.43 (issue #11): a computation younger than this is never stale-abandoned — batch
 *  open (issue #5) calls doOpenParsed within milliseconds, so its young computations survive. */
private const val STALE_COMPUTE_CANCEL_GRACE_MS = 2_000L

private data class PendingOpen(val uri: Uri, val name: String, val text: String, val candidates: List<Int>, val document: com.krystals.crystal.io.CifDocument)

/** Per v0.8.27: user-toggleable search filter types shown in the filter bar. */

private data class PendingLargeOpen(val parsed: ParsedStructure, val name: String, val uri: Uri?, val expandedEstimate: Int)

/** Holds a structure-change bond recomputation whose expanded atom count exceeds the warn threshold. */

private class PendingLargeEdit(val block: suspend () -> com.krystals.crystal.analysis.editing.EditResult?, val expandedEstimate: Int)

/** Per v0.5.3b: scene build runs off the UI thread with this cap; on timeout it fails with a
 *  readable error instead of hanging the viewer. */

internal const val BUILD_SCENE_TIMEOUT_MS = 15_000L

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
        mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString(PreferencesStore.KEY_THEME, ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM))
    }
    var language by remember {
        mutableStateOf(resolveLanguage(preferences.getString(PreferencesStore.KEY_LANGUAGE, defaultSystemLanguage()) ?: "en"))
    }
    val systemDark = isSystemInDarkTheme()
    var backgroundFollowTheme by remember {
        mutableStateOf(preferences.getBoolean(PreferencesStore.KEY_BG_FOLLOW_THEME, true))
    }
    fun applyLanguage(value: String) {
        if (language == value) return
        language = value
        preferences.edit { putString(PreferencesStore.KEY_LANGUAGE, value); putBoolean(PreferencesStore.KEY_PENDING_LANGUAGE_RESTART, true) }
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
                val background = if (dark) AppPalette.BG_DARK else AppPalette.BG_LIGHT
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
        mutableStateOf(preferences.getBoolean(PreferencesStore.KEY_PENDING_LANGUAGE_RESTART, false))
    }
    LaunchedEffect(languageSwitching) {
        if (languageSwitching) {
            kotlinx.coroutines.delay(500)
            preferences.edit { putBoolean(PreferencesStore.KEY_PENDING_LANGUAGE_RESTART, false) }
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
        val background = if (dark) AppPalette.BG_DARK else AppPalette.BG_LIGHT
        viewModel.defaultAppearance = viewModel.defaultAppearance.copy(backgroundArgb = background)
        viewModel.tabs.forEach { tab -> tab.appearance = tab.appearance.copy(backgroundArgb = background) }
    }
    fun applyBackgroundFollowTheme(value: Boolean) {
        backgroundFollowTheme = value
        preferences.edit { putBoolean(PreferencesStore.KEY_BG_FOLLOW_THEME, value) }
        if (value) {
            val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDark
            applyViewerBackground(dark)
        }
    }
    // Per v0.5.2a: persist + globally apply a new appearance (default + every open tab).
    fun applyViewerAppearance(ap: ViewerAppearance) {
        viewModel.applyAppearance(ap)
        preferences.edit { putString(AppearanceStore.KEY, AppearanceStore.run { ap.toJson() }) }
    }
    fun applyTheme(mode: ThemeMode) {
        themeMode = mode
        preferences.edit { putString(PreferencesStore.KEY_THEME, mode.name) }
        applyViewerBackground(mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark)
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
    // Per v0.8.43 (issue #5): batch open computes every tab's bond rules concurrently.
    // computingCount drives the "Computing..." overlay (a single Boolean gate previously
    // skipped every computation after the first during batch open — only tab 1 got rules);
    // computingTabIds dedups per tab; computationJobs holds in-flight jobs for cancel-all.
    val computingCountState = remember { mutableIntStateOf(0) }
    var computingCount by computingCountState
    val computingTabIds = remember { mutableStateListOf<String>() }
    val computationJobsState = remember { mutableStateOf<List<ActiveComputation>>(emptyList()) }
    var computationJobs by computationJobsState
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
    // Per v0.8.36: Save / Save-to-preset confirm the file name in a dialog first.
    var saveNameOpen by remember { mutableStateOf(false) }
    var saveNameIsPreset by remember { mutableStateOf(false) }
    var saveNameTab by remember { mutableStateOf<DocumentTab?>(null) }
    var saveNameDraft by remember { mutableStateOf("") }
    var saveNameEdited by remember { mutableStateOf(false) }
    // Per v0.8.36: target user group for save-to-preset (default 我的预设).
    var saveNameGroup by remember { mutableStateOf(PresetRepository.MY_PRESETS_GROUP) }
    var sponsorLaunchCount by remember { mutableIntStateOf(0) }
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
        val count = preferences.getInt(PreferencesStore.KEY_LAUNCH_COUNT, 0) + 1
        preferences.edit { putInt(PreferencesStore.KEY_LAUNCH_COUNT, count) }
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
        val lastChecked = preferences.getLong(PreferencesStore.KEY_LAST_UPDATE_CHECK_MS, 0L)
        if (now - lastChecked < UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        preferences.edit { putLong(PreferencesStore.KEY_LAST_UPDATE_CHECK_MS, now) }
        updateScope.launch {
            val info = fetchUpdateInfo()
            if (info != null && info.versionCode > com.krystals.app.BuildConfig.VERSION_CODE) {
                val skipped = preferences.getInt(PreferencesStore.KEY_SKIPPED_VERSION_CODE, -1)
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
        // Per v0.8.43 (issue #5): the edit path keeps its global single-flight gate (an edit
        // during any computation is skipped) — now expressed via the computation count.
        if (computingCount > 0) return
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
        computingCount++
        val editStart = System.currentTimeMillis()
        var job: kotlinx.coroutines.Job? = null
        job = scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.Default) { block() })
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                Result.failure(ce)
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                computingCount--
                job?.let { j -> computationJobs -= ActiveComputation(j, tabId = null, startedAt = editStart) }
            }
            result.getOrNull()?.let { editResult ->
                viewModel.current?.let { viewModel.updateAnalysis(it, editResult) }
            }
            result.onFailure { error ->
                if (error is VoronoiSearchLimitExceededException) voronoiWarningOpen = true
                else if (error !is kotlin.coroutines.cancellation.CancellationException) showMessage(error.message ?: "Operation failed")
            }
        }
        computationJobs += ActiveComputation(job, tabId = null, startedAt = editStart)
    }

    /**
     * Per v0.5.2b: open-file bond computation with a smart-ionic timeout (15 s per v0.8.43, was 5 s).
     * Falls back to bonding
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
        // Per v0.8.43 (issue #5): per-tab dedup + concurrent computation. The old global
        // `if (computing) return` made batch open silently skip every bond computation after
        // the first — only the first tab ever received rules (PresetLibrary.openSelected loops
        // doOpenParsed sequentially, and each call hit the global busy gate). Same tab already
        // computing → skip (the original single-flight semantics, per tab).
        val targetTab = viewModel.current ?: return
        if (targetTab.id in computingTabIds) return
        debugLog(CIF_OPEN_TAG) { "BondCompute: tab ${targetTab.id} enqueued (${computingTabIds.size} in flight)" }
        computingTabIds += targetTab.id
        computingCount++
        val computeStart = System.currentTimeMillis()
        var job: kotlinx.coroutines.Job? = null
        job = scope.launch {
            val result = try {
                Result.success(
                    // Per v0.8.43 (issue #11): heavy bond computations are globally limited to
                    // one in flight — the next one queues here (suspension on the caller's
                    // dispatcher, never blocking the main thread or the open pipeline).
                    bondComputeGate.withPermit {
                        withContext(Dispatchers.Default) {
                            // Per v0.8.36: expandedSize comes from the open path (openParsed already
                            // expanded for the large-cell check) — re-expanding here doubled the
                            // expansion cost on big cells.
                            // Per v0.8.26: dispatch by user-selected bond-rule mode.
                            when (settingsValues.bondRuleMode) {
                                BondRuleMode.AUTO -> {
                                    if (CrystalEditor.isAllNonMetals(structure) || expandedSize > BondValence.SMART_IONIC_ATOM_LIMIT) {
                                        CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, null, settingsValues.autoComputeHbonds)
                                    } else {
                                        val smartIonic = kotlinx.coroutines.withTimeoutOrNull(15000L) {
                                            runCatching {
                                                BondValence.smartIonicRules(
                                                    structure, bondConfiguration, epsilon,
                                                    includeHbonds = settingsValues.autoComputeHbonds,
                                                    // Key: after the timeout the coroutine is cancelled, so isActive
                                                    // turns false and the Voronoi loop throws VoronoiAbortedException
                                                    // within ~64 candidates — runCatching turns it into null, the
                                                    // timeout actually fires on CPU-bound work, and the caller falls
                                                    // back to bonding rules with the timeout snackbar.
                                                    cancelCheck = { coroutineContext.isActive },
                                                )
                                            }.getOrNull()
                                        }
                                        CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, smartIonic, settingsValues.autoComputeHbonds)
                                    }
                                }
                                BondRuleMode.SMART_IONIC -> {
                                    val smartIonic = kotlinx.coroutines.withTimeoutOrNull(15000L) {
                                        runCatching { BondValence.smartIonicRules(structure, bondConfiguration, epsilon, includeHbonds = settingsValues.autoComputeHbonds) }.getOrNull()
                                    }
                                    CrystalEditor.fromSmartIonicAttempt(structure, bondConfiguration, epsilon, smartIonic, settingsValues.autoComputeHbonds)
                                }
                                BondRuleMode.BONDING -> CrystalEditor.rebuildBondRules(structure, bondConfiguration, RadiusSource.BONDING, epsilon, settingsValues.autoComputeHbonds)
                            }
                        }
                    }
                )
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                // Per v0.8.43 (issue #11): an abandoned computation (a new file was opened)
                // must NOT run the v0.6.1 fallback — its result is no longer needed and the
                // fallback would re-occupy the pool. The user-facing cancel (overlay dialog)
                // keeps the v0.6.1 fallback (bonding-radius rules so the panel is never empty).
                if (ce is StaleComputationCancelled) {
                    Result.failure(ce)
                } else {
                    val fallback = if (targetTab in viewModel.tabs) {
                        CrystalEditor.rebuildBondRules(
                            targetTab.structure,
                            targetTab.bondConfiguration,
                            RadiusSource.BONDING,
                            targetTab.bondEpsilon,
                            settingsValues.autoComputeHbonds,
                        )
                    } else null
                    fallback?.let { Result.success(it) } ?: Result.failure(ce)
                }
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                computingTabIds.remove(targetTab.id)
                computingCount--
                job?.let { j -> computationJobs -= ActiveComputation(j, targetTab.id, computeStart) }
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
                // Per v0.8.27: 默认显示氢键——关闭时把全部氢键规则 key 加入 hiddenBondPairs
                // (保留规则,DisplayPanel"氢键"子菜单仍在,可手动重新显示)。
                if (!settingsValues.defaultShowHbonds && targetTab in viewModel.tabs) {
                    val hbondKeys = extended.bondConfiguration.rules.filter { it.isHBond }.map { it.key }.toSet()
                    if (hbondKeys.isNotEmpty()) {
                        targetTab.visibility = targetTab.visibility.copy(
                            hiddenBondPairs = targetTab.visibility.hiddenBondPairs + hbondKeys,
                        )
                    }
                }
                // Per v0.8.43: the METALS_ONLY polyhedra default now has the regenerated rules —
                // refine the per-site default set from doOpenParsed to exactly the metals in
                // metal-nonmetal bonds (metal-metal bonds excluded).
                if (settingsValues.defaultPolyhedra == PolyhedraDefault.METALS_ONLY && targetTab in viewModel.tabs) {
                    targetTab.visibility = targetTab.visibility.copy(
                        polyhedronSites = CrystalEditor.metalNonmetalMetalIds(extended.structure, extended.bondConfiguration.rules),
                    )
                }
                if (CrystalEditor.SMART_IONIC_TIMEOUT in extended.warnings) showMessage(smartIonicTimeoutMessage)
                if (targetTab in viewModel.tabs) viewModel.updateAnalysis(targetTab, extended)
                debugLog(CIF_OPEN_TAG) { "OpenCIF 6/6: bond rules computed (${extended.bondConfiguration.rules.size} rules, ${extended.bondConfiguration.rules.count { it.isHBond }} hbonds, mode ${settingsValues.bondRuleMode}) [compute +${System.currentTimeMillis() - computeStart}ms]" }
            }.onFailure { error ->
                if (error is VoronoiSearchLimitExceededException) voronoiWarningOpen = true
                else if (error !is kotlin.coroutines.cancellation.CancellationException) showMessage(error.message ?: "Operation failed")
            }
        }
        computationJobs += ActiveComputation(job, targetTab.id, computeStart)
    }


    // Per v0.4.0: resume the MP flow after the caution dialog — hasKey ? search : enter key.
    fun proceedToMp() {
        if (MaterialsProject.hasKey(activity)) mpSearchOpen = true else mpKeyDialogOpen = true
    }

    fun openUrl(url: String) {
        runCatching { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) }.onFailure { showMessage("Unable to open browser") }
    }

    /** Per v0.5.3b: the actual tab insertion + bond computation, split out of [openParsed] so the
     *  large-cell warning can re-enter here after the user confirms. Declared before [openParsed]
     *  because Kotlin local functions have no forward references.
     *  Per v0.5.2b: always synthesize bond rules, ignoring any rules carried in the CIF. */
    fun doOpenParsed(parsed: ParsedStructure, name: String, uri: Uri?, expandedEstimate: Int) {
        viewModel.add(parsed, name, uri)
        val tab = viewModel.current ?: return
        // Per v0.8.43 (issue #11): opening a new file abandons in-flight bond computations of
        // other tabs — the user moved on, the Default-pool work is no longer needed, and the
        // pool is freed immediately. Two guards: (1) only open-path computations (tabId !=
        // null); (2) a grace window keeps batch open (issue #5) intact — its doOpenParsed
        // calls run within milliseconds of each other, so their young computations survive.
        val now = System.currentTimeMillis()
        computationJobs.forEach { c ->
            if (c.tabId != null && c.tabId != tab.id && c.job.isActive && now - c.startedAt > STALE_COMPUTE_CANCEL_GRACE_MS) {
                c.job.cancel(StaleComputationCancelled())
            }
        }
        debugLog(CIF_OPEN_TAG) { "OpenCIF 5/6: tab ready ($name)" }
        // Per v0.8.26: apply user preference defaults for the new tab.
        tab.visibility = tab.visibility.copy(showBonds = settingsValues.defaultShowBonds)
        // Default extend-bonds setting.
        tab.structuralExpansion = when (settingsValues.defaultExtendBonds) {
            ExtendBondsDefault.ALL -> true
            ExtendBondsDefault.METALS_ONLY -> parsed.structure.sites.any { PeriodicTableData.isMetal(it.species.symbol) }
            ExtendBondsDefault.NEVER -> false
        }
        // Default polyhedra visibility.
        // Per v0.8.43: METALS_ONLY shows polyhedra only for metals that participate in a
        // metal-NONmetal bond (metal-metal bonds excluded) — computed from the carried CIF
        // rules here; refined against the regenerated rules in openWithBondComputation's
        // onSuccess once they exist.
        val siteIds = parsed.structure.sites.map { it.id }.toSet()
        tab.visibility = tab.visibility.copy(polyhedronSites = when (settingsValues.defaultPolyhedra) {
            PolyhedraDefault.ALL -> siteIds
            PolyhedraDefault.METALS_ONLY -> CrystalEditor.metalNonmetalMetalIds(parsed.structure, parsed.bondConfiguration.rules)
            PolyhedraDefault.NEVER -> emptySet()
        })
        // Per v0.7.0: extract user comments from CIF source.
        tab.comments = CifComments.extract(parsed.document.source)
        // Per v0.8.26: skip bond computation when the user disables auto-bond-rules.
        // Per v0.8.36: pass the already-computed expansion size (openParsed expanded for the
        // large-cell gate) so the computation path does not re-expand the cell.
        if (settingsValues.autoBondRules) {
            openWithBondComputation(tab.structure, tab.bondConfiguration, tab.bondEpsilon, expandedEstimate)
        } else {
            // Per v0.8.36: with auto bond rules off, drop any rules carried in the CIF so no
            // bonds are shown at all (previously the file's saved rules still produced bonds).
            tab.bondConfiguration = tab.bondConfiguration.copy(rules = emptyList())
        }
    }

    /** Per v0.5.0: add a parsed structure, synthesizing bond rules off-UI with the computing overlay.
     *  Defined before [loadUri] because local functions must be declared before use (no forward refs).
     *  Per v0.5.3b: cells expanding past [LARGE_CELL_WARN_THRESHOLD] atoms are gated behind a
     *  confirm dialog; the user can still open them in degraded mode. */
    fun openParsed(parsed: ParsedStructure, name: String, uri: Uri?) {
        // Per v0.6.3: move SymmetryExpander.expand() off the main thread — it was the bottleneck
        // that made opening a CIF freeze the UI before the viewer appeared.
        // Per v0.8.43 (issue #11): the open pipeline runs on openDispatcher (its own small pool),
        // isolated from heavy bond computations on Dispatchers.Default.
        scope.launch {
            val expandStart = System.currentTimeMillis()
            val expandedEstimate = withContext(openDispatcher) { SymmetryExpander.expand(parsed.structure).size }
            debugLog(CIF_OPEN_TAG) { "OpenPhase: expand +${System.currentTimeMillis() - expandStart}ms ($expandedEstimate atoms)" }
            debugLog(CIF_OPEN_TAG) { "OpenCIF 4/6: symmetry expansion done ($expandedEstimate atoms)" }
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
                    debugLog(CIF_OPEN_TAG) { "OpenCIF 1/6: file read done (${text.length} chars)" }
                    val document = CifCodec.parse(text)
                    val candidates = CifCodec.structuralBlockIndices(document)
                    require(candidates.isNotEmpty()) { "No crystal structure found" }
                    debugLog(CIF_OPEN_TAG) { "OpenCIF 2/6: document parsed (${candidates.size} blocks)" }
                    PendingOpen(uri, FileRepository.displayName(resolver, uri), text, candidates, document)
                }
            }
            val pending = result.getOrNull()
            if (pending != null) {
                if (pending.candidates.size == 1) {
                    // Per v0.6.4: parseStructure can be heavy (resolves space groups, creates
                    // symmetry operations) — run off the UI thread to avoid blocking.
                    // Per v0.8.43 (issue #11): open pipeline runs on openDispatcher.
                    val parseStart = System.currentTimeMillis()
                    val parsed = withContext(openDispatcher) { CifCodec.parseStructure(pending.text, pending.candidates.first(), autoConvertConventional = settingsValues.autoConvertCell) }
                    debugLog(CIF_OPEN_TAG) { "OpenPhase: parse +${System.currentTimeMillis() - parseStart}ms (${parsed.structure.sites.size} sites)" }
                    debugLog(CIF_OPEN_TAG) { "OpenCIF 3/6: structure parsed (${parsed.structure.sites.size} sites, sg ${parsed.structure.spaceGroup.symbol})" }
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
                    tab.parsed = withContext(openDispatcher) { CifCodec.parseStructure(contentWithComments, tab.parsed.blockIndex, autoConvertConventional = settingsValues.autoConvertCell) }
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
                tab.parsed = withContext(openDispatcher) { CifCodec.parseStructure(contentWithComments, tab.parsed.blockIndex, autoConvertConventional = settingsValues.autoConvertCell) }
                tab.dirty = false
            }.onSuccess { showMessage("Saved ${tab.name}"); afterSave() }.onFailure { if (it !is CancellationException) showMessage(it.message ?: "Save failed") }
        }
    }

    /** Per v0.8.36: confirm button of the save / save-to-preset name dialog. */
    fun confirmSaveName() {
        val tab = saveNameTab ?: return
        val finalName = saveNameDraft.trim().ifEmpty { tab.name.removeSuffix(".cif") }.ensureCifExtension()
        saveNameOpen = false
        if (saveNameIsPreset) {
            scope.launch {
                runCatching {
                    PresetRepository.saveToPreset(
                        activity,
                        tab.parsed,
                        tab.structure,
                        tab.bondConfiguration,
                        tab.renderConfiguration.toCifDisplayMetadata(),
                        finalName,
                        tab.comments,
                        targetGroup = saveNameGroup.ifBlank { PresetRepository.MY_PRESETS_GROUP },
                    )
                }.onSuccess { showMessage("Saved to presets") }
                    .onFailure { showMessage(it.message ?: "Save failed") }
            }
        } else {
            tab.name = finalName
            save(tab)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bitmap = pendingExportBitmap
        pendingExportBitmap = null
        if (granted && bitmap != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileRepository.exportPng(activity.contentResolver, bitmap) } }
                .onSuccess { showMessage("Exported to Pictures/Krystals") }
                .onFailure { if (it !is CancellationException) showMessage(it.message ?: "Export failed") }
            bitmap.recycle()
        } else {
            bitmap?.recycle()
            showMessage("Storage permission is required on Android 8–9")
        }
    }
    fun requestExport(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExportBitmap = bitmap
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileRepository.exportPng(activity.contentResolver, bitmap) } }
                .onSuccess { showMessage("Exported to Pictures/Krystals") }
                .onFailure { if (it !is CancellationException) showMessage(it.message ?: "Export failed") }
            bitmap.recycle()
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
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
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
                            PreferencesStore.clearAll(preferences)
                            val defaults = SettingsValues.defaults()
                            settingsValues = defaults
                            PreferencesStore.save(preferences, defaults)
                            if (language != defaults.language && defaults.language != "auto") applyLanguage(defaults.language)
                            if (themeMode != defaults.theme) { themeMode = defaults.theme; preferences.edit { putString(PreferencesStore.KEY_THEME, defaults.theme.name) } }
                        },
                        onSave = { tab ->
                            // Per v0.8.36: confirm the file name in a dialog first.
                            saveNameTab = tab
                            saveNameIsPreset = false
                            saveNameDraft = tab.name.removeSuffix(".cif")
                            saveNameEdited = false
                            saveNameOpen = true
                        },
                        onOpen = { openLauncher.launch(arrayOf("chemical/x-cif", "text/plain", "application/octet-stream")) },
                        onOpenPreset = { presetOpen = true },
                        onSaveToPreset = {
                            val tab = viewModel.current ?: return@ViewerScreen
                            // Per v0.8.36: confirm the file name in a dialog first; group selectable.
                            saveNameTab = tab
                            saveNameIsPreset = true
                            saveNameDraft = tab.name.removeSuffix(".cif")
                            saveNameEdited = false
                            saveNameGroup = PresetRepository.MY_PRESETS_GROUP
                            saveNameOpen = true
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
                    )
                }
            }
        }

        // Dialogs live inside KrystalsTheme so they pick up the correct color scheme (dark/light).
        KrystalsRootDialogs(
            computingCountState = computingCountState,
            computationJobsState = computationJobsState,
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
            onOpenLink = { linkConfirmUrl = it },
            onCheckUpdates = {
                updateChecking = true
                aboutUpdateMessage = null
                updateScope.launch {
                    val info = fetchUpdateInfo()
                    updateChecking = false
                    if (info != null && info.versionCode > com.krystals.app.BuildConfig.VERSION_CODE) {
                        val skipped = preferences.getInt(PreferencesStore.KEY_SKIPPED_VERSION_CODE, -1)
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
    // Per v0.8.36: the preset library renders at the same top level as the COD/MP search
    // screens — hosting it inside KrystalsRootDialogs (with the windowed dialogs) gave its
    // DropdownMenu popups a different window context that misbehaved on some devices.
    if (presetOpen) PresetLibraryScreen(
        context = activity,
        viewModel = viewModel,
        preferences = preferences,
        autoConvertCell = settingsValues.autoConvertCell,
        onDismiss = { presetOpen = false },
        onMessage = { showMessage(it) },
        onOpenParsed = { parsed, name -> openParsed(parsed, name, null) },
    )
    if (codSearchOpen) CodSearchScreen(
        context = activity,
        viewModel = viewModel,
        autoConvertCell = settingsValues.autoConvertCell,
        codMirrorMode = settingsValues.codMirrorMode,
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
    // Per v0.8.36: save / save-to-preset file-name confirmation dialog.
    if (saveNameOpen) {
        val saveNameTitle = localized("确认文件名", "Confirm file name")
        val saveLabel = localized("保存", "Save")
        val cancelLabel = localized("取消", "Cancel")
        // Per v0.8.36: save-to-preset also picks the target user group (bundled groups excluded).
        // Per v0.8.39: group list loads off the UI thread.
        var presetGroups by remember(saveNameOpen) { mutableStateOf(emptyList<String>()) }
        LaunchedEffect(saveNameOpen, saveNameIsPreset) {
            if (saveNameIsPreset) presetGroups = PresetRepository.listGroups(activity).filter { it.isUserGroup }.map { it.name }
        }
        var groupMenuOpen by remember { mutableStateOf(false) }
        @Composable fun groupLabel(name: String): String =
            if (name == PresetRepository.MY_PRESETS_GROUP) localized("我的预设", "My Presets") else name
        AlertDialog(
            onDismissRequest = { saveNameOpen = false },
            title = { Text(saveNameTitle) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = saveNameDraft,
                            onValueChange = { new ->
                                // The first keystroke replaces the grey placeholder name.
                                if (!saveNameEdited && new.isNotEmpty()) {
                                    saveNameDraft = new
                                    saveNameEdited = true
                                } else {
                                    saveNameDraft = new.filter { c -> c != '/' && c != '\\' && c != ':' }
                                    if (new.isNotEmpty()) saveNameEdited = true
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = if (saveNameEdited) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Text(".cif", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    }
                    if (saveNameIsPreset) {
                        Text(localized("保存到组", "Save to group"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        Box {
                            OutlinedButton(onClick = { groupMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(groupLabel(saveNameGroup), modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                                presetGroups.forEach { g ->
                                    DropdownMenuItem(text = { Text(groupLabel(g)) }, onClick = { saveNameGroup = g; groupMenuOpen = false })
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::confirmSaveName) { Text(saveLabel) } },
            dismissButton = { TextButton(onClick = { saveNameOpen = false }) { Text(cancelLabel) } },
        )
    }
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
    computingCountState: MutableState<Int>,
    computationJobsState: MutableState<List<ActiveComputation>>,
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
    val scope = rememberCoroutineScope()
    var computingCount by computingCountState
    var computationJobs by computationJobsState
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

    // Per v0.8.43 (issue #5): the overlay shows while ANY computation runs and stays until all
    // finish (batch open may run several concurrently). Cancelling cancels every in-flight job;
    // their finallys unwind computingCount/computingTabIds.
    if (computingCount > 0) {
        androidx.compose.material3.BasicAlertDialog(
            onDismissRequest = {
                computationJobs.forEach { it.job.cancel() }
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
        val document = pending.document
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text(localized("选择结构", "Select structure")) },
            text = { Column { pending.candidates.forEach { index -> TextButton(onClick = {
                // Per v0.8.39: parseStructure is heavy — run off the UI thread (matches loadUri).
                // Per v0.8.43 (issue #11): open pipeline runs on openDispatcher.
                scope.launch {
                    val parseStart = System.currentTimeMillis()
                    val parsed = withContext(openDispatcher) {
                        runCatching { CifCodec.parseStructure(pending.text, index, autoConvertConventional = true) }
                    }
                    parsed.onSuccess {
                        debugLog(CIF_OPEN_TAG) { "OpenPhase: parse +${System.currentTimeMillis() - parseStart}ms (${it.structure.sites.size} sites)" }
                        debugLog(CIF_OPEN_TAG) { "OpenCIF 3/6: structure parsed (${it.structure.sites.size} sites, sg ${it.structure.spaceGroup.symbol})" }
                        pendingOpen = null; openParsed(it, pending.name, pending.uri)
                    }
                        .onFailure { if (it is com.krystals.crystal.core.CifParseException) { pendingOpen = null; cifWarningOpen = true } else showMessage(it.message ?: "Unable to open CIF") }
                }
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
                            scope.launch {
                                runCatching {
                                    PresetRepository.saveToPreset(
                                        activity, tab.parsed, tab.structure, tab.bondConfiguration,
                                        tab.renderConfiguration.toCifDisplayMetadata(), tab.name, tab.comments,
                                    )
                                }.onSuccess { showMessage("Saved to presets"); viewModel.close(index); closeRequest = null }
                                    .onFailure { showMessage(it.message ?: "Save failed") }
                            }
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
                            preferences.edit { putInt(PreferencesStore.KEY_SKIPPED_VERSION_CODE, info.versionCode) }
                            updateDialogOpen = false
                        }) { Text(localized("跳过该版本", "Skip This Version")) }
                        TextButton(onClick = { updateDialogOpen = false }) { Text(localized("暂不更新", "Update Later")) }
                    }
                }
            },
        )
    }
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
                !preferences.getBoolean(PreferencesStore.KEY_MP_CAUTION_DISMISSED, false) -> mpCautionOpen = true
                MaterialsProject.hasKey(activity) -> mpSearchOpen = true
                else -> mpKeyDialogOpen = true
            }
        },
    )
}

/**
 * Per v0.8.36: shared app menu — import/preset/online/new, optional per-screen file actions
 * (save/share/export on the viewer), the 2x2 help/feedback/about/sponsor grid, and exit.
 * Used by HomeScreen and ViewerScreen so the common items stay in sync.
 */
