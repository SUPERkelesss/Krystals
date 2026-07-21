package com.krystals.crystal.analysis.structure

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.CrystalInfo
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.core.model.CrystalStructure

object StructureAnalyzer {
    private const val AVOGADRO = 6.02214076e23

    fun info(structure: CrystalStructure): CrystalInfo {
        val atoms = SymmetryExpander.expand(structure)
        val gramsPerMole = atoms.sumOf { atom -> (PeriodicTable.mass(atom.species.symbol) ?: 0.0) * atom.occupancy }
        val density = if (gramsPerMole > 0.0 && structure.lattice.volume > 0.0) {
            gramsPerMole / AVOGADRO / (structure.lattice.volume * 1e-24)
        } else null
        val counts = atoms.groupBy { it.species.symbol }.mapValues { (_, values) -> values.sumOf { it.occupancy } }
        val orderedElements = if ("C" in counts) {
            buildList {
                add("C")
                if ("H" in counts) add("H")
                addAll(counts.keys.filterNot { it == "C" || it == "H" }.sorted())
            }
        } else counts.keys.sorted()
        val composition = orderedElements.joinToString(" ") { element -> "$element ${formatCount(counts.getValue(element))}" }
        return CrystalInfo(
            atoms.size,
            structure.spaceGroup.symbol,
            structure.lattice,
            structure.lattice.volume,
            density,
            composition,
        )
    }

    private fun formatCount(value: Double): String {
        val rounded = kotlin.math.round(value)
        if (kotlin.math.abs(value - rounded) < 1e-8) return rounded.toLong().toString()
        return "%.4f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
}
