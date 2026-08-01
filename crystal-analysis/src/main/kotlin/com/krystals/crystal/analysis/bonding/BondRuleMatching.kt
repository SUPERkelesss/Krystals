package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3

/**
 * Helpers for deciding whether a [BondRule] actually produces a visible bond in a given structure.
 *
 * Per v0.2.3: bond-rule lists (editor + display) hide rules that match no atom pair within their
 * distance window, so the user isn't shown rules that don't affect the rendered scene.
 *
 * Per v0.5.2b: [hasMatchingBond] now accepts a pre-expanded atom list and a [BondGrid] so callers
 * that test many rules (the bond editor / display list) expand + grid once and reuse it. The grid
 * uses periodic-image-position queries (B method): for each atom it inspects its 27 periodic image
 * positions and the grid buckets near each, rather than pairing it against every other atom. This
 * keeps MOF-scale cells (hundreds of expanded atoms × many rules) from freezing the UI.
 */
object BondRuleMatching {

    /**
     * True if [structure] contains expanded atoms for both [BondRule.siteA] and [BondRule.siteB]
     * and at least one pair sits within `[rule.minAngstrom, rule.maxAngstrom]`.
     *
     * Expansion is used (not just the asymmetric unit) because bonds often form across cell
     * boundaries; checking only the ASU would under-report matches.
     *
     * Convenience overload that expands + grids on each call — fine for one-off checks; prefer the
     * [hasMatchingBond] overload that takes a [BondGrid] when filtering many rules at once.
     */
    fun hasMatchingBond(
        rule: BondRule,
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
    ): Boolean {
        val atoms = SymmetryExpander.expand(structure)
        val grid = BondGrid(atoms, structure, estimateCellSize(structure))
        return hasMatchingBond(rule, structure, bondConfiguration, atoms, grid)
    }

    /**
     * Grid-accelerated variant. [atoms] is the pre-expanded asymmetric unit (the grid was built from
     * the same list); pass the same [structure] the grid was built from so periodic-image offsets
     * stay consistent.
     */
    fun hasMatchingBond(
        rule: BondRule,
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        atoms: List<AtomImage>,
        grid: BondGrid,
    ): Boolean {
        if (rule.key in bondConfiguration.disabledPairs) return false
        val a = atoms.filter { it.siteId == rule.siteA }
        if (a.isEmpty()) return false
        val min = rule.minAngstrom
        val max = rule.maxAngstrom

        // Same-site rule (siteA == siteB): any two distinct atoms of that site, OR an atom bonded to
        // its own periodic image (the common case when the ASU has only one atom of that site, e.g.
        // Cs in CsCl). The grid cannot represent "an atom to its own non-zero lattice translation",
        // so this path is handled directly with the minimum-image convention.
        if (rule.siteA == rule.siteB) {
            val offsets = structure.latticeOffsets
            fun minImage(x: Vec3, y: Vec3): Double {
                var best = Double.POSITIVE_INFINITY
                for (off in offsets) {
                    val d = distance(x, y + off)
                    if (d < best) best = d
                }
                return best
            }
            for (i in a.indices) for (j in i + 1 until a.size) {
                val d = minImage(a[i].cartesianCoordinate.toVec3(), a[j].cartesianCoordinate.toVec3())
                if (d > 0.0 && d >= min && d <= max) return true
            }
            for (x in a) {
                for (off in offsets) {
                    if (off.lengthSquared() < 1e-18) continue
                    val cartesian = x.cartesianCoordinate.toVec3()
                    val d = distance(cartesian, cartesian + off)
                    if (d >= min && d <= max) return true
                }
            }
            return false
        }

        // Different-site rule: if the rule window exceeds the grid's cell size the bucket lookup can
        // miss neighbours, so fall back to the exhaustive minimum-image pairing for correctness.
        if (max > grid.cellSize) {
            val b = atoms.filter { it.siteId == rule.siteB }
            if (b.isEmpty()) return false
            val offsets = structure.latticeOffsets
            fun minImage(x: Vec3, y: Vec3): Double {
                var best = Double.POSITIVE_INFINITY
                for (off in offsets) {
                    val d = distance(x, y + off)
                    if (d < best) best = d
                }
                return best
            }
            for (x in a) for (y in b) {
                val d = minImage(x.cartesianCoordinate.toVec3(), y.cartesianCoordinate.toVec3())
                if (d > 0.0 && d >= min && d <= max) return true
            }
            return false
        }

        // Grid path: for each atom of siteA, scan its 27 periodic image positions and collect siteB
        // candidates from nearby buckets, then confirm with the minimum-image distance.
        for (x in a) {
            for (candidate in grid.nearbyOfSite(x.cartesianCoordinate.toVec3(), rule.siteB)) {
                val d = minImageDistance(
                    x.cartesianCoordinate.toVec3(),
                    candidate.cartesianCoordinate.toVec3(),
                    structure,
                )
                if (d > 0.0 && d >= min && d <= max) return true
            }
        }
        return false
    }

    /**
     * Estimate a grid cell size large enough to hold any auto-generated bond window: the maximum
     * bonding-radius sum over all site pairs, plus a 1 Å margin. Rules with a larger max (user-tuned)
     * fall back to exhaustive pairing in [hasMatchingBond]. Public so callers can build a [BondGrid]
     * with the same cell size [hasMatchingBond] would use internally.
     */
    fun estimateCellSize(structure: CrystalStructure): Double {
        var maxSum = 0.0
        for (a in structure.sites) for (b in structure.sites) {
            val sum = PeriodicTable.radius(a.species.symbol, RadiusSource.BONDING) +
                PeriodicTable.radius(b.species.symbol, RadiusSource.BONDING)
            if (sum > maxSum) maxSum = sum
        }
        return (maxSum + 1.0).coerceAtLeast(2.0)
    }
}

/** A periodic spatial hash of expanded atoms for fast neighbour queries.
 *
 *  Atoms are bucketed by `floor(cartesian / cellSize)`. [nearbyOfSite] returns atoms of a given
 *  site whose bucket is within one cell of any of the query point's 27 periodic-image positions,
 *  covering all mirror cells (a query near a cell face/edge/corner sees neighbours in up to 7
 *  neighbouring cells, hence the 27-image sweep rather than a single 3³ bucket window). */
class BondGrid(
    atoms: List<AtomImage>,
    private val structure: CrystalStructure,
    val cellSize: Double,
) {
    private val buckets: Map<Int3, List<AtomImage>> = run {
        val map = HashMap<Int3, MutableList<AtomImage>>()
        for (atom in atoms) {
            val key = keyOf(atom.cartesianCoordinate.toVec3())
            map.getOrPut(key) { mutableListOf() }.add(atom)
        }
        map.mapValues { it.value.toList() }
    }
    private val latticeOffsets: List<Vec3> = structure.latticeOffsets

    /** Atoms of [siteId] near any of [center]'s 27 periodic-image positions (within cellSize). */
    fun nearbyOfSite(center: Vec3, siteId: String): List<AtomImage> {
        val result = ArrayList<AtomImage>()
        val seen = HashSet<Long>()
        for (off in latticeOffsets) {
            collect(center + off, siteId, result, seen)
        }
        return result
    }

    private fun collect(center: Vec3, siteId: String, out: ArrayList<AtomImage>, seen: HashSet<Long>) {
        val ix = Math.floor(center.x / cellSize).toInt()
        val iy = Math.floor(center.y / cellSize).toInt()
        val iz = Math.floor(center.z / cellSize).toInt()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val bucket = buckets[Int3(ix + dx, iy + dy, iz + dz)] ?: continue
            for (atom in bucket) {
                if (atom.siteId != siteId) continue
                if (seen.add(atom.id)) out.add(atom)
            }
        }
    }

    private fun keyOf(v: Vec3): Int3 = Int3(
        Math.floor(v.x / cellSize).toInt(),
        Math.floor(v.y / cellSize).toInt(),
        Math.floor(v.z / cellSize).toInt(),
    )
}

/** Minimum-image distance between [x] and [y] across [structure]'s 27 lattice offsets. */
private fun minImageDistance(x: Vec3, y: Vec3, structure: CrystalStructure): Double {
    var best = Double.POSITIVE_INFINITY
    for (off in structure.latticeOffsets) {
        val d = distance(x, y + off)
        if (d < best) best = d
    }
    return best
}
