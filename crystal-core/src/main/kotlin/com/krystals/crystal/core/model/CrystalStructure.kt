package com.krystals.crystal.core.model

import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.symmetry.SpaceGroup
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation

data class CrystalStructure(
    val blockName: String,
    val lattice: Lattice,
    val spaceGroup: SpaceGroup,
    val symmetryOperations: List<SymmetryOperation>,
    val sites: List<Site>,
    val isConventional: Boolean = true,
) {
    val effectiveSymmetryOperations: List<SymmetryOperation>
        get() = symmetryOperations.ifEmpty { SpaceGroupCatalog.operations(spaceGroup.symbol) }

    /** The 27 lattice-image offsets {-1,0,1}³ × (a,b,c), computed once per structure. Body-only
     *  `val` — does not affect data-class equals/hashCode. */
    val latticeOffsets: List<Vec3> by lazy {
        val la = lattice.matrix.a
        val lb = lattice.matrix.b
        val lc = lattice.matrix.c
        val offsets = ArrayList<Vec3>(27)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            offsets += la * dx.toDouble() + lb * dy.toDouble() + lc * dz.toDouble()
        }
        offsets
    }
}
