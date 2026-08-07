package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.crystal.analysis.model.RadiusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v0.8.6: the bonding-radius fallback path also runs hbond detection.
 * Triggered via [CrystalEditor.fromSmartIonicAttempt] with smartIonic = null
 * (simulating smartIonic timeout or failure).
 *
 * Proton criterion for this path: H site bonded through exactly ONE bonding-rule
 * pair to O/N/F/S/P/Cl (v0.8.16: C added). H sites bonded to 2+ such partners
 * are NOT protons. One H-bond per H donor; acceptors may receive multiple H-bonds.
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

    /**
     * Per v0.8.27: auto-compute-hbonds preference OFF (includeHbonds=false) must
     * suppress H-bond generation on every public entry point while keeping the
     * normal (non-H) rules intact. The fixture is the same H1–O2 proton case from
     * bondingPathAppendsHbondRulesForSingleBondH, which produces an hbond rule
     * when includeHbonds defaults to true.
     */
    @Test
    fun includeHbondsFalseSuppressesHbondsOnAllEntryPoints() {
        val structure = simpleStructure(
            listOf(
                Site("O1", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H1", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),   // ~1.0 AA from O1
                Site("O2", "O2", Species("O"), FractionalCoordinate(0.5, 0.5, 0.59)),  // ~1.9 AA from H1
                Site("O3", "O3", Species("O"), FractionalCoordinate(0.3, 0.2, 0.5)),
                Site("O4", "O4", Species("O"), FractionalCoordinate(0.3, 0.4, 0.5)),
                Site("H2", "H2", Species("H"), FractionalCoordinate(0.3, 0.3, 0.5)),
            ),
        )

        // fromSmartIonicAttempt (bonding fallback path)
        val attempt = CrystalEditor.fromSmartIonicAttempt(
            structure, BondConfiguration(), 0.45, smartIonic = null, includeHbonds = false,
        )
        assertTrue(attempt.bondConfiguration.rules.none { it.isHBond }, "fromSmartIonicAttempt must drop hbonds")
        assertTrue(attempt.bondConfiguration.rules.isNotEmpty(), "normal rules must survive")

        // ensureAutoBondRules
        val ensured = CrystalEditor.ensureAutoBondRules(
            structure, BondConfiguration(), 0.45, includeHbonds = false,
        )
        assertTrue(ensured.bondConfiguration.rules.none { it.isHBond }, "ensureAutoBondRules must drop hbonds")
        assertTrue(ensured.bondConfiguration.rules.isNotEmpty(), "normal rules must survive")

        // rebuildBondRules (SMART_IONIC + BONDING sources)
        val rebuiltSmart = CrystalEditor.rebuildBondRules(
            structure, BondConfiguration(), RadiusSource.SMART_IONIC, 0.45, includeHbonds = false,
        )
        assertTrue(rebuiltSmart.bondConfiguration.rules.none { it.isHBond }, "rebuildBondRules(SMART_IONIC) must drop hbonds")
        val rebuiltBonding = CrystalEditor.rebuildBondRules(
            structure, BondConfiguration(), RadiusSource.BONDING, 0.45, includeHbonds = false,
        )
        assertTrue(rebuiltBonding.bondConfiguration.rules.none { it.isHBond }, "rebuildBondRules(BONDING) must drop hbonds")

        // Sanity: default includeHbonds=true still produces the H1–O2 hbond rule.
        val defaultResult = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = null)
        assertTrue(defaultResult.bondConfiguration.rules.any { it.isHBond }, "default must keep hbonds")
    }

    @Test
    fun bondingPathDoesNotTreatCarbonBondedHAsProton() {
        // Per v0.8.39: C is removed from the proton-partner set — an H bonded ONLY to C
        // (single covalent bond) no longer qualifies as a proton, so no C–H···O hbond rule
        // is produced even when an O acceptor is present. (v0.8.16 had enabled it.)
        val structure = simpleStructure(
            listOf(
                Site("C", "C1", Species("C"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),   // ~1.0 Å from C
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.60)),  // ~2.0 Å from H
            ),
        )
        val result = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = null)
        val hbondRules = result.bondConfiguration.rules.filter { it.isHBond }
        val hoHbond = hbondRules.find { setOf(it.siteA, it.siteB) == setOf("H", "O") }
        assertNull(hoHbond, "C–H donor must NOT produce an H···O hbond rule")
    }

    @Test
    fun acceptorCanAcceptMultipleProtons() {
        // Per v0.8.16 spec: one H atom forms only one H-bond (per-H shortest), but an ACCEPTOR
        // may accept multiple H atoms. Two protons H1/H2 both point at the same O3 acceptor —
        // the network must contain TWO hbond bonds (H1···O3 and H2···O3).
        val structure = simpleStructure(
            listOf(
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),   // ~1.0 Å from O1
                Site("O", "O2", Species("O"), FractionalCoordinate(0.5, 0.7, 0.3)),
                Site("H", "H2", Species("H"), FractionalCoordinate(0.5, 0.7, 0.4)),   // ~1.0 Å from O2
                Site("O", "O3", Species("O"), FractionalCoordinate(0.5, 0.6, 0.55)),  // ~1.8 Å from BOTH H1 and H2
            ),
        )
        val atoms = SymmetryExpander.expand(structure)
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        assertTrue(result.success, "smartIonic must succeed")
        val net = BondDetector.buildNetwork(structure, BondConfiguration(result.rules))
        val hbondBonds = net.bonds.filter { it.rule.isHBond }
        assertEquals(2, hbondBonds.size, "two protons, one H-bond each — acceptor O3 receives both")
        hbondBonds.forEach { b ->
            val end = net.atoms.first { it.id == b.atomB }
            assertTrue(end.species.symbol == "O", "hbond must terminate at an O acceptor, got ${end.species.symbol}")
        }
    }

    @Test
    fun boundaryProtonDetectsHbondAcrossCellBoundary() {
        // Per v0.8.17: H near the cell boundary whose covalent partner sits across the boundary
        // (periodic image) must still be detected. Before the fix the angle used the main-cell
        // coordinate (~9.2 Å away) instead of the periodic displacement (~0.8 Å), giving a wrong
        // ~57° angle and dropping the H-bond entirely.
        val structure = simpleStructure(
            listOf(
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.06)),
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.98)), // ~0.8 Å from O1's +z image
                Site("O", "O2", Species("O"), FractionalCoordinate(0.5, 0.7, 0.85)), // ~2.39 Å H···O2, angle ~123°
            ),
        )
        val atoms = SymmetryExpander.expand(structure)
        // Both rule-generation paths must detect it.
        val si = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        assertTrue(si.success)
        assertTrue(si.rules.any { it.isHBond }, "smartIonic path must detect the boundary H-bond")
        val bp = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = null)
        assertTrue(bp.bondConfiguration.rules.any { it.isHBond }, "bonding path must detect the boundary H-bond")
        // And the bond must materialise in the network at the periodic distance.
        val net = BondDetector.buildNetwork(structure, bp.bondConfiguration)
        val hbondBonds = net.bonds.filter { it.rule.isHBond }
        assertEquals(1, hbondBonds.size)
        assertTrue(hbondBonds.single().distance in 2.0..2.6, "H-bond distance must be the periodic ~2.39 Å")
    }

    // ── v0.8.7: all-non-metal structures default to bonding rules ──────────────────

    @Test
    fun allNonMetalStructureUsesBondingPathDirectly() {
        // Ice-like structure: H and O only (all non-metals).
        // fromSmartIonicAttempt with a valid smartIonic result should STILL use bonding.
        val structure = simpleStructure(
            listOf(
                Site("O1", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H1", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),
                Site("O2", "O2", Species("O"), FractionalCoordinate(0.5, 0.5, 0.59)),
            ),
        )
        // Compute a valid smartIonic result for this structure.
        val atoms = SymmetryExpander.expand(structure)
        val smartResult = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        // Pass the valid smartIonic result — all-non-metal should ignore it, use bonding instead.
        val result = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = smartResult)
        // The result must produce rules (bonding path) and hbond rules (bonding path detection).
        val hbondRules = result.bondConfiguration.rules.filter { it.isHBond }
        assertTrue(result.bondConfiguration.rules.isNotEmpty(), "bonding path must generate rules")
        assertTrue(hbondRules.isNotEmpty(), "all-non-metal bonding path must detect hbonds")
    }

    @Test
    fun metalContainingStructureStillUsesSmartIonicWhenSmall() {
        // CsCl: Cs is metal → should use smartIonic when ≤100 atoms.
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
        val atoms = SymmetryExpander.expand(structure)
        val smartResult = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        assertTrue(smartResult.success, "CsCl smartIonic should succeed")
        // With a valid smartIonic result, the metal-containing structure uses it.
        val result = CrystalEditor.fromSmartIonicAttempt(structure, BondConfiguration(), 0.45, smartIonic = smartResult)
        assertTrue(result.bondConfiguration.rules.isNotEmpty(), "metal structure must generate rules")
        // Metal structure with smartIonic should NOT produce hbond rules (no H atoms).
        assertFalse(result.bondConfiguration.rules.any { it.isHBond }, "CsCl has no H, no hbonds")
    }
}
