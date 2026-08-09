package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cooperative cancellation for the CPU-bound bond-valence / smart-ionic paths. A caller-side
 * cancelCheck must abort the periodic Voronoi search with [VoronoiAbortedException] instead of
 * letting the computation run to completion — this is what lets the app cancel stale rebuilds
 * (back button, new epsilon) and stop BVS restarts from piling up on epsilon changes.
 */
class BvsCancellationTest {

    // CaC2 geometry (I4/mmm, 2 ASU sites): minimal structure whose smart-ionic analysis runs the
    // periodic Voronoi search (same fixture as SmartIonicCarbideTest).
    private fun carbideStructure(): CrystalStructure = CrystalStructure(
        blockName = "carbide",
        lattice = Lattice(3.86859720, 3.86859720, 6.40422248, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("I4/mmm", 139),
        symmetryOperations = SpaceGroupCatalog.operations("I4/mmm"),
        sites = listOf(
            Site("Ca", "A0", Species("Ca"), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site("C", "B1", Species("C"), FractionalCoordinate(0.0, 0.0, 0.40210729)),
        ),
    )

    @Test
    fun bondValenceSumsHonoursCancellationCheck() {
        val structure = carbideStructure()
        // A check that immediately rejects aborts the Voronoi search on its first poll.
        assertFailsWith<VoronoiAbortedException> {
            BondValence.bondValenceSums(structure, BondConfiguration(), cancelCheck = { false })
        }
    }

    @Test
    fun rebuildBondRulesPropagatesCancellation() {
        val structure = carbideStructure()
        // SMART_IONIC rebuild runs smartIonicRules → periodic Voronoi; the cancelCheck must
        // reach the Voronoi search and abort it (not be swallowed by any fallback).
        assertFailsWith<VoronoiAbortedException> {
            CrystalEditor.rebuildBondRules(
                structure, BondConfiguration(), RadiusSource.SMART_IONIC, 0.45,
                cancelCheck = { false },
            )
        }
    }

    @Test
    fun bondValenceSumsIgnoresEpsilonInput() {
        val structure = carbideStructure()
        // epsilon is a bond-window threshold for rule generation; BVS never uses it, so changing
        // it must not change the per-site sums (the app keys the BVS produceState off this).
        val low = BondValence.bondValenceSums(structure, BondConfiguration(), epsilon = 0.1)
        val high = BondValence.bondValenceSums(structure, BondConfiguration(), epsilon = 0.9)
        assertEquals(low, high, "epsilon must not change the bond-valence sums")
    }
}
