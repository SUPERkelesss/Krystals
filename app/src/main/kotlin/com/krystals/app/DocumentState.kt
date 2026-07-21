package com.krystals.app

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import com.krystals.crystal.renderer.LockedMeasurement
import com.krystals.crystal.renderer.MeasurementMode
import com.krystals.crystal.renderer.ViewerVisibility
import java.util.UUID

enum class AtomEditMode { NONE, MODIFY_NEXT, DELETE_NEXT }

class DocumentTab(
    val id: String = UUID.randomUUID().toString(),
    parsed: ParsedStructure,
    structure: CrystalStructure,
    name: String,
    uri: Uri? = null,
    isNew: Boolean = false,
) {
    var parsed by mutableStateOf(parsed)
    var structure by mutableStateOf(structure)
    var name by mutableStateOf(name)
    var savedName by mutableStateOf(name)
    var uri by mutableStateOf(uri)
    var isNew by mutableStateOf(isNew)
    var dirty by mutableStateOf(false)
    var editorOpen by mutableStateOf(isNew)
    var appearance by mutableStateOf(ViewerAppearance())
    var expansion by mutableStateOf(Expansion())
    var visibility by mutableStateOf(ViewerVisibility())
    var selectedAtomIds by mutableStateOf(emptyList<Long>())
    var measurementMode by mutableStateOf(MeasurementMode.NONE)
    // Per v0.3.0: multiple measurements can be locked at once (mirrors the atom-info windows).
    var lockedMeasurements by mutableStateOf(emptyList<LockedMeasurement>())
    var atomEditMode by mutableStateOf(AtomEditMode.NONE)
    var editingSiteId by mutableStateOf<String?>(null)
    var inspectedAtomId by mutableStateOf<Long?>(null)
    // Per v0.2.4: multiple atom-info windows can be locked at once. Each locked window survives
    // starting a new inspection or moving the view. See CrystalViewport.onInspectionLockToggle.
    var lockedInspectedAtomIds by mutableStateOf(emptyList<Long>())
    // Per v0.5.0: bond-rule threshold ε (max = rA + rB + ε). Per-tab UI setting, not persisted to
    // CIF; new files default to 0.45. lastRadiusSource remembers which source the ε slider should
    // reapply when adjusted (default smart-ionic).
    var bondEpsilon by mutableStateOf(0.45)
    var lastRadiusSource by mutableStateOf(RadiusSource.SMART_IONIC)
}

class KrystalsViewModel : ViewModel() {
    val tabs = mutableStateListOf<DocumentTab>()
    var selectedIndex by mutableStateOf(0)
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
        tabs += DocumentTab(parsed = parsed, structure = parsed.structure, name = name, uri = uri, isNew = isNew).also {
            it.appearance = defaultAppearance
            // Per v0.2: when opening a CIF that already carries bond rules (e.g. other software's settings),
            // import them verbatim and do not synthesize additional rules.
            // Per v0.5.0: bond-rule synthesis (smart-ionic) can be heavy, so it is NOT done here.
            // Callers that need rules on a freshly opened tab should run addAsync / applyAutoBondRules.
        }
        selectedIndex = tabs.lastIndex
    }

    /**
     * Per v0.5.0: open a parsed structure and, if it carries no bond rules, synthesize them off the
     * UI thread via [onCompute] (which returns the structure with rules). The caller supplies the
     * suspend compute so the UI can show a "computing" overlay around it. Returns once the tab is
     * added (rules applied if needed).
     */
    suspend fun addAsync(
        parsed: ParsedStructure,
        name: String,
        uri: Uri?,
        isNew: Boolean = false,
        onCompute: suspend (CrystalStructure) -> CrystalStructure,
    ) {
        val existing = uri?.let { target -> tabs.indexOfFirst { it.uri == target } } ?: -1
        if (existing >= 0) { selectedIndex = existing; return }
        val needsRules = parsed.structure.bondRules.isEmpty()
        val structure = if (needsRules) onCompute(parsed.structure) else parsed.structure
        tabs += DocumentTab(parsed = parsed, structure = structure, name = name, uri = uri, isNew = isNew).also {
            it.appearance = defaultAppearance
        }
        selectedIndex = tabs.lastIndex
    }

    /** Per v0.5.0: synthesize bond rules for an already-added tab off the UI thread. */
    suspend fun applyAutoBondRules(tab: DocumentTab, onCompute: suspend (CrystalStructure) -> CrystalStructure) {
        if (tab.structure.bondRules.isNotEmpty()) return
        val structure = onCompute(tab.structure)
        tab.structure = structure
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
        selectedIndex = selectedIndex.coerceAtMost((tabs.size - 1).coerceAtLeast(0))
    }
    fun updateStructure(tab: DocumentTab, structure: CrystalStructure) {
        tab.structure = structure
        tab.dirty = true
        tab.selectedAtomIds = emptyList()
    }
}
