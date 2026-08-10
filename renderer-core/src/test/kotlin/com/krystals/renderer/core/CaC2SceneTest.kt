package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.SceneBuildOptions
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v0.7.0: CaC2 (I4/mmm) single cell without cross-cell bond extension must render
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
        // Per v0.7.0: boundary bonds fully shown — every displayed atom pair in the cell is
        // connected, so Ca-C counts at least the 8 coordination bonds (more with face images).
        assertTrue(bondTypes.count { it == "C-Ca" } >= 8, "full Ca-C coordination in the non-extended cell")
        assertEquals(4, bondTypes.count { it == "C-C" }, "4 C-C dumbbell bonds")
        assertEquals(0, bondTypes.count { it == "Ca-Ca" }, "no Ca-Ca bonds without extension")
    }

    private fun caC2NetworkWithCaExtension(): BondNetwork {
        val structure = caC2Structure()
        val si = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45)
        assertTrue(si.success, "CaC2 smartIonic must resolve")
        val extendedRules = si.rules.map { rule ->
            when {
                rule.siteA == "Ca" && rule.siteB == "C" -> rule.copy(extendAtoB = true)
                rule.siteA == "C" && rule.siteB == "Ca" -> rule.copy(extendBtoA = true)
                else -> rule
            }
        }
        return BondDetector.buildNetwork(structure, BondConfiguration(extendedRules))
    }

    private fun visibleAtoms(scene: com.krystals.renderer.core.scene.RenderScene) =
        scene.objects.filterIsInstance<AtomInstance>().filter { it.visible }

    private fun visibleBonds(scene: com.krystals.renderer.core.scene.RenderScene) =
        scene.objects.filterIsInstance<BondInstance>().filter { it.visible }

    private fun isCarbonCarbon(bond: BondInstance, atomById: Map<Long, com.krystals.crystal.core.model.AtomImage>): Boolean {
        val a = atomById.getValue(bond.bond.atomA)
        val b = atomById.getValue(bond.bond.atomB)
        return a.species.symbol == "C" && b.species.symbol == "C"
    }

    @Test
    fun secondaryExtensionCompletesCaC2CarbonBondsWithoutAddingAtoms() {
        val network = caC2NetworkWithCaExtension()
        val atomById = network.atoms.associateBy { it.id }
        val off = CrystalSceneBuilder().build(
            network.structure,
            network,
            SceneBuildOptions(showSecondaryExtendBonds = false),
        )
        val on = CrystalSceneBuilder().build(
            network.structure,
            network,
            SceneBuildOptions(showSecondaryExtendBonds = true),
        )

        val offAtomIds = visibleAtoms(off).map { it.atom.id }.toSet()
        val onAtomIds = visibleAtoms(on).map { it.atom.id }.toSet()
        assertEquals(offAtomIds, onAtomIds, "secondary bonds must not introduce more shell atoms")

        val offBonds = visibleBonds(off)
        val onBonds = visibleBonds(on)
        assertTrue(offBonds.any { bond ->
            val a = atomById.getValue(bond.bond.atomA)
            val b = atomById.getValue(bond.bond.atomB)
            setOf(a.species.symbol, b.species.symbol) == setOf("Ca", "C") &&
                (a.isExternalShell || b.isExternalShell)
        }, "first-order Ca-C extension must remain visible when secondary bonds are disabled")
        assertFalse(offBonds.any { isCarbonCarbon(it, atomById) &&
            (atomById.getValue(it.bond.atomA).isExternalShell || atomById.getValue(it.bond.atomB).isExternalShell) },
            "external C-C secondary bonds must be hidden when disabled")

        assertTrue(onBonds.any { bond ->
            if (!isCarbonCarbon(bond, atomById)) return@any false
            val a = atomById.getValue(bond.bond.atomA)
            val b = atomById.getValue(bond.bond.atomB)
            a.isExternalShell.xor(b.isExternalShell)
        }, "secondary extension must show external-to-cell C-C bonds")
        assertTrue(onBonds.any { bond ->
            if (!isCarbonCarbon(bond, atomById)) return@any false
            val a = atomById.getValue(bond.bond.atomA)
            val b = atomById.getValue(bond.bond.atomB)
            a.isExternalShell && b.isExternalShell
        }, "secondary extension must synthesize external-to-external C-C bonds")
        assertFalse(onBonds.any { bond ->
            val a = atomById.getValue(bond.bond.atomA)
            val b = atomById.getValue(bond.bond.atomB)
            a.species.symbol == "Ca" && b.species.symbol == "Ca"
        }, "secondary extension must not resurrect Ca-Ca periodic self-image bonds")
    }

    @Test
    fun secondaryExtensionObeysVisibilityFilters() {
        val network = caC2NetworkWithCaExtension()
        val atomById = network.atoms.associateBy { it.id }
        val ccKey = network.bonds.first { bond ->
            val a = atomById.getValue(bond.atomA)
            val b = atomById.getValue(bond.atomB)
            a.species.symbol == "C" && b.species.symbol == "C"
        }.rule.key

        val hiddenSite = CrystalSceneBuilder().build(
            network.structure,
            network,
            SceneBuildOptions(hiddenSiteIds = setOf("C"), showSecondaryExtendBonds = true),
        )
        assertFalse(visibleBonds(hiddenSite).any { bond ->
            isCarbonCarbon(bond, atomById) &&
                (atomById.getValue(bond.bond.atomA).isExternalShell || atomById.getValue(bond.bond.atomB).isExternalShell)
        })

        val hiddenRule = CrystalSceneBuilder().build(
            network.structure,
            network,
            SceneBuildOptions(hiddenBondKeys = setOf(ccKey), showSecondaryExtendBonds = true),
        )
        assertFalse(visibleBonds(hiddenRule).any { isCarbonCarbon(it, atomById) })

        val allBondsOff = CrystalSceneBuilder().build(
            network.structure,
            network,
            SceneBuildOptions(showBonds = false, showSecondaryExtendBonds = true),
        )
        assertTrue(visibleBonds(allBondsOff).isEmpty())
    }
}
