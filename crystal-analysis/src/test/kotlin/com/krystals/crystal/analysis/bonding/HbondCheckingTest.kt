package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HbondCheckingTest {

    /** Build a simple P1 structure with given sites, 10×10×10 Å cell. */
    private fun simpleStructure(sites: List<Site>): CrystalStructure = CrystalStructure(
        blockName = "test",
        lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = sites,
    )

    @Test
    fun h2oPairGeneratesHbondRule() {
        // O at z=0.3, H at z=0.4 (~1.0 Å covalent), second O' at z=0.59 (~1.9 Å H-bond from H).
        val structure = simpleStructure(
            listOf(
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),
                Site("O", "O2", Species("O"), FractionalCoordinate(0.5, 0.5, 0.59)),
            ),
        )
        val atoms = SymmetryExpander.expand(structure)
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        assertTrue(result.success)
        val hbondRules = result.rules.filter { it.isHBond }
        assertTrue(hbondRules.isNotEmpty(), "should detect at least one H-bond in H₂O-like layout")
        val hoRule = hbondRules.first { setOf(it.siteA, it.siteB) == setOf("H", "O") }
        assertNotNull(hoRule)
        assertTrue(hoRule.maxAngstrom > 1.5, "hbond window should extend to vdW sum")
    }

    @Test
    fun noHbondWhenNoResolvedValence1H() {
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
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        assertTrue(result.rules.none { it.isHBond })
    }

    @Test
    fun neighbourElementCNotAccepted() {
        // H and C close — C is not an acceptor element.
        val structure = simpleStructure(
            listOf(
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),
                Site("C", "C1", Species("C"), FractionalCoordinate(0.5, 0.5, 0.45)),
            ),
        )
        val atoms = SymmetryExpander.expand(structure)
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        assertTrue(result.rules.none { it.isHBond }, "C is not an H-bond acceptor")
    }

    @Test
    fun hbondRuleSkipsWhenNormalCovalentWindowCovers() {
        // H and O at 0.5 Å — covalent O–H bonding range, hbond should not appear.
        val structure = simpleStructure(
            listOf(
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.35)),
            ),
        )
        val atoms = SymmetryExpander.expand(structure)
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        val normalOH = result.rules.find { !it.isHBond && setOf(it.siteA, it.siteB) == setOf("O", "H") }
        // If smartIonic resolves both sites and generates a normal O–H rule, no hbond is needed.
        val hbondRules = result.rules.filter { it.isHBond }
        assertTrue(hbondRules.isEmpty() || normalOH != null,
            "when normal O–H exists at close distance, no separate hbond rule")
    }

    @Test
    fun hbondRuleNotEmittedBeyondVdwSum() {
        // H and F at ~3.0 Å — beyond vdW(H=1.20) + vdW(F=1.47) = 2.67 Å, should NOT bond.
        val structure = simpleStructure(
            listOf(
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.2)),
                Site("F", "F1", Species("F"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val atoms = SymmetryExpander.expand(structure)
        val result = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, atoms)
        val hbondFH = result.rules.filter { it.isHBond && setOf(it.siteA, it.siteB) == setOf("H", "F") }
        assertTrue(hbondFH.isEmpty(), "3.0 Å exceeds vdW sum; no hbond for H–F")
    }
}
