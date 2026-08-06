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
 * v0.8.36: CaC2 (I4/mmm) single cell without cross-cell bond extension must render
 * 8 Ca-C bonds (each of the 4 C atoms coordinated by 2 Ca), 4 C-C dumbbell bonds,
 * and NO Ca-Ca bonds (the Ca-Ca 3.87 A metal self-image bonds across the cell faces
 * must not appear). Regression: user observed Ca-Ca bonds in the non-extended view.
 */
class CaC2SceneTest {

    private fun caC2Structure() = CrystalStructure(
        blockName = "cac2",
        lattice = Lattice(3.86859720, 3.86859720, 6.40422248, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("I4/mmm", 139),
        symmetryOperations = SpaceGroupCatalog.operations("I4/mmm"),
        sites = listOf(
            Site("Ca", "Ca0", Species("Ca"), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site("C", "C1", Species("C"), FractionalCoordinate(0.0, 0.0, 0.40210729)),
        ),
    )

    @Test
    fun cac2UnitCellHasEightCaC_FourCC_NoCaCa() {
        val structure = caC2Structure()
        val si = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        assertEquals(true, si.success, "CaC2 smartIonic must resolve")
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
        // Per v0.8.42: boundary bonds fully shown — every displayed atom pair in the cell is
        // connected, so Ca-C counts at least the 8 coordination bonds (more with face images).
        assertTrue(bondTypes.count { it == "C-Ca" } >= 8, "full Ca-C coordination in the non-extended cell")
        assertEquals(4, bondTypes.count { it == "C-C" }, "4 C-C dumbbell bonds")
        assertEquals(0, bondTypes.count { it == "Ca-Ca" }, "no Ca-Ca bonds without extension")
    }
}
