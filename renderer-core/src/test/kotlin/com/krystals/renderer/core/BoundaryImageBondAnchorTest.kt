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
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.7.0: boundary-image atoms (the periodic images displayed on the cell faces, e.g.
 * (1,0,0) / (0,0,1)) must keep their bonds — every displayed atom that participates in the
 * bond network must be attached to at least one visible bond, and in fact to ALL of them
 * (no heteronuclear dedup). Same-atom periodic self-images (Ca-Ca) are never rendered.
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
        // Sanity: at least one boundary-image atom is actually displayed and bonded.
        val boundaryDisplayed = displayed.filter { it.isShell && it.isBoundaryImage }
        assertTrue(boundaryDisplayed.isNotEmpty(), "P1 NaCl must produce boundary images")
        val bondedBoundary = boundaryDisplayed.filter { it.id in visibleBondEnds }
        assertTrue(bondedBoundary.isNotEmpty(), "boundary-image atoms must keep visible bonds")
    }

    @Test
    fun extendingBonds_doNotSurfaceOuterShellSelfImageAtoms() {
        // CaC2 with METALS_ONLY-style extension (Ca-Ca extendAtoB=true, like the app's default):
        // the outer-shell Ca images beyond the cell must NOT be displayed — they are same-atom
        // periodic self-images of the extended metal bond, not atoms the extension reaches
        // (v0.7.0). Reached outer-shell C atoms are still allowed.
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
        val rules = si.rules.map { if (it.siteA == "Ca" || it.siteB == "Ca") it.copy(extendAtoB = true) else it }
        val net = BondDetector.buildNetwork(structure, BondConfiguration(rules))
        val scene = CrystalSceneBuilder().build(structure, net)
        val visibleIds = scene.objects.filterIsInstance<AtomInstance>()
            .filter { it.visible }
            .map { it.atom.id }
            .toSet()
        val visibleOuterCa = net.atoms.filter { it.isExternalShell && it.species.symbol == "Ca" }
            .filter { it.id in visibleIds }
        assertEquals(emptyList(), visibleOuterCa.map { it.id },
            "outer-shell Ca self-image atoms must not be surfaced by the extend preference")
        // Sanity: at least one outer-shell C atom IS surfaced by the extended Ca-C bonds.
        val visibleOuterC = net.atoms.filter { it.isExternalShell && it.species.symbol == "C" }
            .filter { it.id in visibleIds }
        assertTrue(visibleOuterC.isNotEmpty(), "reached outer-shell C atoms stay visible")
    }

    @Test
    fun cac2NoSelfImageBondsWithFullCoordination() {
        // CaC2 single-cell acceptance: no Ca-Ca self-image bonds (even with the metal-extend
        // preference, which sets Ca-Ca extendAtoB=true); full Ca-C coordination (>= 8);
        // C-C dumbbells present.
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
        assertTrue(bondTypes.count { it == "C-Ca" } >= 8, "full Ca-C coordination")
        assertEquals(4, bondTypes.count { it == "C-C" }, "4 C-C dumbbell bonds")
        assertEquals(0, bondTypes.count { it == "Ca-Ca" }, "no Ca-Ca self-image bonds")
    }
}
