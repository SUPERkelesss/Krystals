package com.krystals.app

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.krystals.core.CifCodec
import com.krystals.core.CrystalEditor
import com.krystals.core.CrystalStructure
import com.krystals.core.Expansion
import com.krystals.core.ParsedStructure
import com.krystals.core.ViewerAppearance
import com.krystals.renderer.MeasurementMode
import com.krystals.renderer.ViewerVisibility
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
    var measurementLocked by mutableStateOf(false)
    var atomEditMode by mutableStateOf(AtomEditMode.NONE)
    var editingSiteId by mutableStateOf<String?>(null)
    var inspectedAtomId by mutableStateOf<Long?>(null)
    var inspectionLocked by mutableStateOf(false)
}

class KrystalsViewModel : ViewModel() {
    val tabs = mutableStateListOf<DocumentTab>()
    var selectedIndex by mutableStateOf(0)
        private set
    var defaultAppearance by mutableStateOf(ViewerAppearance())

    val current: DocumentTab? get() = tabs.getOrNull(selectedIndex)

    fun add(parsed: ParsedStructure, name: String, uri: Uri?, isNew: Boolean = false) {
        val existing = uri?.let { target -> tabs.indexOfFirst { it.uri == target } } ?: -1
        if (existing >= 0) { selectedIndex = existing; return }
        tabs += DocumentTab(parsed = parsed, structure = parsed.structure, name = name, uri = uri, isNew = isNew).also {
            it.appearance = defaultAppearance
            it.structure = CrystalEditor.ensureAutoBondRules(it.structure).structure
        }
        selectedIndex = tabs.lastIndex
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
