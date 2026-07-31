package com.krystals.crystal.analysis.structure

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.CrystalInfo
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.core.model.CrystalStructure
import kotlin.math.abs

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
        val reducedFormula = computeReducedFormula(counts, orderedElements)
        return CrystalInfo(
            atoms.size,
            structure.spaceGroup.symbol,
            structure.lattice,
            structure.lattice.volume,
            density,
            composition,
            gramsPerMole,
            reducedFormula,
        )
    }

    private fun formatCount(value: Double): String {
        val rounded = kotlin.math.round(value)
        if (abs(value - rounded) < 1e-8) return rounded.toLong().toString()
        return "%.4f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }

    /** Compute the reduced chemical formula by dividing all counts by their GCD. */
    private fun computeReducedFormula(
        counts: Map<String, Double>,
        orderedElements: List<String>,
    ): String {
        // Round counts to nearest integer for GCD computation
        val intCounts = orderedElements.map { it to counts.getValue(it).let { v -> (v + 0.5).toInt() } }
        if (intCounts.any { it.second <= 0 }) {
            // If any count is zero or negative, fall back to using the original composition
            return orderedElements.joinToString(" ") { element -> "$element ${formatCount(counts.getValue(element))}" }
        }
        val gcd = intCounts.fold(intCounts.first().second) { acc, (_, v) -> gcd(acc, v) }
        if (gcd <= 1) {
            return orderedElements.joinToString(" ") { element -> "$element ${formatCount(counts.getValue(element))}" }
        }
        return orderedElements.joinToString(" ") { element ->
            val reduced = counts.getValue(element) / gcd
            if (abs(reduced - kotlin.math.round(reduced)) < 1e-8) {
                "$element ${(reduced + 0.5).toLong()}"
            } else {
                "$element ${formatCount(reduced)}"
            }
        }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
