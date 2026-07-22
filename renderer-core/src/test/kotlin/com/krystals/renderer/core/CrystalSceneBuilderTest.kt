package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.SceneBuildOptions
import com.krystals.renderer.core.primitive.MeshKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrystalSceneBuilderTest {
    @Test
    fun buildsDeterministicAtomBondAndPolyhedronInstances() {
        val structure = structure()
        val rule = BondRule("Cs", "Cl", 0.1, 4.0, extendAcrossCell = true)
        val analysis = BondDetector.buildNetwork(
            structure,
            BondConfiguration(listOf(rule)),
            Expansion(),
        )
        val options = SceneBuildOptions(polyhedronSiteIds = setOf("Cs"))

        val first = CrystalSceneBuilder().build(structure, analysis, options)
        val second = CrystalSceneBuilder().build(structure, analysis, options)

        assertEquals(analysis.atoms.size, first.atoms.size)
        assertEquals(analysis.bonds.size, first.bonds.size)
        assertTrue(first.meshes.isNotEmpty())
        assertTrue(first.meshes.all { it.kind == MeshKind.POLYHEDRON_FACE })
        assertTrue(first.meshes.all { it.triangleIndices.size == (it.vertices.size - 2) * 3 })
        assertEquals(first.objects.map { it.id }, second.objects.map { it.id })
    }

    @Test
    fun visibilityDoesNotDiscardSourceInstances() {
        val structure = structure()
        val rule = BondRule("Cs", "Cl", 0.1, 4.0)
        val analysis = BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule)))

        val scene = CrystalSceneBuilder().build(
            structure,
            analysis,
            SceneBuildOptions(hiddenSiteIds = setOf("Cl"), showBonds = false),
        )

        assertEquals(analysis.atoms.size, scene.atoms.size)
        assertEquals(analysis.bonds.size, scene.bonds.size)
        assertTrue(scene.atoms.filter { it.atom.siteId == "Cl" }.all { !it.visible })
        assertFalse(scene.bonds.any { it.visible })
    }

    private fun structure() = CrystalStructure(
        blockName = "cscl",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(
            Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate.ZERO),
            Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
        ),
    )
}
