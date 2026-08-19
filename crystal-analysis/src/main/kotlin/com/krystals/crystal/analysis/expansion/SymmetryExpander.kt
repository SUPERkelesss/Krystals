package com.krystals.crystal.analysis.expansion

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3

object SymmetryExpander {
    /**
     * Per v0.7.2: dedupe tolerance for expanded images. 2e-4 absorbs
     * DFT-relaxation noise in site coordinates (e.g. Materials Project data) so that
     * images of a noisy near-special-position site still collapse to the correct
     * multiplicity instead of producing near-duplicate atoms.
     */
    // Four-decimal CIF coordinates can accumulate up to 1.5e-4 error when a
    // relation such as y = 2x is rounded independently on both sides.
    private const val DEDUPE_TOLERANCE = 2e-4

    fun expand(structure: CrystalStructure): List<AtomImage> {
        val result = mutableListOf<AtomImage>()
        var id = 1L
        structure.sites.forEach { site ->
            val positions = mutableListOf<FractionalCoordinate>()
            structure.effectiveSymmetryOperations.forEach { operation ->
                val position = operation.apply(site.fractionalCoordinate)
                // Compare with periodic tolerance instead of a single quantization bucket.
                // Coordinates on a bucket boundary (common in CIFs rounded to 4–5 decimals)
                // can differ by one bin even when they are symmetry-equivalent.
                if (positions.none { it.almostEquals(position, DEDUPE_TOLERANCE) }) {
                    positions += position
                }
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

}
