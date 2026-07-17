package com.krystals.core

import kotlin.math.abs

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
        // Per v0.3.2: bonds use the minimum-image convention (inferBonds), so corner/edge neighbour
        // bonds need no materialised image atoms. BUT the *display* of boundary atoms still needs
        // their periodic images — a corner atom at (0,0,0) should render at all 8 cell corners, not
        // just one. So we keep the boundary-image generation here (for display/byId lookup only),
        // while inferBonds is fed only the in-cell atoms (cellOffset == 0) to avoid duplicate bonds.
        val atoms = ArrayList<ExpandedAtom>(predicted.toInt())
        var id = 1L
        for (ix in 0 until expansion.x) for (iy in 0 until expansion.y) for (iz in 0 until expansion.z) {
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
        // Bonds are computed only among in-cell atoms (cellOffset == 0); the minimum-image convention
        // already covers cross-cell neighbours, so image atoms would only create duplicate bonds.
        val inCell = atoms.filter { it.cellOffset.x == 0 && it.cellOffset.y == 0 && it.cellOffset.z == 0 }
        return SceneSnapshot(atoms, inferBonds(inCell, bondRules, structure.disabledBondPairs, structure.cell), structure, expansion)
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

    fun inferBonds(atoms: List<ExpandedAtom>, rules: List<BondRule>, disabledPairs: Set<String> = emptySet(), cell: UnitCell): List<Bond> {
        if (atoms.size < 2) return emptyList()
        val custom = rules.associateBy { it.key }
        // Per v0.3.2: minimum-image convention. For each atom pair consider all 27 periodic images
        // (offset in {-1,0,1}^3) of B and take the closest one, so corner/edge neighbour bonds are
        // found without materialising 26 neighbour-cell atoms. O(N^2 * 27) - fine for N up to a few
        // hundred; larger structures can be optimised later with spatial hashing.
        val la = cell.matrix.a // lattice vectors in Cartesian (columns of the cell matrix)
        val lb = cell.matrix.b
        val lc = cell.matrix.c
        val offsets = ArrayList<Vec3>(27)
        val intOffsets = ArrayList<Int3>(27)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            intOffsets += Int3(dx, dy, dz)
            offsets += la * dx.toDouble() + lb * dy.toDouble() + lc * dz.toDouble()
        }
        val result = mutableListOf<Bond>()
        for (i in atoms.indices) {
            val atom = atoms[i]
            for (j in i + 1 until atoms.size) {
                val other = atoms[j]
                val key = listOf(atom.siteId, other.siteId).sorted().joinToString("\u0000")
                // Per v0.2.3: a pair the user explicitly deleted is not redrawn via the fallback.
                if (key in disabledPairs) continue
                val rule = custom[key] ?: BondRule(
                    atom.siteId, other.siteId, 0.1,
                    PeriodicTable.covalentRadius(atom.element) + PeriodicTable.covalentRadius(other.element) + 0.45,
                    BondRuleSource.AUTO,
                )
                // Find the closest periodic image of other relative to atom.
                var bestD = Double.POSITIVE_INFINITY
                var bestIdx = -1
                for (k in offsets.indices) {
                    val d = distance(atom.cartesian, other.cartesian + offsets[k])
                    if (d < bestD) { bestD = d; bestIdx = k }
                }
                if (bestD > 0.0 && bestD >= rule.minAngstrom && bestD <= rule.maxAngstrom) {
                    result += Bond(atom.id, other.id, bestD, rule, intOffsets[bestIdx])
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
