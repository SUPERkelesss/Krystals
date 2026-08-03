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

/** Per v0.8.34: a first-level group in the preset library (a bundled asset subdirectory or a user folder). */
data class PresetGroup(val name: String, val isUserGroup: Boolean, val entries: List<PresetEntry>)

/** Per v0.8.34: filter metadata extracted from a preset CIF. */
data class PresetMeta(
    val formula: String,
    val elementCount: Int,
    val crystalSystem: String?,
    val pointGroup: String?,
    val spaceGroup: String?,
)

object PresetRepository {
    private const val ASSET_DIR = "cifs_example"
    private const val USER_DIR = "presets"

    /** Per v0.8.34: the default user group, created on first save/open. */
    const val MY_PRESETS_GROUP = "我的预设"

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

    /** Per v0.8.34: `presets/` root directory, created on demand. */
    private fun userRoot(context: Context): File =
        File(context.filesDir, USER_DIR).apply { if (!exists()) mkdirs() }

    /** Per v0.8.34: the `presets/我的预设/` directory (default save target), created on demand. */
    fun myPresetsDir(context: Context): File =
        File(userRoot(context), MY_PRESETS_GROUP).apply { if (!exists()) mkdirs() }

    /**
     * Per v0.8.34: list the library as first-level groups, each with its files.
     * User groups are the first-level directories under `presets/`; loose `.cif`
     * files (legacy flat saves) are folded into the "我的预设" group.
     */
    fun listGroups(context: Context): List<PresetGroup> {
        val bundledByCategory = collectBundled(context, ASSET_DIR, null).groupBy { it.category ?: "bundled" }
        val bundledGroups = bundledByCategory.entries
            .sortedBy { it.key }
            .map { (category, entries) -> PresetGroup(category, isUserGroup = false, entries = entries.sortedBy { it.name }) }

        val root = userRoot(context)
        val looseFiles = root.listFiles { f -> f.extension.equals("cif", ignoreCase = true) }.orEmpty()
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        val myPresetsFiles = looseFiles.map { PresetEntry(it.name, PresetSource.USER, file = it, category = MY_PRESETS_GROUP) }
        val userGroups = dirs.map { dir ->
            val entries = dir.listFiles { f -> f.extension.equals("cif", ignoreCase = true) }.orEmpty()
                .sortedBy { it.name }
                .map { PresetEntry(it.name, PresetSource.USER, file = it, category = dir.name) }
            PresetGroup(dir.name, isUserGroup = true, entries = entries)
        }.toMutableList()
        // The default group always exists (even when only loose legacy files are present).
        val myIndex = userGroups.indexOfFirst { it.name == MY_PRESETS_GROUP }
        if (myIndex >= 0) {
            userGroups[myIndex] = userGroups[myIndex].copy(entries = (myPresetsFiles + userGroups[myIndex].entries))
        } else {
            userGroups.add(0, PresetGroup(MY_PRESETS_GROUP, isUserGroup = true, entries = myPresetsFiles))
        }
        return userGroups + bundledGroups
    }

    /** Per v0.8.34: create a new first-level user group; returns the directory or null on conflict/failure. */
    fun createGroup(context: Context, name: String): File? {
        val safe = name.trim()
        if (safe.isEmpty()) return null
        val dir = File(userRoot(context), safe)
        return if (dir.exists() || dir.mkdirs()) dir else null
    }

    /** Per v0.8.34: rename a first-level user group. */
    fun renameGroup(context: Context, oldName: String, newName: String): Boolean {
        val safe = newName.trim()
        if (safe.isEmpty() || safe == oldName) return false
        val old = File(userRoot(context), oldName)
        val target = File(userRoot(context), safe)
        if (!old.isDirectory || target.exists()) return false
        return old.renameTo(target)
    }

    /** Per v0.8.34: move a user preset file into another first-level group. */
    fun movePreset(context: Context, entry: PresetEntry, targetGroup: String): Boolean {
        if (entry.source != PresetSource.USER) return false
        val file = entry.file ?: return false
        val targetDir = File(userRoot(context), targetGroup)
        if (!targetDir.isDirectory) return false
        val target = File(targetDir, file.name)
        if (target.exists()) return false
        return file.renameTo(target)
    }

    fun openPreset(context: Context, entry: PresetEntry, autoConvertConventional: Boolean = true): ParsedStructure {
        val text = when (entry.source) {
            PresetSource.BUNDLED -> context.assets.open(entry.assetPath!!).bufferedReader().use { it.readText() }
            PresetSource.USER -> entry.file!!.readText()
        }
        val parsed = CifCodec.parseStructure(text, autoConvertConventional = autoConvertConventional)
        // Per v0.2: only synthesize bond rules when the CIF has none of its own.
        // Per v0.5.0: rule synthesis (smart-ionic) is deferred to the caller's async path so the UI
        // can show a "computing" overlay — return the parsed structure as-is here.
        return parsed
    }

    /** Per v0.8.34: parse a preset CIF's filter metadata (formula/element count/space group info). */
    fun parseMeta(text: String): PresetMeta? {
        return runCatching {
            val parsed = CifCodec.parseStructure(text, autoConvertConventional = true)
            val structure = parsed.structure
            val sg = structure.spaceGroup
            val resolved = if (sg.crystalSystem != null) sg else com.krystals.crystal.core.symmetry.SpaceGroupCatalog.find(sg.symbol)
            val formula = com.krystals.crystal.analysis.structure.StructureAnalyzer.info(structure).reducedFormula
            PresetMeta(
                formula = formula,
                elementCount = Regex("[A-Z][a-z]?").findAll(formula).map { m -> m.value }.distinct().count(),
                crystalSystem = resolved?.crystalSystem,
                pointGroup = resolved?.pointGroup,
                spaceGroup = sg.symbol,
            )
        }.getOrNull()
    }

    fun saveToPreset(
        context: Context,
        parsed: ParsedStructure,
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        displayMetadata: CifDisplayMetadata,
        name: String,
        comments: String = "",
    ): File {
        // Per v0.8.34: user presets are saved into the "我的预设" group directory.
        val userDir = myPresetsDir(context)
        val safeName = name.ifBlank { "structure.cif" }.let { if (it.endsWith(".cif", true)) it else "$it.cif" }
        val target = File(userDir, safeName)
        val content = CifCodec.write(parsed, structure, bondConfiguration, displayMetadata)
        // Per v0.7.0: inject user comments into CIF before saving to preset.
        val contentWithComments = CifComments.inject(content, comments)
        target.writeText(contentWithComments, Charsets.UTF_8)
        return target
    }

    fun deletePreset(entry: PresetEntry): Boolean {
        if (entry.source != PresetSource.USER) return false
        return entry.file?.delete() == true
    }
}
