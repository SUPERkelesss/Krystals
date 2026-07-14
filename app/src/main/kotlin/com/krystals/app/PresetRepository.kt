package com.krystals.app

import android.content.Context
import android.net.Uri
import com.krystals.core.CifCodec
import com.krystals.core.CrystalEditor
import com.krystals.core.CrystalStructure
import com.krystals.core.ParsedStructure
import java.io.File

enum class PresetSource { BUNDLED, USER }

data class PresetEntry(val name: String, val source: PresetSource, val assetPath: String? = null, val file: File? = null)

object PresetRepository {
    private const val ASSET_DIR = "cifs_example"
    private const val USER_DIR = "presets"

    fun listPresets(context: Context): List<PresetEntry> {
        val bundled = runCatching { context.assets.list(ASSET_DIR).orEmpty().toList() }.getOrDefault(emptyList())
            .filter { it.endsWith(".cif", ignoreCase = true) }
            .map { PresetEntry(it, PresetSource.BUNDLED, assetPath = "$ASSET_DIR/$it") }
        val userDir = File(context.filesDir, USER_DIR).apply { if (!exists()) mkdirs() }
        val user = userDir.listFiles { file -> file.extension.equals("cif", ignoreCase = true) }.orEmpty()
            .sortedBy { it.name }
            .map { PresetEntry(it.name, PresetSource.USER, file = it) }
        return bundled + user
    }

    fun openPreset(context: Context, entry: PresetEntry): ParsedStructure {
        val text = when (entry.source) {
            PresetSource.BUNDLED -> context.assets.open(entry.assetPath!!).bufferedReader().use { it.readText() }
            PresetSource.USER -> entry.file!!.readText()
        }
        val parsed = CifCodec.parseStructure(text)
        return parsed.copy(structure = CrystalEditor.ensureAutoBondRules(parsed.structure).structure)
    }

    fun saveToPreset(context: Context, parsed: ParsedStructure, structure: CrystalStructure, name: String): File {
        val userDir = File(context.filesDir, USER_DIR).apply { if (!exists()) mkdirs() }
        val safeName = name.ifBlank { "structure.cif" }.let { if (it.endsWith(".cif", true)) it else "$it.cif" }
        val target = File(userDir, safeName)
        val content = CifCodec.write(parsed, structure, structure.bondRules)
        target.writeText(content, Charsets.UTF_8)
        return target
    }

    fun deletePreset(entry: PresetEntry): Boolean {
        if (entry.source != PresetSource.USER) return false
        return entry.file?.delete() == true
    }
}
