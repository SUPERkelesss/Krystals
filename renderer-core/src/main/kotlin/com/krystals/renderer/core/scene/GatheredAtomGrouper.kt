package com.krystals.renderer.core.scene

import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.AtomImage

/** A single member slice of a gathered-atom pie. */
data class GatherSlice(
    val siteId: String,
    val atomId: Long,
    val color: Long,        // site's ARGB color from the appearance config
    val fraction: Double,   // display weight (sums with remainder to 1)
)

/** An atom whose expanded position coincides with at least one other atom of a different site,
 *  rendered as a segmented pie-chart sphere. */
data class GatheredAtom(
    val center: Vec3,
    val memberAtomIds: List<Long>,     // original atom ids in stable order
    val slices: List<GatherSlice>,     // per-member pie slices
    val remainderFraction: Double,     // 0 when raw Σocc >= 1
    val mixedColor: Long,              // occ-weighted blend (alpha always FF)
    val wasNormalized: Boolean,        // true when raw Σocc > 1
)

data class GatheredAtomGroups(
    val groups: List<GatheredAtom>,
    val byMemberId: Map<Long, GatheredAtom>,
)

/**
 * Groups expanded atoms by matching cartesian coordinates (tolerance 1e-4 Å) into
 * [GatheredAtom] bundles. Only atoms of DIFFERENT site ids at the same position
 * qualify — this models substitutional / mixed-occupancy disorder.
 *
 * Colors are resolved from [colorBySite] (siteId → ARGB). When no color is mapped
 * for a site, 0xFF808080 (gray) is used as a fallback.
 */
object GatheredAtomGrouper {
    /** Spacing of the spatial hash grid in Å. */
    private const val TOLERANCE = 1e-4
    private const val INV_TOL = 1.0 / TOLERANCE

    /**
     * Group atoms by rounded cartesian position. Each group must contain ≥2 atoms
     * with at least TWO distinct [AtomImage.siteId] values.
     */
    fun group(atoms: List<AtomImage>, colorBySite: Map<String, Long>): List<GatheredAtom> =
        groupWithIndex(atoms, colorBySite).groups

    /** Groups atoms once and returns both the stable group list and its member-id index. */
    fun groupWithIndex(atoms: List<AtomImage>, colorBySite: Map<String, Long>): GatheredAtomGroups {
        if (atoms.size < 2) return GatheredAtomGroups(emptyList(), emptyMap())

        // Spatial hash: (ix, iy, iz) → atoms at that cell.
        val buckets = linkedMapOf<Triple<Int, Int, Int>, MutableList<AtomImage>>()
        for (a in atoms) {
            val key = Triple(round(a.cartesianCoordinate.x), round(a.cartesianCoordinate.y), round(a.cartesianCoordinate.z))
            buckets.getOrPut(key) { mutableListOf() }.add(a)
        }

        val result = mutableListOf<GatheredAtom>()
        for ((_, bucket) in buckets) {
            if (bucket.size < 2) continue
            // Only group atoms with different siteIds.
            val distinctSites = bucket.map { it.siteId }.distinct()
            if (distinctSites.size < 2) continue

            val ids = bucket.map { it.id }
            // Per-site occupancy: sum occ of all atoms of each site in this bucket.
            // Then normalize display fractions.
            val rawOccBySite = linkedMapOf<String, Double>()
            for (a in bucket) {
                rawOccBySite[a.siteId] = (rawOccBySite[a.siteId] ?: 0.0) + a.occupancy
            }
            val rawSum = rawOccBySite.values.sum()
            val wasNormalized = rawSum > 1.0
            val scale = if (wasNormalized) 1.0 / rawSum else 1.0

            val slices = rawOccBySite.map { (siteId, rawOcc) ->
                val color = colorBySite[siteId] ?: 0xFF808080L
                // Use the FIRST atom id of each site as the slice representative.
                val atomId = bucket.first { it.siteId == siteId }.id
                GatherSlice(siteId, atomId, color, rawOcc * scale)
            }

            val remainder = (1.0 - slices.sumOf { it.fraction }).coerceIn(0.0, 1.0)
            val mixed = mixColors(slices)

            val center = bucket.first().cartesianCoordinate.toVec3()
            result += GatheredAtom(center, ids, slices, remainder, mixed, wasNormalized)
        }
        val byMemberId = linkedMapOf<Long, GatheredAtom>()
        for (group in result) {
            for (id in group.memberAtomIds) byMemberId[id] = group
        }
        return GatheredAtomGroups(result, byMemberId)
    }

    /** Returns a map from member atom id to its [GatheredAtom], or empty map if no groups. */
    fun groupByAtomId(atoms: List<AtomImage>, colorBySite: Map<String, Long>): Map<Long, GatheredAtom> {
        return groupWithIndex(atoms, colorBySite).byMemberId
    }

    /** Blends slice colors weighted by fraction into a single ARGB (alpha=0xFF). */
    private fun mixColors(slices: List<GatherSlice>): Long {
        if (slices.isEmpty()) return 0xFF808080L
        if (slices.size == 1) return slices.single().color or 0xFF000000L
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (s in slices) {
            val wt = s.fraction
            r += ((s.color ushr 16) and 0xFF).toInt() * wt
            g += ((s.color ushr 8) and 0xFF).toInt() * wt
            b += (s.color and 0xFF).toInt() * wt
            w += wt
        }
        val iw = if (w > 0.0) 1.0 / w else 1.0
        val ir = (r * iw).toInt().coerceIn(0, 255)
        val ig = (g * iw).toInt().coerceIn(0, 255)
        val ib = (b * iw).toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (ir.toLong() shl 16) or (ig.toLong() shl 8) or ib.toLong()
    }

    private fun round(value: Double): Int = (value * INV_TOL).toInt()
}
