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

    fun find(name: String): SpaceGroup? {
        val normalized = normalize(name)
        return all.firstOrNull { normalize(it.symbol) == normalized }
    }

    fun resolve(symbol: String, number: Int? = null): SpaceGroup {
        // Per v0.6.3: normalize symbol by removing spaces (CIF files often write "F m -3 m").
        val normalized = symbol.replace(" ", "")
        val catalog = number?.let { all.getOrNull(it - 1) } ?: find(symbol)
        return if (catalog == null) SpaceGroup(normalized, number) else catalog.copy(symbol = normalized, number = number ?: catalog.number)
    }

    fun operations(name: String): List<SymmetryOperation> {
        val number = find(name)?.number ?: return listOf(SymmetryOperation.IDENTITY)
        return SpaceGroupData.operationsTable[number]?.map(SymmetryOperation::parse)
            ?: listOf(SymmetryOperation.IDENTITY)
    }

    val RHOMBOHEDRAL_GROUPS: Set<Int> = setOf(146, 148, 155, 160, 161, 166, 167)

    fun isRhombohedral(name: String): Boolean = find(name)?.number in RHOMBOHEDRAL_GROUPS

    private fun normalize(value: String) = value.replace(" ", "").replace("_", "").lowercase()

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
