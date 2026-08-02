package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondValence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: v0.8.4 — corundum (Al2O3) must not render Al–Al BONDS, even though an Al–Al
 * rule legitimately exists.
 *
 * Root cause (v0.8.2 regression): BondDetector's hbond rework gated `customRule` on the
 * distance falling inside the rule window, so when the Al–Al rule window (ionic radii:
 * [0.1, 1.52]) did not cover a real Al–Al contact (2.68-2.82 Å), customRule became null
 * and the covalent auto fallback ([0.1, 2.97]) resurrected the pair as a bond.
 *
 * Same-polarity rules (cation–cation / anion–anion) are intentionally NOT disabled — such
 * bonding is physically valid when the rule window covers the distance; only the
 * out-of-window fallback resurrection is wrong.
 */
class CorundumRegressionTest {
    private fun corundum() = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
        .firstOrNull { it.isDirectory }
        ?.let { File(it, "02_oxides/corundum_Al2O3.cif") }
        ?: error("Sample CIF corpus not found")

    @Test
    fun smartIonicKeepsAlAlRuleButBondNetworkHasNoAlAlBonds() {
        val parsed = CifCodec.parseStructure(corundum().readText(), 0)
        val structure = parsed.structure
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        assertTrue(result.success, "smartIonic should succeed for Al2O3")

        // The Al–Al rule itself is legitimate (ionic window, max ~1.52 Å) and must be kept.
        val alAlRules = result.rules.filter { rule ->
            val ids = setOf(rule.siteA, rule.siteB)
            ids.size == 1 && structure.sites.any { it.id == ids.first() && it.species.symbol == "Al" }
        }
        assertTrue(alAlRules.isNotEmpty(), "Al–Al rule expected (same-polarity rules stay enabled)")

        val network = BondDetector.buildNetwork(
            structure,
            BondConfiguration(rules = result.rules),
        )
        val symbolById = network.atoms.associate { it.id to it.species.symbol }
        // Real Al–Al contacts (2.68-2.82 Å) lie outside the ionic rule window; the covalent
        // auto fallback must NOT resurrect them.
        val alAl = network.bonds.filter { bond ->
            symbolById[bond.atomA] == "Al" && symbolById[bond.atomB] == "Al"
        }
        assertEquals(0, alAl.size, "no Al–Al bond expected (out-of-window pair must not fall back to covalent radii)")
        val alO = network.bonds.count { bond ->
            setOf(symbolById[bond.atomA], symbolById[bond.atomB]) == setOf("Al", "O")
        }
        assertTrue(alO > 0, "Al–O bonds expected, got $alO")
    }
}
