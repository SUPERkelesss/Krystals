package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.crystal.core.symmetry.parseFraction

data class CifToken(val value: String, val start: Int, val end: Int)

sealed interface CifItem {
    val start: Int
    val end: Int
    val tags: List<String>
}

data class CifPair(
    val tag: String,
    val value: String,
    override val start: Int,
    override val end: Int,
) : CifItem {
    override val tags = listOf(tag.lowercase())
}

data class CifLoop(
    override val tags: List<String>,
    val values: List<String>,
    override val start: Int,
    override val end: Int,
) : CifItem {
    val rowCount: Int get() = if (tags.isEmpty()) 0 else values.size / tags.size
    /** Tag → column index, built once so per-row lookups are O(1) instead of a linear scan. */
    private val tagIndex: Map<String, Int> = tags.mapIndexed { index, tag -> tag.lowercase() to index }.toMap()
    fun value(row: Int, tag: String): String? {
        val column = tagIndex[tag.lowercase()] ?: -1
        if (column < 0 || row !in 0 until rowCount) return null
        return values[row * tags.size + column]
    }
}

data class CifBlock(
    val name: String,
    val start: Int,
    val end: Int,
    val items: List<CifItem>,
) {
    fun scalar(vararg names: String): String? {
        names.forEach { wanted ->
            items.forEach { item ->
                when (item) {
                    is CifPair -> if (item.tag.equals(wanted, true)) return unquote(item.value)
                    is CifLoop -> item.value(0, wanted)?.let { return unquote(it) }
                }
            }
        }
        return null
    }

    fun loopContaining(vararg names: String): CifLoop? = items.filterIsInstance<CifLoop>().firstOrNull { loop ->
        names.any { name -> loop.tags.any { it.equals(name, true) } }
    }
}

data class CifDocument(val source: String, val blocks: List<CifBlock>)

data class CifDisplayMetadata(
    val elementArgbOverrides: Map<String, Long> = emptyMap(),
)

data class ParsedStructure(
    val document: CifDocument,
    val blockIndex: Int,
    val structure: CrystalStructure,
    val bondConfiguration: BondConfiguration,
    val displayMetadata: CifDisplayMetadata,
)

private data class ParsedBlock(
    val structure: CrystalStructure,
    val bondConfiguration: BondConfiguration,
    val displayMetadata: CifDisplayMetadata,
)

object CifCodec {
private val replacementPrefixes = listOf(
"_cell_", "_atom_site_", "_symmetry_equiv_pos_", "_space_group_symop_", "_krystals_bond_rule_", "_krystals_element_color_", "_krystals_is_conventional",
)
    private val replacementTags = setOf(
        "_symmetry_space_group_name_h-m", "_space_group_name_h-m_alt", "_symmetry_int_tables_number",
        "_space_group_it_number",
    )
    /** Trailing parenthesized uncertainty like `1.234(5)` stripped before numeric parsing. */
    private val numericParenRegex = Regex("\\([0-9]+\\)$")

    fun parse(source: String): CifDocument {
        val tokens = tokenize(source)
        val blockStarts = tokens.withIndex().filter { (_, token) -> token.value.lowercase().startsWith("data_") }
        require(blockStarts.isNotEmpty()) { "No CIF data block found" }
        val blocks = blockStarts.mapIndexed { blockOrdinal, indexed ->
            val tokenIndex = indexed.index
            val header = indexed.value
            val nextTokenIndex = blockStarts.getOrNull(blockOrdinal + 1)?.index ?: tokens.size
            val blockEnd = blockStarts.getOrNull(blockOrdinal + 1)?.value?.start ?: source.length
            CifBlock(
                name = header.value.drop(5),
                start = header.start,
                end = blockEnd,
                items = parseItems(tokens, tokenIndex + 1, nextTokenIndex),
            )
        }
        return CifDocument(source, blocks)
    }

    fun structuralBlockIndices(document: CifDocument): List<Int> = document.blocks.indices.filter { index ->
        val block = document.blocks[index]
        block.scalar("_cell_length_a") != null &&
            block.loopContaining("_atom_site_fract_x", "_atom_site_cartn_x") != null
    }

    fun parseStructure(source: String, blockIndex: Int? = null, autoConvertConventional: Boolean = true): ParsedStructure {
        val document = parse(source)
        val candidates = structuralBlockIndices(document)
        require(candidates.isNotEmpty()) { "CIF contains no structure with a unit cell and atom sites" }
        val selected = blockIndex ?: candidates.first()
        require(selected in candidates) { "Selected data block is not a crystal structure" }
        val parsedBlock = toStructure(document.blocks[selected])
        // Per v0.6.5: If toStructure detected a non-conventional cell (e.g. :R suffix or explicit
        // flag = 0), trust it. Otherwise, use the heuristic for cells without explicit flags.
        val hasExplicitFlag = document.blocks[selected].scalar("_krystals_is_conventional") != null
        val isConventional = if (!parsedBlock.structure.isConventional) {
            false
        } else if (hasExplicitFlag) {
            true
        } else {
            CrystalEditor.isConventionalCell(parsedBlock.structure)
        }
        // Per v0.8.26: when autoConvertConventional is false, keep the original cell as-is
        // (user preference to view primitive cells without automatic conversion).
        val structure = when {
            !autoConvertConventional -> parsedBlock.structure
            isConventional -> parsedBlock.structure.copy(isConventional = true)
            else -> convertPrimitiveToConventional(parsedBlock.structure, parsedBlock.bondConfiguration)
        }
        return ParsedStructure(
            document,
            selected,
            structure,
            parsedBlock.bondConfiguration,
            parsedBlock.displayMetadata,
        )
    }

    fun newDocument(structure: CrystalStructure = CrystalStructure(
        blockName = "untitled",
        lattice = Lattice.DEFAULT,
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = emptyList(),
        sites = emptyList(),
    ), bondConfiguration: BondConfiguration = BondConfiguration(), displayMetadata: CifDisplayMetadata = CifDisplayMetadata()): ParsedStructure {
        val source = canonicalStructure(structure, bondConfiguration.rules, displayMetadata, includeHeader = true)
        return parseStructureAllowEmpty(source, autoConvertConventional = true)
    }

    private fun parseStructureAllowEmpty(source: String, autoConvertConventional: Boolean = true): ParsedStructure {
        val document = parse(source)
        val parsedBlock = toStructure(document.blocks[0])
        val hasExplicitFlag = document.blocks[0].scalar("_krystals_is_conventional") != null
        val isConventional = if (!parsedBlock.structure.isConventional) {
            false
        } else if (hasExplicitFlag) {
            true
        } else {
            CrystalEditor.isConventionalCell(parsedBlock.structure)
        }
        val structure = when {
            !autoConvertConventional -> parsedBlock.structure
            isConventional -> parsedBlock.structure.copy(isConventional = true)
            else -> convertPrimitiveToConventional(parsedBlock.structure, parsedBlock.bondConfiguration)
        }
        return ParsedStructure(document, 0, structure, parsedBlock.bondConfiguration, parsedBlock.displayMetadata)
    }

    fun write(
        parsed: ParsedStructure,
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        displayMetadata: CifDisplayMetadata,
    ): String {
        val document = parsed.document
        val block = document.blocks[parsed.blockIndex]
        val source = document.source
        val replacement = canonicalStructure(structure, bondConfiguration.rules, displayMetadata, includeHeader = false)
        val out = StringBuilder(source.length + replacement.length)
        out.append(source, 0, block.start)
        var cursor = block.start
        var inserted = false
        block.items.forEach { item ->
            val replace = item.tags.any(::isReplacedTag)
            if (replace) {
                out.append(source, cursor, item.start)
                if (!inserted) {
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    out.append(replacement)
                    inserted = true
                }
                cursor = item.end
            } else {
                out.append(source, cursor, item.end)
                cursor = item.end
            }
        }
        out.append(source, cursor, block.end)
        if (!inserted) {
            val insertion = out.length - (source.length - block.end)
            out.insert(insertion, "\n$replacement")
        }
        out.append(source, block.end, source.length)
        return out.toString()
    }

    private fun toStructure(block: CifBlock): ParsedBlock {
        val lattice = Lattice(
            numeric(block.scalar("_cell_length_a")) ?: 1.0,
            numeric(block.scalar("_cell_length_b")) ?: 1.0,
            numeric(block.scalar("_cell_length_c")) ?: 1.0,
            numeric(block.scalar("_cell_angle_alpha")) ?: 90.0,
            numeric(block.scalar("_cell_angle_beta")) ?: 90.0,
            numeric(block.scalar("_cell_angle_gamma")) ?: 90.0,
        )
        val rawGroupName = block.scalar("_space_group_name_h-m_alt", "_symmetry_space_group_name_h-m") ?: "P1"
        // Per v0.6.5: detect COD hex/rhombohedral setting suffixes (:H, :R).
        // :H = hexagonal (conventional), :R = rhombohedral (primitive).
        val isRhombohedralSetting = rawGroupName.replace(" ", "").let { s ->
            s.endsWith(":R") || s.endsWith(":r")
        }
        val isHexSetting = rawGroupName.replace(" ", "").let { s ->
            s.endsWith(":H") || s.endsWith(":h")
        }
        val groupName = rawGroupName
        val groupNumber = numeric(block.scalar("_space_group_it_number", "_symmetry_int_tables_number"))?.toInt()
            ?: SpaceGroupCatalog.find(groupName)?.number

        val symmetryLoop = block.loopContaining("_space_group_symop_operation_xyz", "_symmetry_equiv_pos_as_xyz")
        val symmetryTag = symmetryLoop?.tags?.firstOrNull {
            it.equals("_space_group_symop_operation_xyz", true) || it.equals("_symmetry_equiv_pos_as_xyz", true)
        }
        val operations = if (symmetryLoop != null && symmetryTag != null) {
            (0 until symmetryLoop.rowCount).mapNotNull { row ->
                symmetryLoop.value(row, symmetryTag)?.let { runCatching { SymmetryOperation.parse(unquote(it)) }.getOrNull() }
            }
        } else emptyList()

        val atomLoop = block.loopContaining("_atom_site_fract_x", "_atom_site_cartn_x")
        val sites = if (atomLoop == null) emptyList() else (0 until atomLoop.rowCount).mapNotNull { row ->
            val label = atomLoop.firstValue(row, "_atom_site_label") ?: "Site${row + 1}"
            val rawElement = atomLoop.firstValue(row, "_atom_site_type_symbol") ?: label
            val element = PeriodicTable.normalizeElement(rawElement)
            val fractional = if (atomLoop.hasTag("_atom_site_fract_x")) {
                FractionalCoordinate(
                    numeric(atomLoop.firstValue(row, "_atom_site_fract_x")) ?: return@mapNotNull null,
                    numeric(atomLoop.firstValue(row, "_atom_site_fract_y")) ?: return@mapNotNull null,
                    numeric(atomLoop.firstValue(row, "_atom_site_fract_z")) ?: return@mapNotNull null,
                ).wrapped()
            } else {
                val cartesian = CartesianCoordinate(
                    numeric(atomLoop.firstValue(row, "_atom_site_cartn_x")) ?: return@mapNotNull null,
                    numeric(atomLoop.firstValue(row, "_atom_site_cartn_y")) ?: return@mapNotNull null,
                    numeric(atomLoop.firstValue(row, "_atom_site_cartn_z")) ?: return@mapNotNull null,
                )
                lattice.toFractional(cartesian).wrapped()
            }
            Site(
                id = uniqueSiteId(label, row),
                label = label,
                species = Species(element),
                fractionalCoordinate = fractional,
                occupancy = (numeric(atomLoop.firstValue(row, "_atom_site_occupancy")) ?: 1.0).coerceIn(0.0, 1.0),
            )
        }

        // Per v0.7.1: bond rules are never read back from CIF (always regenerated via
        // smart-ionic/bonding radii after parsing), so the _krystals_bond_rule_*/_vesta_bond_*/
        // _geom_bond_* loops are intentionally skipped here.

        val colorLoop = block.loopContaining("_krystals_element_color_symbol")
        val colorOverrides = if (colorLoop == null) emptyMap() else (0 until colorLoop.rowCount).mapNotNull { row ->
            val symbol = colorLoop.firstValue(row, "_krystals_element_color_symbol") ?: return@mapNotNull null
            val argb = colorLoop.firstValue(row, "_krystals_element_color_argb")?.toLongOrNull()
                ?: numeric(colorLoop.firstValue(row, "_krystals_element_color_argb"))?.toLong()
                ?: return@mapNotNull null
            PeriodicTable.normalizeElement(symbol) to argb
        }.toMap()

        // Per v0.6.5: determine isConventional from CIF flag, COD setting suffix, or default.
        val isConventional = numeric(block.scalar("_krystals_is_conventional"))?.let { it >= 0.5 }
            ?: when {
                isRhombohedralSetting -> false
                isHexSetting -> true
                else -> true
            }

        return ParsedBlock(
            structure = CrystalStructure(
                block.name,
                lattice,
                SpaceGroupCatalog.resolve(groupName, groupNumber),
                operations,
                sites,
                isConventional = isConventional,
            ),
            // Per v0.7.1: completely disable reading bond rules from CIF. Bond rules are always
            // regenerated via smart-ionic/bonding radii after parsing. This prevents stale or
            // incorrect rules (e.g. Cr-Cr in Cr2O3) from being loaded from CIF files.
            bondConfiguration = BondConfiguration(),
            displayMetadata = CifDisplayMetadata(colorOverrides),
        )
    }

    private fun canonicalStructure(
        structure: CrystalStructure,
        rules: List<BondRule>,
        displayMetadata: CifDisplayMetadata,
        includeHeader: Boolean,
    ): String = buildString {
        if (includeHeader) append("data_${sanitizeBlockName(structure.blockName)}\n")
        append("_space_group_name_H-M_alt   '${structure.spaceGroup.symbol}'\n")
        structure.spaceGroup.number?.let { append("_space_group_IT_number   $it\n") }
        append("_cell_length_a   ${format(structure.lattice.a)}\n")
        append("_cell_length_b   ${format(structure.lattice.b)}\n")
        append("_cell_length_c   ${format(structure.lattice.c)}\n")
        append("_cell_angle_alpha   ${format(structure.lattice.alpha)}\n")
        append("_cell_angle_beta   ${format(structure.lattice.beta)}\n")
        append("_cell_angle_gamma   ${format(structure.lattice.gamma)}\n")
        append("_krystals_is_conventional   ${if (structure.isConventional) 1 else 0}\n")
        append("loop_\n _space_group_symop_id\n _space_group_symop_operation_xyz\n")
        structure.effectiveSymmetryOperations.forEachIndexed { index, operation ->
            append(" ${index + 1} '${operation.source}'\n")
        }
        append("loop_\n _atom_site_label\n _atom_site_type_symbol\n _atom_site_fract_x\n _atom_site_fract_y\n _atom_site_fract_z\n _atom_site_occupancy\n")
        structure.sites.forEach { site ->
            append(" ${quoteIfNeeded(site.label)} ${quoteIfNeeded(site.species.symbol)} ${format(site.fractionalCoordinate.x)} ${format(site.fractionalCoordinate.y)} ${format(site.fractionalCoordinate.z)} ${format(site.occupancy)}\n")
        }
        if (rules.isNotEmpty()) {
            append("loop_\n _krystals_bond_rule_site_a\n _krystals_bond_rule_site_b\n _krystals_bond_rule_min_distance\n _krystals_bond_rule_max_distance\n _krystals_bond_rule_extend_a_to_b\n _krystals_bond_rule_extend_b_to_a\n")
            // Per v0.8.1: Hbond rules are regenerated on every smart-ionic run — don't persist them.
            rules.filter { !it.isHBond }.sortedBy { it.key }.forEach { rule ->
                val labelA = structure.sites.firstOrNull { it.id == rule.siteA }?.label ?: rule.siteA
                val labelB = structure.sites.firstOrNull { it.id == rule.siteB }?.label ?: rule.siteB
                append(" ${quoteIfNeeded(labelA)} ${quoteIfNeeded(labelB)} ${format(rule.minAngstrom)} ${format(rule.maxAngstrom)} ${if (rule.extendAtoB) 1 else 0} ${if (rule.extendBtoA) 1 else 0}\n")
            }
        }
        if (displayMetadata.elementArgbOverrides.isNotEmpty()) {
            append("loop_\n _krystals_element_color_symbol\n _krystals_element_color_argb\n")
            displayMetadata.elementArgbOverrides.entries.sortedBy { it.key }.forEach { (symbol, argb) ->
                append(" ${quoteIfNeeded(symbol)} $argb\n")
            }
        }
    }

    private fun parseItems(tokens: List<CifToken>, start: Int, endExclusive: Int): List<CifItem> {
        val items = mutableListOf<CifItem>()
        var index = start
        while (index < endExclusive) {
            val token = tokens[index]
            val lower = token.value.lowercase()
            when {
                lower == "loop_" -> {
                    val loopStart = token.start
                    index++
                    val tags = mutableListOf<String>()
                    while (index < endExclusive && tokens[index].value.startsWith('_')) {
                        tags += tokens[index].value.lowercase()
                        index++
                    }
                    if (tags.isEmpty()) continue
                    val values = mutableListOf<String>()
                    while (index < endExclusive) {
                        val next = tokens[index].value
                        val control = next.equals("loop_", true) || next.lowercase().startsWith("data_") ||
                            next.lowercase().startsWith("save_") || next.startsWith('_')
                        if (control && values.size % tags.size == 0) break
                        values += next
                        index++
                    }
                    val completeValues = values.take(values.size - values.size % tags.size)
                    val itemEnd = tokens[index - 1].end
                    items += CifLoop(tags, completeValues, loopStart, itemEnd)
                }
                token.value.startsWith('_') && index + 1 < endExclusive -> {
                    val value = tokens[index + 1]
                    items += CifPair(token.value, value.value, token.start, value.end)
                    index += 2
                }
                else -> index++
            }
        }
        return items
    }

    private fun tokenize(source: String): List<CifToken> {
        val tokens = mutableListOf<CifToken>()
        var index = 0
        fun atLineStart(i: Int) = i == 0 || source[i - 1] == '\n' || source[i - 1] == '\r'
        while (index < source.length) {
            val ch = source[index]
            when {
                ch == '\n' -> index++
                ch.isWhitespace() -> index++
                ch == '#' -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                ch == ';' && atLineStart(index) -> {
                    val start = index
                    index++
                    while (index < source.length) {
                        if (source[index] == ';' && atLineStart(index)) {
                            index++
                            break
                        }
                        index++
                    }
                    tokens += CifToken(source.substring(start, index), start, index)
                }
                ch == '\'' || ch == '"' -> {
                    val quote = ch
                    val start = index
                    index++
                    while (index < source.length) {
                        if (source[index] == quote && (index + 1 == source.length || source[index + 1].isWhitespace())) {
                            index++
                            break
                        }
                        index++
                    }
                    tokens += CifToken(source.substring(start, index), start, index)
                }
                else -> {
                    val start = index
                    while (index < source.length && !source[index].isWhitespace()) {
                        if (source[index] == '#' && index == start) break
                        index++
                    }
                    if (index > start) tokens += CifToken(source.substring(start, index), start, index)
                    else index++
                }
            }
        }
        return tokens
    }

    private fun CifLoop.hasTag(tag: String) = tags.any { it.equals(tag, true) }
    private fun CifLoop.firstValue(row: Int, tag: String) = value(row, tag)?.let(::unquote)?.takeUnless { it == "." || it == "?" }
    private fun isReplacedTag(tag: String): Boolean {
        val normalized = tag.lowercase()
        return normalized in replacementTags || replacementPrefixes.any(normalized::startsWith)
    }
    private fun numeric(value: String?): Double? {
        if (value == null || value == "." || value == "?") return null
        val central = unquote(value).replace(numericParenRegex, "")
        return runCatching { parseFraction(central) }.getOrNull()
    }
    private fun uniqueSiteId(label: String, row: Int) = "${label.trim()}#${row + 1}"
    private fun sanitizeBlockName(name: String) = name.ifBlank { "untitled" }.replace(Regex("\\s+"), "_")
    private fun format(value: Double): String = "%.8f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.').ifBlank { "0" }
    private fun quoteIfNeeded(value: String) = if (value.any { it.isWhitespace() } || value.isEmpty()) "'${value.replace("'", "")}'" else value

    /**
     * Per v0.6.5: Convert a primitive cell to conventional using CrystalEditor,
     * then apply lattice parameter adjustment for the crystal system.
     */
    private fun convertPrimitiveToConventional(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
    ): CrystalStructure {
        val result = CrystalEditor.convertToConventional(structure, bondConfiguration)
        return result.structure
    }
}

private fun unquote(value: String): String {
    val trimmed = value.trim()
    return when {
        trimmed.length >= 2 && ((trimmed.first() == '\'' && trimmed.last() == '\'') || (trimmed.first() == '"' && trimmed.last() == '"')) -> trimmed.substring(1, trimmed.length - 1)
        trimmed.startsWith(';') -> trimmed.removePrefix(";").removeSuffix(";").trim('\r', '\n')
        else -> trimmed
    }
}
