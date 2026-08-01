package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondValence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: v0.8.3 — corundum (Al2O3) must not produce Al–Al rules or Al–Al bonds.
 *
 * Root cause (recurring class): the smart-ionic generator only skipped anion–anion pairs, so
 * cation–cation pairs (Al³⁺–Al³⁺) generated rules; worse, BondDetector's covalent auto fallback
 * resurrected same-polarity contacts whose distance fell inside the covalent-radius window
 * (Al–Al 2.68-2.82 Å vs covalent window 2.97 Å) even when the pair's own rule window missed.
 * Fix: skip same-polarity pairs in smartIonicRules, make an existing pair rule authoritative in
 * BondDetector, and suppress the covalent auto fallback for same-element pairs.
 */
class CorundumRegressionTest {
    private fun corundum() = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
        .firstOrNull { it.isDirectory }
        ?.let { File(it, "02_oxides/corundum_Al2O3.cif") }
        ?: error("Sample CIF corpus not found")

    @Test
    fun smartIonicGeneratesNoAlAlRule() {
        val parsed = CifCodec.parseStructure(corundum().readText(), 0)
        val result = BondValence.smartIonicRules(parsed.structure, BondConfiguration(), 0.45)
        assertTrue(result.success, "smartIonic should succeed for Al2O3")
        val alAl = result.rules.filter { rule ->
            val siteIds = setOf(rule.siteA, rule.siteB)
            siteIds.size == 1 && parsed.structure.sites.any { it.id == siteIds.first() && it.species.symbol == "Al" }
        }
        assertTrue(alAl.isEmpty(), "no Al–Al rule expected, got ${alAl.map { "${it.siteA}-${it.siteB}" }}")
        assertTrue(
            result.rules.any { rule ->
                val ids = setOf(rule.siteA, rule.siteB)
                ids.size == 2 && parsed.structure.sites.filter { it.id in ids }.map { it.species.symbol }.toSet() == setOf("Al", "O")
            },
            "Al–O rule expected",
        )
    }

    @Test
    fun bondNetworkHasNoAlAlBonds() {
        val parsed = CifCodec.parseStructure(corundum().readText(), 0)
        val smartIonic = BondValence.smartIonicRules(parsed.structure, BondConfiguration(), 0.45)
        assertTrue(smartIonic.success)
        val network = BondDetector.buildNetwork(
            parsed.structure,
            BondConfiguration(rules = smartIonic.rules),
        )
        val symbolById = network.atoms.associate { it.id to it.species.symbol }
        val alAl = network.bonds.filter { bond ->
            symbolById[bond.atomA] == "Al" && symbolById[bond.atomB] == "Al"
        }
        assertEquals(0, alAl.size, "no Al–Al bond expected (covalent fallback must not resurrect same-element pairs)")
        val alO = network.bonds.count { bond ->
            setOf(symbolById[bond.atomA], symbolById[bond.atomB]) == setOf("Al", "O")
        }
        assertTrue(alO > 0, "Al–O bonds expected, got $alO")
    }
}
