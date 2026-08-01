package com.krystals.crystal.analysis.expansion

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3

object SymmetryExpander {
    /**
     * Per v0.7.1: dedupe tolerance for expanded images. 1e-4 (≈0.001 Å) absorbs
     * DFT-relaxation noise in site coordinates (e.g. Materials Project data) so that
     * images of a noisy near-special-position site still collapse to the correct
     * multiplicity instead of producing near-duplicate atoms.
     */
    private const val DEDUPE_TOLERANCE = 1e-4
    private const val DEDUPE_SCALE = 1e4 // 1 / DEDUPE_TOLERANCE — grid resolution matching the tolerance
    /** Each wrapped fractional coordinate occupies 14 bits (2^14 = 16384 > 10000 grid bins). */
    private const val COORD_BITS = 14

    fun expand(structure: CrystalStructure): List<AtomImage> {
        val result = mutableListOf<AtomImage>()
        var id = 1L
        structure.sites.forEach { site ->
            val seen = HashSet<Long>()
            val positions = mutableListOf<FractionalCoordinate>()
            structure.effectiveSymmetryOperations.forEach { operation ->
                val position = operation.apply(site.fractionalCoordinate)
                // O(ops) dedupe via a grid hash instead of the previous O(ops²) linear scan.
                // Images of one orbit snap to the same grid bin (bin size == DEDUPE_TOLERANCE),
                // while genuinely distinct symmetry positions fall in different bins.
                if (seen.add(dedupeKey(position))) positions += position
            }
            positions.forEach { position ->
                result += AtomImage(
                    id++, site.id, site.label, site.species, position,
                    structure.lattice.toCartesian(position), site.occupancy, Int3(0, 0, 0),
                )
            }
        }
        return result
    }

    /** Pack a wrapped fractional coordinate into 3×14-bit fields for O(1) membership tests. */
    private fun dedupeKey(position: FractionalCoordinate): Long {
        fun pack(component: Double): Long {
            // operation.apply() wraps to [0,1); the floor-guard here tolerates any residual
            // -1e-16 style noise so 0.99999999 and 0.00000001 map to the same wrapped bin.
            var v = component - kotlin.math.floor(component)
            if (v >= 1.0) v -= 1.0
            return Math.round(v * DEDUPE_SCALE).toLong() % DEDUPE_SCALE.toLong()
        }
        return pack(position.x) or (pack(position.y) shl COORD_BITS) or (pack(position.z) shl (2 * COORD_BITS))
    }
}
