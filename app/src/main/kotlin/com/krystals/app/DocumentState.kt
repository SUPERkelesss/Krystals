package com.krystals.app

import android.net.Uri
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.editing.EditResult
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.CifDisplayMetadata
import com.krystals.crystal.io.ParsedStructure
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.measure.MeasurementSelection as LockedMeasurement
import com.krystals.interaction.state.InspectionState
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.InteractionReducer
import com.krystals.interaction.state.SelectionState
import com.krystals.interaction.state.ViewerCommand
import com.krystals.interaction.state.ViewerDocumentState
import com.krystals.interaction.state.VisibilityState as ViewerVisibility
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import java.util.UUID

enum class AtomEditMode { NONE, MODIFY_NEXT, DELETE_NEXT }

// Per v0.7.1: bond draw mode — user picks two atoms in the viewer to pre-fill a bond rule.
// Per v0.7.1: DELETING mode — user picks two atoms to delete the bond between them.
enum class BondDrawMode { NONE, DRAWING, DELETING }

data class DocumentSnapshot(
    val structure: CrystalStructure,
    val bondConfiguration: BondConfiguration,
    val renderConfiguration: RenderConfiguration,
    val name: String,
    val dirty: Boolean,
    val editorOpen: Boolean,
    val appearance: ViewerAppearance,
    val expansion: Expansion,
    val interactionDocument: ViewerDocumentState,
    val atomEditMode: AtomEditMode,
    val editingSiteId: String?,
    val bondEpsilon: Double,
    val lastRadiusSource: RadiusSource,
    val isPrimitiveCell: Boolean,
    val savedConventionalStructure: CrystalStructure?,
    val savedConventionalBondConfig: BondConfiguration?,
    val savedPrimitiveStructure: CrystalStructure?,
    val savedPrimitiveBondConfig: BondConfiguration?,
    val structuralExpansion: Boolean,
) {
    fun restore(tab: DocumentTab) {
        tab.structure = structure; tab.bondConfiguration = bondConfiguration; tab.renderConfiguration = renderConfiguration
        tab.name = name; tab.dirty = dirty; tab.editorOpen = editorOpen; tab.appearance = appearance
        tab.expansion = expansion; tab.interactionState = tab.interactionState.copy(document = interactionDocument)
        tab.atomEditMode = atomEditMode; tab.editingSiteId = editingSiteId
        tab.bondEpsilon = bondEpsilon; tab.lastRadiusSource = lastRadiusSource
        tab.isPrimitiveCell = isPrimitiveCell
        tab.savedConventionalStructure = savedConventionalStructure
        tab.savedConventionalBondConfig = savedConventionalBondConfig
        tab.savedPrimitiveStructure = savedPrimitiveStructure
        tab.savedPrimitiveBondConfig = savedPrimitiveBondConfig
        tab.structuralExpansion = structuralExpansion
    }
    companion object {
        fun capture(tab: DocumentTab) = DocumentSnapshot(
            tab.structure, tab.bondConfiguration, tab.renderConfiguration, tab.name, tab.dirty, tab.editorOpen,
            tab.appearance, tab.expansion, tab.interactionState.document,
            tab.atomEditMode, tab.editingSiteId, tab.bondEpsilon, tab.lastRadiusSource,
            tab.isPrimitiveCell,
            tab.savedConventionalStructure, tab.savedConventionalBondConfig,
            tab.savedPrimitiveStructure, tab.savedPrimitiveBondConfig,
            tab.structuralExpansion,
        )
    }
}

class DocumentTab(
    val id: String = UUID.randomUUID().toString(),
    parsed: ParsedStructure,
    structure: CrystalStructure,
    bondConfiguration: BondConfiguration,
    renderConfiguration: RenderConfiguration,
    name: String,
    uri: Uri? = null,
    isNew: Boolean = false,
) {
    var parsed by mutableStateOf(parsed)
    var structure by mutableStateOf(structure)
    var bondConfiguration by mutableStateOf(bondConfiguration)
    var renderConfiguration by mutableStateOf(renderConfiguration)
    var name by mutableStateOf(name)
    var savedName by mutableStateOf(name)
    var uri by mutableStateOf(uri)
    var isNew by mutableStateOf(isNew)
    var dirty by mutableStateOf(false)
    var editorOpen by mutableStateOf(isNew)
    var appearance by mutableStateOf(ViewerAppearance())
    var expansion by mutableStateOf(Expansion())
    var interactionState by mutableStateOf(InteractionState())
    var visibility: ViewerVisibility
        get() = interactionState.document.visibility
        set(value) { interactionState = InteractionReducer.reduce(interactionState, ViewerCommand.SetVisibility(value)) }
    var selectedAtomIds: List<Long>
        get() = interactionState.document.selection.selectedAtomIds
        set(value) { interactionState = interactionState.copy(document = interactionState.document.copy(selection = SelectionState(value))) }
    var measurementMode: MeasurementMode
        get() = interactionState.document.measurementMode
        set(value) { interactionState = interactionState.copy(document = interactionState.document.copy(measurementMode = value)) }
    var lockedMeasurements: List<LockedMeasurement>
        get() = interactionState.document.lockedMeasurements
        set(value) { interactionState = interactionState.copy(document = interactionState.document.copy(lockedMeasurements = value)) }
    var atomEditMode by mutableStateOf(AtomEditMode.NONE)
    var editingSiteId by mutableStateOf<String?>(null)
    var inspectedAtomId: Long?
        get() = interactionState.document.inspection.inspectedAtomId
        set(value) { interactionState = interactionState.copy(document = interactionState.document.copy(inspection = interactionState.document.inspection.copy(inspectedAtomId = value))) }
    // Per v0.2.4: multiple atom-info windows can be locked at once. Each locked window survives
    // starting a new inspection or moving the view. See CrystalViewport.onInspectionLockToggle.
    var lockedInspectedAtomIds: List<Long>
        get() = interactionState.document.inspection.lockedInspectedAtomIds
        set(value) { interactionState = interactionState.copy(document = interactionState.document.copy(inspection = interactionState.document.inspection.copy(lockedInspectedAtomIds = value))) }
    // Per v0.5.0: bond-rule threshold ε (max = rA + rB + ε). Per-tab UI setting, not persisted to
    // CIF; new files default to 0.45. lastRadiusSource remembers which source the ε slider should
    // reapply when adjusted (default smart-ionic).
    var bondEpsilon by mutableDoubleStateOf(0.45)
    var lastRadiusSource by mutableStateOf(RadiusSource.SMART_IONIC)
    // Per v0.7.0: user crystal comments stored in CIF as "# Krystals Comments" block.
    var comments by mutableStateOf("")
    var commentsOpen by mutableStateOf(false)
    // Per v0.8.0: tracks whether the cell is currently in primitive form (after convertToPrimitive).
    var isPrimitiveCell by mutableStateOf(false)
    // Per v0.8.1: remember the original conventional/primitive cell so converting back restores
    // the exact data without matrix rounding errors.
    var savedConventionalStructure by mutableStateOf<CrystalStructure?>(null)
    var savedConventionalBondConfig by mutableStateOf<BondConfiguration?>(null)
    var savedPrimitiveStructure by mutableStateOf<CrystalStructure?>(null)
    var savedPrimitiveBondConfig by mutableStateOf<BondConfiguration?>(null)
// Per v0.6.5: true when the current expansion is structural (from a 3×3 matrix transform),
// not a display-only supercell. Controls whether SINGLE_CELL frame mode shows the
// entire supercell frame or just one cell.
    var structuralExpansion by mutableStateOf(false)
    // Per v0.7.1: when non-null, EditorPanel opens on this tab ("atoms", "bonds", etc.)
    var pendingEditorTab by mutableStateOf<String?>(null)
    // Per v0.7.1: bond draw state — when DRAWING, atom taps are intercepted to pick two atoms
    // and pre-fill a BondRuleDialog. pendingBondDrawRule holds the preset until BondEditor opens.
    var bondDrawMode by mutableStateOf(BondDrawMode.NONE)
    var bondDrawFirstSiteId by mutableStateOf<String?>(null)
    var bondDrawFirstCartesian by mutableStateOf<Vec3?>(null)
    var pendingBondDrawRule by mutableStateOf<BondRule?>(null)
    val history = HistoryController<DocumentSnapshot>(10)
    var historyVersion by mutableIntStateOf(0)
    var floatingPosition by mutableStateOf(FloatingBallPosition())
    fun recordHistory() { history.record(DocumentSnapshot.capture(this)); historyVersion++ }
    fun undo() { history.undo(DocumentSnapshot.capture(this))?.let { it.restore(this); historyVersion++ } }
    fun redo() { history.redo(DocumentSnapshot.capture(this))?.let { it.restore(this); historyVersion++ } }
}

class KrystalsViewModel : ViewModel() {
    val tabs = mutableStateListOf<DocumentTab>()
    var selectedIndex by mutableIntStateOf(0)
        private set
    var defaultAppearance by mutableStateOf(ViewerAppearance())

    val current: DocumentTab? get() = tabs.getOrNull(selectedIndex)

    // Per v0.5.2a: set the global appearance — default + every open tab — in one go (used both at
    // startup to load the persisted value and when the user saves in the Appearance dialog).
    fun applyAppearance(ap: ViewerAppearance) {
        defaultAppearance = ap
        tabs.forEach { it.appearance = ap }
    }

    fun add(parsed: ParsedStructure, name: String, uri: Uri?, isNew: Boolean = false) {
        val existing = uri?.let { target -> tabs.indexOfFirst { it.uri == target } } ?: -1
        if (existing >= 0) { selectedIndex = existing; return }
        tabs += DocumentTab(
            parsed = parsed,
            structure = parsed.structure,
            // Per v0.6.5: ignore bond rules written in the CIF; always start with an empty configuration.
            bondConfiguration = BondConfiguration(),
            renderConfiguration = parsed.displayMetadata.toRenderConfiguration(),
            name = name,
            uri = uri,
            isNew = isNew,
        ).also {
            it.appearance = defaultAppearance
            // Per v0.5.2b: always synthesize bond rules, ignoring any rules carried in the CIF.
            // Per v0.5.0: bond-rule synthesis (smart-ionic) can be heavy, so it is NOT done here.
            // Callers that need rules on a freshly opened tab should run addAsync / applyAutoBondRules.
        }
        selectedIndex = tabs.lastIndex
    }

    /**
     * Per v0.5.0: open a parsed structure and, if it carries no bond rules, synthesize them off the
     * UI thread via [onCompute] (which returns both the structure and bond configuration). The caller supplies the
     * suspend compute so the UI can show a "computing" overlay around it. Returns once the tab is
     * added (rules applied if needed).
     */
    suspend fun addAsync(
        parsed: ParsedStructure,
        name: String,
        uri: Uri?,
        isNew: Boolean = false,
        onCompute: suspend (CrystalStructure, BondConfiguration) -> EditResult,
    ) {
        val existing = uri?.let { target -> tabs.indexOfFirst { it.uri == target } } ?: -1
        if (existing >= 0) { selectedIndex = existing; return }
        // Per v0.6.5: ignore bond rules written in the CIF; always start with an empty configuration.
        val result = onCompute(parsed.structure, BondConfiguration())
        tabs += DocumentTab(
            parsed = parsed,
            structure = result.structure,
            bondConfiguration = result.bondConfiguration,
            renderConfiguration = parsed.displayMetadata.toRenderConfiguration(),
            name = name,
            uri = uri,
            isNew = isNew,
        ).also {
            it.appearance = defaultAppearance
        }
        selectedIndex = tabs.lastIndex
    }

    /** Per v0.5.0: synthesize bond rules for an already-added tab off the UI thread. */
    suspend fun applyAutoBondRules(
        tab: DocumentTab,
        onCompute: suspend (CrystalStructure, BondConfiguration) -> EditResult,
    ) {
        val result = onCompute(tab.structure, tab.bondConfiguration)
        tab.structure = result.structure
        tab.bondConfiguration = result.bondConfiguration
        tab.dirty = true
    }

    fun createNew() {
        val parsed = CifCodec.newDocument()
        var suffix = 1
        var name = "Untitled.cif"
        while (tabs.any { it.name == name }) { suffix++; name = "Untitled $suffix.cif" }
        add(parsed, name, null, isNew = true)
    }

    fun select(index: Int) { if (index in tabs.indices) selectedIndex = index }
    fun move(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        val tab = tabs.removeAt(from)
        tabs.add(to, tab)
        selectedIndex = to
    }
    fun close(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        selectedIndex = when {
            tabs.isEmpty() -> 0
            selectedIndex == index -> (index - 1).coerceAtLeast(0)
            selectedIndex > index -> selectedIndex - 1
            else -> selectedIndex.coerceAtMost(tabs.lastIndex)
        }
    }
    fun updateAnalysis(tab: DocumentTab, result: EditResult) {
        tab.recordHistory()
        tab.structure = result.structure
        tab.bondConfiguration = result.bondConfiguration
        tab.dirty = true
        tab.selectedAtomIds = emptyList()
    }
}

internal fun CifDisplayMetadata.toRenderConfiguration() = RenderConfiguration(
    elementArgbOverrides = elementArgbOverrides,
)

internal fun RenderConfiguration.toCifDisplayMetadata() = CifDisplayMetadata(
    elementArgbOverrides = elementArgbOverrides,
)
