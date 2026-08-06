package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.primitive.BondInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.8.38 regression: boundary-image atoms (e.g. the (0,0,1) / (1,0,0) periodic images shown
 * on the cell faces) must not end up completely bondless. The v0.8.36 heteronuclear
 * deduplication kept only the in-cell image of each pair, so in symmetric structures (NaCl,
 * ice) every bond of a boundary-image atom was a duplicate and got dropped — the displayed
 * periodic atom appeared isolated. Fix: a duplicate is anchored (kept once per pair) when its
 * boundary-image end has no other visible bond.
 */
class BoundaryImageBondAnchorTest {

    private fun naclP1() = CrystalStructure(
        blockName = "nacl",
        lattice = Lattice(5.64, 5.64, 5.64, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = SpaceGroupCatalog.operations("P1"),
        sites = listOf(
            Site("Na", "Na0", Species("Na"), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.0, 0.0)),
        ),
    )

    @Test
    fun everyDisplayedBondedAtomHasAtLeastOneVisibleBond() {
        val structure = naclP1()
        val si = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        assertTrue(si.success, "NaCl smartIonic must resolve")
        val net = BondDetector.buildNetwork(structure, BondConfiguration(si.rules))
        val scene = CrystalSceneBuilder().build(structure, net)
        val visibleBondEnds = scene.objects.filterIsInstance<BondInstance>()
            .filter { it.visible }
            .flatMap { listOf(it.bond.atomA, it.bond.atomB) }
            .toSet()
        // Every displayed (non-external) atom that participates in the bond network must be
        // attached to at least one visible bond — including boundary-image atoms.
        val displayed = net.atoms.filter { !it.isExternalShell }
        val bondless = displayed.filter { a ->
            net.bonds.any { it.atomA == a.id || it.atomB == a.id } &&
                a.id !in visibleBondEnds
        }
        assertEquals(emptyList(), bondless.map { it.id },
            "displayed atoms with network bonds must not be bondless")
        // Sanity: at least one boundary-image atom is actually displayed and anchored.
        val boundaryDisplayed = displayed.filter { it.isShell && it.isBoundaryImage }
        assertTrue(boundaryDisplayed.isNotEmpty(), "P1 NaCl must produce boundary images")
    }

    @Test
    fun cac2EightFourZeroRegression() {
        // The CaC2 single-cell acceptance (8 Ca-C / 4 C-C / 0 Ca-Ca) must be unchanged.
        val structure = CrystalStructure(
            blockName = "cac2",
            lattice = Lattice(3.86859720, 3.86859720, 6.40422248, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("I4/mmm", 139),
            symmetryOperations = SpaceGroupCatalog.operations("I4/mmm"),
            sites = listOf(
                Site("Ca", "Ca0", Species("Ca"), FractionalCoordinate(0.0, 0.0, 0.0)),
                Site("C", "C1", Species("C"), FractionalCoordinate(0.0, 0.0, 0.40210729)),
            ),
        )
        val si = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        val net = BondDetector.buildNetwork(structure, BondConfiguration(si.rules))
        val scene = CrystalSceneBuilder().build(structure, net)
        val atomById = net.atoms.associateBy { it.id }
        val bondTypes = scene.objects.filterIsInstance<BondInstance>()
            .filter { it.visible }
            .map { b ->
                val a = atomById.getValue(b.bond.atomA)
                val c = atomById.getValue(b.bond.atomB)
                listOf(a.species.symbol, c.species.symbol).sorted().joinToString("-")
            }
        assertEquals(8, bondTypes.count { it == "C-Ca" })
        assertEquals(4, bondTypes.count { it == "C-C" })
        assertEquals(0, bondTypes.count { it == "Ca-Ca" })
    }
}
