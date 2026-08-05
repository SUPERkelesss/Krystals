package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * v0.8.36: neutral-element fallback — a cation whose measured bond-valence sum is closer to
 * neutral 0 than to any tabulated valence (total BVS < 0.5) uses the neutral bonding radius
 * for its rule window instead of a Shannon radius at a spurious valence.
 */
class NeutralFallbackTest {

    @Test
    fun weaklyBondedCationUsesBondingRadius() {
        // Fe ... O at 4.0 A: BVS = exp((1.734-4.0)/0.37) ~ 0.002 << 0.5, so Fe is effectively
        // neutral. Its rule window must use the BONDING radius, not a Shannon radius.
        val structure = CrystalStructure(
            blockName = "weak",
            lattice = Lattice(8.0, 8.0, 8.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Fe", "Fe1", Species("Fe"), FractionalCoordinate(0.0, 0.0, 0.0)),
                Site("O", "O1", Species("O"), FractionalCoordinate(0.5, 0.0, 0.0)),
            ),
        )
        val r = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        assertTrue(r.success, "weak Fe-O must still resolve")
        val rule = r.rules.single { setOf(it.siteA, it.siteB) == setOf("Fe", "O") }
        // Fe uses the neutral bonding radius (~1.32); O stays a Shannon anion (~1.38):
        // window ~3.15 A. A Shannon Fe2+ window would be ~2.6 A.
        assertTrue(
            rule.maxAngstrom > 2.9,
            "Fe rule window must use the neutral bonding radius: got ${rule.maxAngstrom}",
        )
    }

    @Test
    fun stronglyBondedCationKeepsShannonRadius() {
        // Fe-O at 1.95 A: BVS = exp((1.734-1.95)/0.37) ~ 0.56 (Fe2+ param) — above the neutral
        // threshold, so Fe keeps its Shannon radius (window < bonding window).
        val structure = CrystalStructure(
            blockName = "strong",
            lattice = Lattice(8.0, 8.0, 8.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Fe", "Fe1", Species("Fe"), FractionalCoordinate(0.0, 0.0, 0.0)),
                Site("O", "O1", Species("O"), FractionalCoordinate(0.24375, 0.0, 0.0)),
            ),
        )
        val r = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        assertTrue(r.success, "strong Fe-O must resolve")
        val rule = r.rules.single { setOf(it.siteA, it.siteB) == setOf("Fe", "O") }
        // Fe keeps its Shannon Fe2+ radius (~0.78): window ~2.6 A, below the 2.9 split.
        assertTrue(
            rule.maxAngstrom < 2.9,
            "Fe with real BVS must keep the (smaller) Shannon window: got ${rule.maxAngstrom}",
        )
    }
}
