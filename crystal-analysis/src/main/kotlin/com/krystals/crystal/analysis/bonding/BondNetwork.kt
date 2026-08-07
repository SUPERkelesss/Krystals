package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.HydrogenBond

data class BondNetwork(
    val atoms: List<AtomImage>,
    val bonds: List<Bond>,
    /** 氢键独立通道:与 [bonds] 分离,不参与共价键语义(分子判据、共价配位)。 */
    val hbonds: List<HydrogenBond> = emptyList(),
    val structure: CrystalStructure,
    val expansion: Expansion,
)
