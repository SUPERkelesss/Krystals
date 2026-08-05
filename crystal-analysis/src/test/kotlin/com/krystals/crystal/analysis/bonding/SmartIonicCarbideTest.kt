package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * v0.8.36: carbide C4- anion. Smart-ionic must resolve CaC2 (I4/mmm, Ca at origin, C2 dumbbell
 * along c) — before C joined the fixed-anion table, Ca had no anion partner and the whole BVS
 * analysis failed (success=false, bonding fallback). BaO2 with the identical geometry always
 * worked because O is a fixed anion.
 */
class SmartIonicCarbideTest {

    private fun carbideStructure(aSymbol: String, bSymbol: String): CrystalStructure = CrystalStructure(
        blockName = "carbide",
        // CaC2 geometry: I4/mmm, a=b=3.87, c=6.40, A at origin, B at (0,0,0.4021)
        lattice = Lattice(3.86859720, 3.86859720, 6.40422248, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("I4/mmm", 139),
        symmetryOperations = SpaceGroupCatalog.operations("I4/mmm"),
        sites = listOf(
            Site(aSymbol, "A0", Species(aSymbol), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site(bSymbol, "B1", Species(bSymbol), FractionalCoordinate(0.0, 0.0, 0.40210729)),
        ),
    )

    @Test
    fun cac2ResolvesLikeBao2() {
        val cac2 = carbideStructure("Ca", "C")
        val r1 = BondValence.smartIonicRules(cac2, BondConfiguration(), 0.45)
        assertTrue(r1.success, "CaC2 smartIonic must resolve (C is a carbide anion)")
        assertTrue(r1.rules.any { setOf(it.siteA, it.siteB) == setOf("Ca", "C") },
            "CaC2 must produce a Ca-C rule")

        // Same geometry with O instead of C — must behave identically.
        val bao2 = carbideStructure("Ca", "O")
        val r2 = BondValence.smartIonicRules(bao2, BondConfiguration(), 0.45)
        assertTrue(r2.success, "BaO2-equivalent structure must resolve")
        assertTrue(r2.rules.any { setOf(it.siteA, it.siteB) == setOf("Ca", "O") },
            "oxide must produce an A-B rule")
    }

    @Test
    fun carbonWithMoreElectronegativeNeighbourStaysCation() {
        // C bonded to O must stay on the cation path (like carbonate CO3): the smartIonic
        // analysis must resolve and keep C as a cation (hasMoreElectronegativeNeighbour).
        val carbonateLike = CrystalStructure(
            blockName = "cox",
            lattice = Lattice(6.0, 6.0, 6.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("C", "C1", Species("C"), FractionalCoordinate(0.5, 0.5, 0.5)),
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.7)),
            ),
        )
        val r = BondValence.smartIonicRules(carbonateLike, BondConfiguration(), 0.45)
        assertTrue(r.success, "C-O structure must resolve (C cation via electronegativity fall-through)")
    }
}
