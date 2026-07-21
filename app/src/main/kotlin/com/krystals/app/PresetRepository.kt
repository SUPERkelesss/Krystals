package com.krystals.app

import android.content.Context
import android.net.Uri
import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.CifDisplayMetadata
import com.krystals.crystal.io.ParsedStructure
import java.io.File

enum class PresetSource { BUNDLED, USER }

data class PresetEntry(val name: String, val source: PresetSource, val assetPath: String? = null, val file: File? = null, val category: String? = null)

object PresetRepository {
    private const val ASSET_DIR = "cifs_example"
    private const val USER_DIR = "presets"

    /** Recursively collect bundled `.cif` files under [dir] (relative to assets root), tagging each with its [category]. */
    private fun collectBundled(context: Context, dir: String, category: String?): List<PresetEntry> {
        val entries = runCatching { context.assets.list(dir).orEmpty().toList() }.getOrDefault(emptyList())
        val result = mutableListOf<PresetEntry>()
        for (entry in entries) {
            val relPath = if (dir.isEmpty()) entry else "$dir/$entry"
            if (entry.endsWith(".cif", ignoreCase = true)) {
                result += PresetEntry(entry, PresetSource.BUNDLED, assetPath = relPath, category = category)
            } else {
                // A subdirectory: descend. Its own name becomes the category for the files within.
                result += collectBundled(context, relPath, entry)
            }
        }
        return result
    }

    fun listPresets(context: Context): List<PresetEntry> {
        // Bundled presets live in nested subdirectories of cifs_example (01_basic, 02_oxides, …).
        // A flat assets.list() only returns the subdirectory names, so we recurse.
        val bundled = collectBundled(context, ASSET_DIR, null)
        val userDir = File(context.filesDir, USER_DIR).apply { if (!exists()) mkdirs() }
        val user = userDir.listFiles { file -> file.extension.equals("cif", ignoreCase = true) }.orEmpty()
            .sortedBy { it.name }
            // Per v0.2.2: user-saved presets form their own group, shown first.
            .map { PresetEntry(it.name, PresetSource.USER, file = it, category = "__user__") }
        return user + bundled
    }

    fun openPreset(context: Context, entry: PresetEntry): ParsedStructure {
        val text = when (entry.source) {
            PresetSource.BUNDLED -> context.assets.open(entry.assetPath!!).bufferedReader().use { it.readText() }
            PresetSource.USER -> entry.file!!.readText()
        }
        val parsed = CifCodec.parseStructure(text)
        // Per v0.2: only synthesize bond rules when the CIF has none of its own.
        // Per v0.5.0: rule synthesis (smart-ionic) is deferred to the caller's async path so the UI
        // can show a "computing" overlay — return the parsed structure as-is here.
        return parsed
    }

    fun saveToPreset(
        context: Context,
        parsed: ParsedStructure,
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        displayMetadata: CifDisplayMetadata,
        name: String,
    ): File {
        val userDir = File(context.filesDir, USER_DIR).apply { if (!exists()) mkdirs() }
        val safeName = name.ifBlank { "structure.cif" }.let { if (it.endsWith(".cif", true)) it else "$it.cif" }
        val target = File(userDir, safeName)
        val content = CifCodec.write(parsed, structure, bondConfiguration, displayMetadata)
        target.writeText(content, Charsets.UTF_8)
        return target
    }

    fun deletePreset(entry: PresetEntry): Boolean {
        if (entry.source != PresetSource.USER) return false
        return entry.file?.delete() == true
    }
}
