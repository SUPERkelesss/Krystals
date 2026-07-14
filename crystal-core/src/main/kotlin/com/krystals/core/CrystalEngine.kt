package com.krystals.core

import kotlin.math.floor
import kotlin.math.max

object CrystalEngine {
    const val MAX_RENDERED_ATOMS = 100_000
    private const val AVOGADRO = 6.02214076e23

    fun buildScene(
        structure: CrystalStructure,
        expansion: Expansion = Expansion(),
        bondRules: List<BondRule> = structure.bondRules,
    ): SceneSnapshot {
        val base = expandAsymmetricUnit(structure)
        val predicted = base.size.toLong() * expansion.multiplier
        require(predicted <= MAX_RENDERED_ATOMS) {
            "Expansion would create at least $predicted atoms; limit is $MAX_RENDERED_ATOMS"
        }
        val atoms = ArrayList<ExpandedAtom>(predicted.toInt())
        var id = 1L
        for (ix in 0 until expansion.x) for (iy in 0 until expansion.y) for (iz in 0 until expansion.z) {
            val offset = Int3(ix, iy, iz)
            base.forEach { atom ->
                val boundaryX = atom.fractional.x < 1e-6 && ix == expansion.x - 1
                val boundaryY = atom.fractional.y < 1e-6 && iy == expansion.y - 1
                val boundaryZ = atom.fractional.z < 1e-6 && iz == expansion.z - 1
                val xImages = if (boundaryX) intArrayOf(0, 1) else intArrayOf(0)
                val yImages = if (boundaryY) intArrayOf(0, 1) else intArrayOf(0)
                val zImages = if (boundaryZ) intArrayOf(0, 1) else intArrayOf(0)
                for (bx in xImages) for (by in yImages) for (bz in zImages) {
                    val imageOffset = Int3(ix + bx, iy + by, iz + bz)
                    val fractional = atom.fractional + Vec3(imageOffset.x.toDouble(), imageOffset.y.toDouble(), imageOffset.z.toDouble())
                    atoms += atom.copy(
                        id = id++, fractional = fractional,
                        cartesian = structure.cell.toCartesian(fractional), cellOffset = imageOffset,
                    )
                    require(atoms.size <= MAX_RENDERED_ATOMS) {
                        "Expansion including boundary atoms exceeds limit $MAX_RENDERED_ATOMS"
                    }
                }
            }
        }
        return SceneSnapshot(atoms, inferBonds(atoms, bondRules), structure, expansion)
    }

    fun expandAsymmetricUnit(structure: CrystalStructure): List<ExpandedAtom> {
        val result = mutableListOf<ExpandedAtom>()
        var id = 1L
        structure.sites.forEach { site ->
            val positions = mutableListOf<Vec3>()
            structure.effectiveSymmetryOperations.forEach { operation ->
                val position = operation.apply(site.fractional)
                if (positions.none { it.almostEquals(position) }) positions += position
            }
            positions.forEach { position ->
                result += ExpandedAtom(
                    id++, site.id, site.label, site.element, position,
                    structure.cell.toCartesian(position), site.occupancy, Int3(0, 0, 0),
                )
            }
        }
        return result
    }

    fun inferBonds(atoms: List<ExpandedAtom>, rules: List<BondRule>): List<Bond> {
        if (atoms.size < 2) return emptyList()
        val custom = rules.associateBy { it.key }
        val maxCustom = rules.maxOfOrNull { it.maxAngstrom } ?: 0.0
        val maxRadius = atoms.maxOfOrNull { PeriodicTable.covalentRadius(it.element) } ?: 1.25
        val cellSize = max(0.5, max(maxCustom, maxRadius * 2 + 0.45))
        fun cellKey(v: Vec3) = Int3(floor(v.x / cellSize).toInt(), floor(v.y / cellSize).toInt(), floor(v.z / cellSize).toInt())
        val buckets = atoms.groupBy { cellKey(it.cartesian) }
        val result = mutableListOf<Bond>()
        atoms.forEach { atom ->
            val origin = cellKey(atom.cartesian)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                buckets[Int3(origin.x + dx, origin.y + dy, origin.z + dz)].orEmpty().forEach { other ->
                    if (other.id <= atom.id) return@forEach
                    val key = listOf(atom.siteId, other.siteId).sorted().joinToString("\u0000")
                    val rule = custom[key] ?: BondRule(
                        atom.siteId, other.siteId, 0.1,
                        PeriodicTable.covalentRadius(atom.element) + PeriodicTable.covalentRadius(other.element) + 0.45,
                        BondRuleSource.AUTO,
                    )
                    val d = distance(atom.cartesian, other.cartesian)
                    if (d >= rule.minAngstrom && d <= rule.maxAngstrom) result += Bond(atom.id, other.id, d, rule)
                }
            }
        }
        return result
    }

    fun info(structure: CrystalStructure): CrystalInfo {
        val atoms = expandAsymmetricUnit(structure)
        val gramsPerMole = atoms.sumOf { atom -> (PeriodicTable.mass(atom.element) ?: 0.0) * atom.occupancy }
        val density = if (gramsPerMole > 0.0 && structure.cell.volume > 0.0) {
            gramsPerMole / AVOGADRO / (structure.cell.volume * 1e-24)
        } else null
        val counts = atoms.groupBy { it.element }.mapValues { (_, values) -> values.sumOf { it.occupancy } }
        val orderedElements = if ("C" in counts) {
            buildList {
                add("C")
                if ("H" in counts) add("H")
                addAll(counts.keys.filterNot { it == "C" || it == "H" }.sorted())
            }
        } else counts.keys.sorted()
        val composition = orderedElements.joinToString(" ") { element -> "$element ${formatCount(counts.getValue(element))}" }
        return CrystalInfo(atoms.size, structure.spaceGroupName, structure.cell, structure.cell.volume, density, composition)
    }

    private fun formatCount(value: Double): String {
        val rounded = kotlin.math.round(value)
        if (kotlin.math.abs(value - rounded) < 1e-8) return rounded.toLong().toString()
        return "%.4f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
}
