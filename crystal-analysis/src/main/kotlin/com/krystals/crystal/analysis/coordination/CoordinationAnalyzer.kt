package com.krystals.crystal.analysis.coordination

import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.core.model.AtomImage

object CoordinationAnalyzer {
    fun neighbors(
        network: BondNetwork,
        showBonds: Boolean = true,
        hiddenBondPairs: Set<String> = emptySet(),
    ): Map<Long, List<AtomImage>> {
        if (!showBonds) return emptyMap()
        val atomById = network.atoms.associateBy { it.id }
        val neighbors = linkedMapOf<Long, MutableList<AtomImage>>()
        network.bonds.forEach { bond ->
            if (bond.rule.key in hiddenBondPairs) return@forEach
            val atomA = atomById[bond.atomA] ?: return@forEach
            val atomB = atomById[bond.atomB] ?: return@forEach
            neighbors.getOrPut(atomA.id) { mutableListOf() } += atomB
            neighbors.getOrPut(atomB.id) { mutableListOf() } += atomA
        }
        return neighbors.mapValues { (_, atoms) -> atoms.toList() }
    }

    fun coordinationNumber(
        network: BondNetwork,
        atomId: Long,
        showBonds: Boolean = true,
        hiddenBondPairs: Set<String> = emptySet(),
    ): Int = neighbors(network, showBonds, hiddenBondPairs)[atomId].orEmpty().size
}
