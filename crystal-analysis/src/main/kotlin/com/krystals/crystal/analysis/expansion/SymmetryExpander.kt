package com.krystals.crystal.analysis.expansion

import com.krystals.crystal.analysis.model.CrystalStructure
import com.krystals.crystal.analysis.model.ExpandedAtom
import com.krystals.crystal.core.Int3
import com.krystals.crystal.core.Vec3

object SymmetryExpander {
    fun expand(structure: CrystalStructure): List<AtomImage> {
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
}

