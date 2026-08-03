package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondValence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression (v0.8.5): the corpus h3po4_H3PO4.cif must yield exactly 10 VISIBLE hbond bonds
 * (14 total network hbonds, minus 4 whose acceptor is an external-shell atom the scene
 * builder hides). Guards the bond-level per-proton shortest + angle filters on real data —
 * the renderer-core HbondH3po4SceneTest covers the same acceptance on a hand-coded P21/c
 * structure (renderer-core cannot parse CIF).
 */
class H3po4CorpusCheckTest {
    @Test
    fun corpusH3po4Renders10Hbonds() {
        val dir = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("corpus missing")
        val parsed = CifCodec.parseStructure(File(dir, "08_molecular/h3po4_H3PO4.cif").readText(), 0)
        val structure = parsed.structure
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        val network = BondDetector.buildNetwork(structure, BondConfiguration(rules = result.rules))
        val atomById = network.atoms.associate { it.id to it }
        val hbonds = network.bonds.filter { it.rule.isHBond }
        // Scene-builder visibility: hbond rules never extend across the cell, so an hbond whose
        // acceptor (atomB, oriented shell-last) is an external shell is hidden.
        val visible = hbonds.filter { !(atomById[it.atomB]?.isExternalShell == true) }
        println("corpus H3PO4: hbondRules=${result.rules.count { it.isHBond }} hbondBonds=${hbonds.size} visible=${visible.size}")
        visible.forEach { println("  d=${"%.2f".format(it.distance)}") }
        assertEquals(10, visible.size, "visible hbonds from corpus CIF")
    }
}
