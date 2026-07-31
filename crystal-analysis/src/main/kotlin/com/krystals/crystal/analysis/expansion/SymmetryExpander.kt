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

    fun expand(structure: CrystalStructure): List<AtomImage> {
        val result = mutableListOf<AtomImage>()
        var id = 1L
        structure.sites.forEach { site ->
            val positions = mutableListOf<FractionalCoordinate>()
            structure.effectiveSymmetryOperations.forEach { operation ->
                val position = operation.apply(site.fractionalCoordinate)
                if (positions.none { it.almostEquals(position, DEDUPE_TOLERANCE) }) positions += position
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
