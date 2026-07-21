package com.krystals.crystal.analysis.expansion

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3

object SymmetryExpander {
    fun expand(structure: CrystalStructure): List<AtomImage> {
        val result = mutableListOf<AtomImage>()
        var id = 1L
        structure.sites.forEach { site ->
            val positions = mutableListOf<FractionalCoordinate>()
            structure.effectiveSymmetryOperations.forEach { operation ->
                val position = operation.apply(site.fractionalCoordinate)
                if (positions.none { it.almostEquals(position) }) positions += position
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
