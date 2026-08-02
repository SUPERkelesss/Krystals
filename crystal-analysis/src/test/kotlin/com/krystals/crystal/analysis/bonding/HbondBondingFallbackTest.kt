package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.editing.CrystalEditor
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
 * v0.8.6: the bonding-radius fallback path also runs hbond detection.
 * Triggered via [CrystalEditor.fromSmartIonicAttempt] with smartIonic = null
 * (simulating smartIonic timeout or failure).
 *
 * Proton criterion for this path: H site bonded through exactly ONE bonding-rule
 * pair to O/N/F/S/P/Cl. H sites bonded to 2+ such acceptors are NOT protons.
 */
class HbondBondingFallbackTest {

    /** Build a simple P1 structure. Cell = 10x10x10 AA so fractional → Cartesian = ×10. */
    private fun simpleStructure(sites: List<Site>) = CrystalStructure(
        blockName = "test",
        lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = sites,
    )

    @Test
    fun bondingPathAppendsHbondRulesForSingleBondH() {
        // H1 bonds only to O1 (single bond) → qualifies as proton.
        // H2 bonds to both O3 and O4 (two bonds) → NOT a proton.
        // H1 is positioned ~1.9 AA from O2 (H-bond candidate beyond covalent window).
        val structure = simpleStructure(
            listOf(
                Site("O1", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H1", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),   // ~1.0 AA from O1
                Site("O2", "O2", Species("O"), FractionalCoordinate(0.5, 0.5, 0.59)),  // ~1.9 AA from H1
                Site("O3", "O3", Species("O"), FractionalCoordinate(0.3, 0.2, 0.5)),
                Site("O4", "O4", Species("O"), FractionalCoordinate(0.3, 0.4, 0.5)),
                Site("H2", "H2", Species("H"), FractionalCoordinate(0.3, 0.3, 0.5)),   // ~1.0 AA from O3, ~1.0 AA from O4
            ),
        )

        // Trigger the bonding fallback path by passing smartIonic = null.
        val result = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = null)
        val rules = result.bondConfiguration.rules
        val hbondRules = rules.filter { it.isHBond }
        val normalRules = rules.filter { !it.isHBond }

        // Normal rules exist from bonding-radius generation.
        assertTrue(normalRules.isNotEmpty(), "bonding path must generate normal rules")

        // Hbond rules exist from the new bonding-path hbond detection.
        assertTrue(hbondRules.isNotEmpty(), "bonding fallback path must append hbond rules")

        // Exactly one hbond rule: H1 (proton) to O2 (acceptor).
        val hoHbond = hbondRules.find { setOf(it.siteA, it.siteB) == setOf("H1", "O2") }
            ?: hbondRules.find { setOf(it.siteA, it.siteB) == setOf("H1", "O") }
        assertTrue(hoHbond != null, "H1 must have an hbond rule")

        // No hbond rule for H2 (it bonds two O → not a proton).
        val h2Hbond = hbondRules.any { setOf(it.siteA, it.siteB).any { s -> s == "H2" } }
        assertTrue(!h2Hbond, "H2 bonds two O → must NOT be a proton")

        // The hbond rule window uses 0.95×vdW.
        hoHbond?.let {
            assertTrue(it.maxAngstrom < 2.6, "hbond max must be 0.95×vdW, got ${it.maxAngstrom}")
        }
    }

    @Test
    fun bondingPathNoHbondWhenNoProton() {
        // Pure CsCl — no H sites at all, bonding path should produce no hbond rules.
        val structure = CrystalStructure(
            blockName = "cscl",
            lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate.ZERO),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val result = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = null)
        assertTrue(result.bondConfiguration.rules.none { it.isHBond }, "no H → no hbond rules")
    }
}
