package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure

data class BondNetwork(
    val atoms: List<AtomImage>,
    val bonds: List<Bond>,
    val structure: CrystalStructure,
    val expansion: Expansion,
)
