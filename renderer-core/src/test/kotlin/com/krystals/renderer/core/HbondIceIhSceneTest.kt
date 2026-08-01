package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.SceneBuildOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: ice-Ih must produce visible H-bond [BondInstance]s in the scene.
 *
 * Ice-Ih (COD 1011023, P6_3cm, a=7.82 c=7.36, 36 atoms per unit cell).
 *
 * Geometry: 12 H₂O per cell → 24 H atoms, each donating one H-bond to a
 * neighbouring O.  21 of these 24 H···O pairs have BOTH atoms inside the
 * 1×1×1 expansion box and are therefore visible in the scene.  The remaining
 * 3 H-bonds have their acceptor O as an external-shell atom — the scene builder
 * hides bonds whose far endpoint is external shell unless `extendAtoB`/
 * `extendBtoA` is set (hbond rules default to false).
 *
 * Empirical (buildNetworkGridded, SceneBuildOptions defaults, 1×1×1 expansion):
 *   21 visible hbond bonds, 55 visible normal bonds = 76 visible bonds total.
 */
class HbondIceIhSceneTest {

    private fun iceIh(): CrystalStructure = CrystalStructure(
        blockName = "iceIh",
        lattice = Lattice(7.82, 7.82, 7.36, 90.0, 90.0, 120.0),
        spaceGroup = SpaceGroupCatalog.resolve("P63cm", 185),
        symmetryOperations = SpaceGroupCatalog.operations("P63cm"),
        sites = listOf(
            Site("O1", "O1", Species("O"), FractionalCoordinate(0.3333, 0.0, 0.0625)),
            Site("O2", "O2", Species("O"), FractionalCoordinate(0.6667, 0.0, 0.9375)),
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.3333, 0.0, 0.174)),
            Site("H2", "H2", Species("H"), FractionalCoordinate(0.438, 0.0, 0.026)),
            Site("H3", "H3", Species("H"), FractionalCoordinate(0.772, 0.105, 0.975)),
        ),
    )

    @Test
    fun iceIhSingleCellHas24VisibleHbondBonds() {
        val structure = iceIh()
        // 1. Generate smart-ionic rules + hbond rules (public overload).
        val result = BondValence.smartIonicRules(structure, BondConfiguration())

        // 2. Detect bonds with the gridded detector (production path).
        val config = BondConfiguration(result.rules)
        val network = BondDetector.buildNetworkGridded(structure, config, Expansion())

        // 3. Build the scene — this is what the renderer sees.
        val scene = CrystalSceneBuilder().build(structure, network, SceneBuildOptions())

        val visibleBonds = scene.bonds.filter { it.visible }
        val visibleHbonds = visibleBonds.filter { it.bond.rule.isHBond }
        val visibleNormal = visibleBonds.filter { !it.bond.rule.isHBond }

        // Ice-Ih per-unit-cell geometry: 12 H₂O → 24 H donors → 24 H-bonds (undirected).
        assertEquals(21, visibleHbonds.size, "visible hbond BondInstances in ice-Ih 1x1x1")
        // Normal bonds scale with expansion; this is the total from the gridded detector
        // (1×1×1) with scene-builder filtering. Non-zero sanity only — hbond count is the
        // primary regression guard.
        assertTrue(visibleNormal.size > 20, "visible normal bonds in ice-Ih, got ${visibleNormal.size}")
        assertTrue(visibleBonds.size > 40, "total visible bonds in ice-Ih, got ${visibleBonds.size}")

        // Sanity: every hbond is between H and O.
        visibleHbonds.forEach { bond ->
            val symbols = setOf(
                network.atoms.first { it.id == bond.bond.atomA }.species.symbol,
                network.atoms.first { it.id == bond.bond.atomB }.species.symbol,
            )
            assertEquals(setOf("H", "O"), symbols, "hbond must be H–O")
        }
    }
}
