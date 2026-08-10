package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.Bond
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.HydrogenBond
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.SceneBuildOptions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per hbond-angle-threshold: the scene builder filters hbonds by the D–H···A angle
 * (vertex at H, measured from the concrete rendered atom images). Only hbonds whose angle
 * EXCEEDS `hbondAngleThreshold` are visible; threshold <= 0 disables the filter; hbonds
 * whose donor H has no covalent partner retain custom-rule behaviour; auto-detected contacts
 * require a D-H donor.
 *
 * Geometry (10 Å cubic cell, P1):
 *   H (0.4, 0.5, 0.5)   — donor
 *   O (0.5, 0.5, 0.5)   — covalent partner of H (D–H = 1 Å along +x)
 *   A (0.2, 0.5, 0.5)   — acceptor at H–A = 2 Å along −x → D–H···A = 180° (linear)
 *   B (0.5, 0.6, 0.5)   — acceptor at H–B = (1, 1, 0) → D–H···A = 45° (bent)
 *   C (0.6, 0.4, 0.5)   — acceptor with NO covalent partner (angle filter skipped)
 */
class HbondAngleThresholdTest {

    private val structure = CrystalStructure(
        blockName = "hbondAngle",
        lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = SpaceGroupCatalog.operations("P1"),
        sites = listOf(
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.4, 0.5, 0.5)),
            Site("O1", "O1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.5)),
            Site("A1", "A1", Species("O"), FractionalCoordinate(0.2, 0.5, 0.5)),
            Site("B1", "B1", Species("O"), FractionalCoordinate(0.5, 0.6, 0.5)),
            Site("C1", "C1", Species("O"), FractionalCoordinate(0.6, 0.4, 0.5)),
        ),
    )

    private fun atom(id: Long, siteId: String, frac: Triple<Double, Double, Double>): AtomImage {
        val f = FractionalCoordinate(frac.first, frac.second, frac.third)
        val cart = structure.lattice.toCartesian(f)
        return AtomImage(
            id = id,
            siteId = siteId,
            siteLabel = siteId,
            species = Species(if (siteId == "H1") "H" else "O"),
            fractionalCoordinate = f,
            cartesianCoordinate = cart,
            occupancy = 1.0,
            cellOffset = Int3(0, 0, 0),
        )
    }

    private fun hbond(donorId: Long, acceptorId: Long, siteB: String, distance: Double): HydrogenBond =
        HydrogenBond(
            donorId = donorId,
            acceptorId = acceptorId,
            distance = distance,
            siteA = "H1",
            siteB = siteB,
            ruleKey = listOf("H1", siteB).sorted().joinToString("\u0000") + "\u0000hbond",
        )

    private fun makeNetwork(
        atoms: List<AtomImage>,
        hbonds: List<HydrogenBond>,
        covalentBonds: List<Bond>,
    ): BondNetwork = BondNetwork(
        atoms = atoms,
        bonds = covalentBonds,
        hbonds = hbonds,
        structure = structure,
        expansion = Expansion(),
    )

    private fun visibleHbondIds(network: BondNetwork, sceneOptions: SceneBuildOptions): Set<Long> =
        CrystalSceneBuilder().build(structure, network, sceneOptions).hbonds
            .filter { it.visible }
            .map { it.hbond.acceptorId }
            .toSet()

    private val allAtoms = listOf(
        atom(1, "H1", Triple(0.4, 0.5, 0.5)),
        atom(2, "O1", Triple(0.5, 0.5, 0.5)),
        atom(3, "A1", Triple(0.2, 0.5, 0.5)),
        atom(4, "B1", Triple(0.5, 0.6, 0.5)),
        atom(5, "C1", Triple(0.6, 0.4, 0.5)),
    )
    private val covalentRule = BondRule("H1", "O1", 0.1, 1.5, BondRuleSource.AUTO)
    private val hOcovalent = Bond(1, 2, 1.0, covalentRule, Int3(0, 0, 0))

    @Test
    fun threshold150ShowsOnlyLinearHbond() {
        val net = makeNetwork(
            allAtoms,
            listOf(hbond(1, 3, "A1", 2.0), hbond(1, 4, "B1", 1.41)),
            listOf(hOcovalent),
        )
        val visible = visibleHbondIds(net, SceneBuildOptions(hbondAngleThreshold = 150.0))
        assertEquals(setOf(3L), visible, "150° threshold: only the 180° hbond visible, got $visible")
    }

    @Test
    fun threshold30ShowsAllHbonds() {
        val net = makeNetwork(
            allAtoms,
            listOf(hbond(1, 3, "A1", 2.0), hbond(1, 4, "B1", 1.41)),
            listOf(hOcovalent),
        )
        val visible = visibleHbondIds(net, SceneBuildOptions(hbondAngleThreshold = 30.0))
        assertEquals(setOf(3L, 4L), visible, "30° threshold: both hbonds visible, got $visible")
    }

    @Test
    fun default110ShowsOnlyHbondsAbove110Degrees() {
        val net = makeNetwork(
            allAtoms,
            listOf(hbond(1, 3, "A1", 2.0), hbond(1, 4, "B1", 1.41)),
            listOf(hOcovalent),
        )
        val visible = visibleHbondIds(net, SceneBuildOptions())
        assertEquals(setOf(3L), visible, "default 110° threshold: 180° shown, 45° hidden, got $visible")
    }

    @Test
    fun customHbondWithoutCovalentPartnerRemainsVisible() {
        val net = makeNetwork(
            allAtoms,
            listOf(hbond(1, 5, "C1", 1.41)),
            emptyList(),
        )
        val visible = visibleHbondIds(net, SceneBuildOptions(hbondAngleThreshold = 150.0))
        assertEquals(setOf(5L), visible, "manual hbond rules without donor data remain visible, got $visible")
    }

    @Test
    fun concreteAcceptorImageIsNotWrappedToTheOppositeDirection() {
        // 4 A cell. D at x=2, H at x=3, so D-H points toward -x. The selected acceptor image
        // is at x=0.8: its ACTUAL H...A vector is -2.2 A and D-H...A = 0 degrees (reject).
        // Minimum-image wrapping changes it to +1.8 A and incorrectly reports 180 degrees.
        val smallCell = structure.copy(lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0))
        fun image(id: Long, siteId: String, x: Double, element: String) = AtomImage(
            id = id,
            siteId = siteId,
            siteLabel = siteId,
            species = Species(element),
            fractionalCoordinate = FractionalCoordinate(x / 4.0, 0.5, 0.5),
            cartesianCoordinate = CartesianCoordinate(x, 2.0, 2.0),
            occupancy = 1.0,
            cellOffset = Int3(0, 0, 0),
        )
        val atoms = listOf(
            image(1, "H1", 3.0, "H"),
            image(2, "D1", 2.0, "O"),
            image(3, "A1", 0.8, "O"),
        )
        val covalent = Bond(1, 2, 1.0, BondRule("H1", "D1", 0.1, 1.5, BondRuleSource.AUTO))
        val net = BondNetwork(
            atoms = atoms,
            bonds = listOf(covalent),
            hbonds = listOf(hbond(1, 3, "A1", 2.2)),
            structure = smallCell,
            expansion = Expansion(),
        )

        val visible = CrystalSceneBuilder().build(
            smallCell, net, SceneBuildOptions(hbondAngleThreshold = 110.0),
        ).hbonds.filter { it.visible }
        assertEquals(0, visible.size, "the wrong periodic O image must fail the D-H...A angle")
    }
}
