package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.SceneBuildOptions
import com.krystals.renderer.core.primitive.BondInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.8.37: secondary extend bonds — when a bond extends across the cell, the bonds of the
 * atoms the extension reaches (e.g. the C-C dumbbells of the C atoms reached by extending
 * Ca-C in CaC2) are shown too, controlled by the "Secondary bonds on extend" switch
 * (SceneBuildOptions.secondaryExtendBonds, default true).
 */
class SecondaryExtendBondTest {

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

    /** smartIonic rules with the metal (Ca) side of Ca-C set to extend across the cell. */
    private fun extendingRules(structure: CrystalStructure): List<BondRule> =
        BondValence.smartIonicRules(structure, BondConfiguration(), 0.45).rules.map { r ->
            if (r.siteA == "Ca" || r.siteB == "Ca") r.copy(extendAtoB = true) else r
        }

    private fun visibleShellCC(scene: com.krystals.renderer.core.scene.RenderScene, net: com.krystals.crystal.analysis.bonding.BondNetwork): Int {
        val atomById = net.atoms.associateBy { it.id }
        return scene.objects.filterIsInstance<BondInstance>()
            .filter { it.visible }
            .count { b ->
                val a = atomById.getValue(b.bond.atomA)
                val c = atomById.getValue(b.bond.atomB)
                listOf(a.species.symbol, c.species.symbol).sorted().joinToString("-") == "C-C" &&
                    (a.isExternalShell || c.isExternalShell)
            }
    }

    @Test
    fun extendingCaC_showsSecondaryDumbbellBondsByDefault() {
        val structure = caC2Structure()
        val net = BondDetector.buildNetwork(structure, BondConfiguration(extendingRules(structure)))
        val scene = CrystalSceneBuilder().build(
            structure, net, options = SceneBuildOptions(),
        )
        // The Ca-C extension reaches external-shell C atoms; their C-C dumbbell bonds with
        // in-cell C (secondary extend bonds) are visible by default.
        val shellCC = visibleShellCC(scene, net)
        assertTrue(shellCC >= 1, "secondary C-C dumbbell bonds should be visible (got $shellCC)")
        // The primary extend bonds themselves (Ca to external-shell C) are visible too.
        val atomById = net.atoms.associateBy { it.id }
        val primaryExtendVisible = scene.objects.filterIsInstance<BondInstance>()
            .filter { it.visible }
            .any { b ->
                val a = atomById.getValue(b.bond.atomA)
                val c = atomById.getValue(b.bond.atomB)
                listOf(a.species.symbol, c.species.symbol).sorted().joinToString("-") == "C-Ca" &&
                    (a.isExternalShell || c.isExternalShell)
            }
        assertTrue(primaryExtendVisible, "primary extend Ca-C bonds must stay visible")
    }

    @Test
    fun disablingSecondaryExtendBondsHidesOnlySecondaryBonds() {
        val structure = caC2Structure()
        val net = BondDetector.buildNetwork(structure, BondConfiguration(extendingRules(structure)))
        val scene = CrystalSceneBuilder().build(
            structure, net,
            options = SceneBuildOptions(secondaryExtendBonds = false),
        )
        assertEquals(0, visibleShellCC(scene, net), "secondary bonds hidden by the switch")
        // Primary extend bonds remain (switch only gates secondary).
        val atomById = net.atoms.associateBy { it.id }
        val primaryExtendVisible = scene.objects.filterIsInstance<BondInstance>()
            .filter { it.visible }
            .any { b ->
                val a = atomById.getValue(b.bond.atomA)
                val c = atomById.getValue(b.bond.atomB)
                listOf(a.species.symbol, c.species.symbol).sorted().joinToString("-") == "C-Ca" &&
                    (a.isExternalShell || c.isExternalShell)
            }
        assertTrue(primaryExtendVisible, "primary extend bonds are not gated by the secondary switch")
    }

    @Test
    fun nonExtendingSceneKeepsNoCaCaAndFullCoordination() {
        // Regression: without extension rules the single cell shows no Ca-Ca self-image bonds,
        // keeps the C-C dumbbells, and (since v0.8.40) the full Ca-C coordination (>= 8).
        val structure = caC2Structure()
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
        // Per v0.8.40: full Ca-C coordination (>= 8); 4 C-C dumbbells; no Ca-Ca self-images.
        assertTrue(bondTypes.count { it == "C-Ca" } >= 8)
        assertEquals(4, bondTypes.count { it == "C-C" })
        assertEquals(0, bondTypes.count { it == "Ca-Ca" })
    }
}