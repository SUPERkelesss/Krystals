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

/**
 * Regression: H3PO4 must produce exactly 10 visible hbond BondInstances at scene level.
 *
 * H3PO4 (P2_1/c, Z=4, a=5.748, b=4.732, c=11.495, beta=95.80°):
 * 4 molecules per unit cell, each with 3 OH groups → 12 H atoms each donating one H-bond.
 *
 * Geometry: each H is covalently bonded to its parent O (O4/H1, O5/H2, O7/H3).
 * H-bonds form to acceptor O atoms of neighbouring molecules. With 1x1x1 expansion:
 *   12 H donors → 12 candidate H-bonds
 *   - 2 acceptors are external-shell atoms (hidden by scene builder)
 *   → 10 visible hbond bonds
 *
 * Per-H shortest-distance and angle >110-degree filters are applied at the bond level
 * (BondDetector post-filter v0.8.5), keeping exactly 10 surviving hbond bonds.
 */
class HbondH3po4SceneTest {

    private fun h3po4(): CrystalStructure = CrystalStructure(
        blockName = "H3PO4",
        lattice = Lattice(5.74790254, 4.73223527, 11.49463885, 90.0, 95.80202826, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P21/c", 14),
        symmetryOperations = SpaceGroupCatalog.operations("P21/c"),
        sites = listOf(
            Site("P", "P0", Species("P"), FractionalCoordinate(0.20862818, 0.68859841, 0.13907478)),
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.13477207, 0.00746911, 0.32888308)),
            Site("H2", "H2", Species("H"), FractionalCoordinate(0.27565435, 0.07566389, 0.04775379)),
            Site("H3", "H3", Species("H"), FractionalCoordinate(0.48417268, 0.13488864, 0.67682761)),
            Site("O4", "O4", Species("O"), FractionalCoordinate(0.05734793, 0.14127154, 0.38203676)),
            Site("O5", "O5", Species("O"), FractionalCoordinate(0.27258754, 0.62985965, 0.53453602)),
            Site("O6", "O6", Species("O"), FractionalCoordinate(0.28291049, 0.67807569, 0.75571357)),
            Site("O7", "O7", Species("O"), FractionalCoordinate(0.32852517, 0.10272094, 0.62678371)),
        ),
    )

    @Test
    fun h3po4SingleCellHas10VisibleHbondBonds() {
        val structure = h3po4()
        val result = BondValence.smartIonicRules(structure, BondConfiguration())
        val config = BondConfiguration(result.rules)
        val network = BondDetector.buildNetworkGridded(structure, config, Expansion())
        val scene = CrystalSceneBuilder().build(structure, network, SceneBuildOptions())

        val visibleBonds = scene.bonds.filter { it.visible }
        val visibleHbonds = visibleBonds.filter { it.bond.rule.isHBond }

        assertEquals(10, visibleHbonds.size, "visible hbond BondInstances in H3PO4 1x1x1")
    }
}
