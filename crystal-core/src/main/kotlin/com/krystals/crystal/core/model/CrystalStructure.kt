package com.krystals.crystal.core.model

import com.krystals.crystal.core.lattice.Lattice
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
}
