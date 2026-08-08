package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per v0.8.x: [CrystalEditor.rebuildHbondRules] recomputes ONLY the H-bond rules for the
 * current radius source while leaving the existing normal (covalent) rules untouched.
 *
 * - BONDING / VDW sources run the bonding-path hbond core over the EXISTING normal rules;
 * - SMART_IONIC runs the full smart-ionic analysis and keeps only its hbond rules (or the
 *   original configuration when the analysis fails).
 */
class HbondRebuildTest {

    /** Build a simple P1 structure. Cell = 10x10x10 AA so fractional → Cartesian = ×10. */
    private fun simpleStructure(sites: List<Site>) = CrystalStructure(
        blockName = "test",
        lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = sites,
    )

    /** H1 covalently bonded to O1 (~1.0 AA); O2 sits ~1.9 AA away — beyond the covalent
     *  window (H+O bonding radii + 0.45 ≈ 1.46 AA) but inside 0.95×vdW ≈ 2.58 AA,
     *  so H1···O2 is a genuine H-bond candidate. Same fixture as HbondBondingFallbackTest. */
    private fun waterStructure() = simpleStructure(
        listOf(
            Site("O1", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),   // ~1.0 AA from O1
            Site("O2", "O2", Species("O"), FractionalCoordinate(0.5, 0.5, 0.59)),  // ~1.9 AA from H1
        ),
    )

    /** Input configuration: one hand-made normal rule (O1–H1) plus a FAKE hbond rule
     *  (H1···O1 — the covalent partner must never carry an H-bond rule). */
    private fun seededConfiguration() = BondConfiguration(
        rules = listOf(
            BondRule("O1", "H1", 0.1, 2.0, BondRuleSource.CUSTOM),
            BondRule("H1", "O1", 1.46, 2.58, BondRuleSource.CUSTOM, isHBond = true),
        ),
    )

    @Test
    fun bondingSourceRebuildsHbondRulesKeepingNormalRules() {
        val structure = waterStructure()
        val config = seededConfiguration()

        val result = CrystalEditor.rebuildHbondRules(structure, config, RadiusSource.BONDING)
        val rules = result.bondConfiguration.rules

        // Normal rules survive untouched — exactly the seeded O1–H1 rule.
        val normalRules = rules.filter { !it.isHBond }
        assertEquals(listOf(BondRule("O1", "H1", 0.1, 2.0, BondRuleSource.CUSTOM)), normalRules)

        // The fake H1···O1 hbond rule is recomputed away.
        val hbondRules = rules.filter { it.isHBond }
        assertTrue(hbondRules.none { setOf(it.siteA, it.siteB) == setOf("H1", "O1") }, "fake hbond rule must be replaced")

        // The recomputed set contains the genuine H1···O2 hbond rule with the 0.95×vdW window.
        val hoHbond = hbondRules.find { setOf(it.siteA, it.siteB) == setOf("H1", "O2") }
        assertTrue(hoHbond != null, "rebuild must produce the H1···O2 hbond rule, got $hbondRules")
        assertTrue(hoHbond.maxAngstrom < 2.6, "hbond max must be 0.95×vdW, got ${hoHbond.maxAngstrom}")
    }

    @Test
    fun smartIonicSourceRebuildsHbondRulesKeepingNormalRules() {
        val structure = waterStructure()
        val config = seededConfiguration()

        val result = CrystalEditor.rebuildHbondRules(structure, config, RadiusSource.SMART_IONIC)
        val rules = result.bondConfiguration.rules

        // Normal rules survive untouched — exactly the seeded O1–H1 rule.
        val normalRules = rules.filter { !it.isHBond }
        assertEquals(listOf(BondRule("O1", "H1", 0.1, 2.0, BondRuleSource.CUSTOM)), normalRules)

        // The fake H1···O1 hbond rule is recomputed away.
        val hbondRules = rules.filter { it.isHBond }
        assertTrue(hbondRules.none { setOf(it.siteA, it.siteB) == setOf("H1", "O1") }, "fake hbond rule must be replaced")

        // The recomputed set contains the genuine H1···O2 hbond rule.
        assertTrue(hbondRules.any { setOf(it.siteA, it.siteB) == setOf("H1", "O2") }, "rebuild must produce the H1···O2 hbond rule, got $hbondRules")
    }

    @Test
    fun vdwSourceRebuildsHbondRulesKeepingNormalRules() {
        // The VDW source shares the bonding-path hbond core — same expectations as BONDING.
        val structure = waterStructure()
        val config = seededConfiguration()

        val result = CrystalEditor.rebuildHbondRules(structure, config, RadiusSource.VDW)
        val rules = result.bondConfiguration.rules

        assertEquals(listOf(BondRule("O1", "H1", 0.1, 2.0, BondRuleSource.CUSTOM)), rules.filter { !it.isHBond })
        assertTrue(rules.filter { it.isHBond }.any { setOf(it.siteA, it.siteB) == setOf("H1", "O2") }, "VDW source must produce the H1···O2 hbond rule")
    }

    @Test
    fun noHydrogenStructureKeepsRulesUnchanged() {
        // Pure CsCl — no H sites: no hbond detection can run, the rule list must be
        // returned verbatim for every source.
        val structure = simpleStructure(
            listOf(
                Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate.ZERO),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val config = BondConfiguration(
            rules = listOf(
                BondRule("Cs", "Cl", 0.1, 3.5, BondRuleSource.CUSTOM),
                BondRule("Cl", "Cs", 0.1, 3.5, BondRuleSource.CUSTOM, isHBond = true),
            ),
        )

        for (source in listOf(RadiusSource.BONDING, RadiusSource.VDW, RadiusSource.SMART_IONIC)) {
            val result = CrystalEditor.rebuildHbondRules(structure, config, source)
            assertEquals(config.rules, result.bondConfiguration.rules, "source $source must not touch rules without H")
        }
    }
}
