package com.krystals.crystal.core.symmetry

import com.krystals.crystal.data.SpaceGroupData

data class SpaceGroup(
    val symbol: String,
    val number: Int? = null,
    val crystalSystem: String? = null,
    val pointGroup: String? = null,
)

object SpaceGroupCatalog {
    val all: List<SpaceGroup> = SpaceGroupData.symbols.mapIndexed { index, symbol ->
        val number = index + 1
        SpaceGroup(symbol, number, crystalSystem(number), pointGroup(number))
    }

    /** Pre-normalised lookup so [find] is O(1) instead of scanning all 230 symbols per call. */
    private val byNormalizedName: Map<String, SpaceGroup> = all.associateBy { normalize(it.symbol) }

    /** Parsed symmetry operations per space-group number, filled lazily on first use. */
    private val operationsCache = mutableMapOf<Int, List<SymmetryOperation>>()

    fun find(name: String): SpaceGroup? = byNormalizedName[normalize(name)]

    fun resolve(symbol: String, number: Int? = null): SpaceGroup {
        // Per v0.6.5: strip COD hex/rhombohedral setting suffixes (:H, :R).
        // Per v0.8.27: also strip IT-origin-choice suffixes (:1, :2, ...) and any
        // other trailing ':setting' (e.g. 'I41/amd:1' -> 'I41/amd'), so they match
        // the catalog instead of falling into the unknown-group branch.
        val noSuffix = stripSettingSuffix(symbol)
        val catalog = number?.let { all.getOrNull(it - 1) } ?: find(noSuffix)
        return if (catalog == null) {
            // Unknown group — preserve original symbol verbatim (without setting suffix).
            SpaceGroup(noSuffix, number)
        } else {
            // Known group — emit the canonical catalog symbol (standard H-M form),
            // NOT the raw CIF spelling: CIFs may write 'F m -3 m', 'I4_1/amd'
            // (pymatgen underscore style) or 'I41/amd:1'; all must normalize to the
            // single canonical symbol so editors/UI show a consistent name.
            catalog.copy(symbol = catalog.symbol, number = number ?: catalog.number)
        }
    }

    fun operations(name: String): List<SymmetryOperation> {
        val number = find(name)?.number ?: return listOf(SymmetryOperation.IDENTITY)
        return operationsCache.getOrPut(number) {
            SpaceGroupData.operationsTable[number]?.map(SymmetryOperation::parse)
                ?: listOf(SymmetryOperation.IDENTITY)
        }
    }

    val RHOMBOHEDRAL_GROUPS: Set<Int> = setOf(146, 148, 155, 160, 161, 166, 167)

    fun isRhombohedral(name: String): Boolean = find(name)?.number in RHOMBOHEDRAL_GROUPS

    /** Drop any ':setting' suffix — COD hex/rhombohedral (:H/:R) and IT origin
     *  choice (:1/:2) alike. Space-group symbols never contain ':', so the first
     *  colon starts the suffix. */
    private fun stripSettingSuffix(value: String): String {
        val idx = value.indexOf(':')
        return if (idx >= 0) value.substring(0, idx) else value
    }

    private fun normalize(value: String) = stripSettingSuffix(value)
        .replace(" ", "")
        .replace("_", "")
        .lowercase()

    private fun crystalSystem(number: Int): String = when (number) {
        1, 2 -> "Triclinic"
        in 3..15 -> "Monoclinic"
        in 16..74 -> "Orthorhombic"
        in 75..142 -> "Tetragonal"
        in 143..167 -> "Trigonal"
        in 168..194 -> "Hexagonal"
        else -> "Cubic"
    }

    private fun pointGroup(number: Int): String = when (number) {
        1 -> "1"
        2 -> "-1"
        in 3..5 -> "2"
        in 6..9 -> "m"
        in 10..15 -> "2/m"
        in 16..24 -> "222"
        in 25..46 -> "mm2"
        in 47..74 -> "mmm"
        in 75..80 -> "4"
        in 81..82 -> "-4"
        in 83..88 -> "4/m"
        in 89..98 -> "422"
        in 99..110 -> "4mm"
        in 111..122 -> "-42m"
        in 123..142 -> "4/mmm"
        in 143..146 -> "3"
        in 147..148 -> "-3"
        in 149..155 -> "32"
        in 156..161 -> "3m"
        in 162..167 -> "-3m"
        in 168..173 -> "6"
        174 -> "-6"
        in 175..176 -> "6/m"
        in 177..182 -> "622"
        in 183..186 -> "6mm"
        in 187..190 -> "-6m2"
        in 191..194 -> "6/mmm"
        in 195..199 -> "23"
        in 200..206 -> "m-3"
        in 207..214 -> "432"
        in 215..220 -> "-43m"
        else -> "m-3m"
    }
}
