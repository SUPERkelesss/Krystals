package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression coverage for per-image D-H...A filtering in [BondDetector]. */
class HbondConcreteImageAngleTest {
    private val structure = CrystalStructure(
        blockName = "hbond-image-angle",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(
            Site("D1", "D1", Species("O"), FractionalCoordinate(0.50, 0.5, 0.5)), // x=2.0 A
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.75, 0.5, 0.5)), // x=3.0 A
            Site("A1", "A1", Species("O"), FractionalCoordinate(0.20, 0.5, 0.5)), // x=0.8 A
        ),
    )

    private val covalent = BondRule(
        siteA = "D1",
        siteB = "H1",
        minAngstrom = 0.5,
        maxAngstrom = 1.2,
        source = BondRuleSource.CUSTOM,
    )

    @Test
    fun wrongPrimaryAcceptorImageIsRejectedByActualAngle() {
        // The primary A image is 2.2 A to the LEFT of H, parallel to D-H: angle 0 degrees.
        // Minimum-image wrapping moves it to the right at 1.8 A and would report 180 degrees.
        // This window deliberately selects only the wrong 2.2 A primary image.
        val hbond = BondRule(
            siteA = "H1",
            siteB = "A1",
            minAngstrom = 2.0,
            maxAngstrom = 2.4,
            source = BondRuleSource.CUSTOM,
            isHBond = true,
        )

        val network = BondDetector.buildNetwork(
            structure,
            BondConfiguration(listOf(covalent, hbond)),
        )

        assertEquals(0, network.hbonds.size, "the concrete A image on the donor side must fail the angle cut")
    }

}
