package com.krystals.crystal.analysis.model

import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.data.PeriodicTableData

data class Expansion(val x: Int = 1, val y: Int = 1, val z: Int = 1) {
    init {
        require(x > 0 && y > 0 && z > 0)
    }

    val multiplier: Int get() = x * y * z
}

typealias RadiusSource = com.krystals.crystal.data.RadiusSource
typealias BondValenceParam = com.krystals.crystal.data.BondValenceParam

data class CrystalInfo(
    val atomCount: Int,
    val spaceGroup: String,
    val lattice: Lattice,
    val volume: Double,
    val density: Double?,
    val composition: String,
)

/** Chemistry-only access to the static data table. Display/color queries live in renderer. */
object PeriodicTable {
    val symbols: List<String> get() = PeriodicTableData.symbols

    fun mass(symbol: String): Double? = PeriodicTableData.mass(symbol)
    fun covalentRadius(symbol: String): Double = PeriodicTableData.covalentRadius(symbol)
    fun normalizeElement(value: String): String = PeriodicTableData.normalizeElement(value)
    fun radius(symbol: String, source: RadiusSource): Double = PeriodicTableData.radius(symbol, source)
    fun bondValenceParam(cation: String, cationValence: Int, anion: String, anionValence: Int): BondValenceParam? =
        PeriodicTableData.bondValenceParam(cation, cationValence, anion, anionValence)
    fun cationValences(cation: String): Set<Int> = PeriodicTableData.cationValences(cation)
    fun shannonCrystalRadius(ion: String, charge: Int, cn: Int): Double? =
        PeriodicTableData.shannonCrystalRadius(ion, charge, cn)
    fun anionValence(element: String): Int? = PeriodicTableData.anionValence(element)
    fun isAnion(element: String, other: String): Boolean = PeriodicTableData.isAnion(element, other)
    fun electronegativityPublic(element: String): Double = PeriodicTableData.electronegativityPublic(element)
}
