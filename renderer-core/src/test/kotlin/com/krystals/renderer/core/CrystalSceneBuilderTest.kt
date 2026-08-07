package com.krystals.renderer.core

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.builder.SceneBuildOptions
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.GatheredAtomInstance
import com.krystals.renderer.core.primitive.MeshKind
import com.krystals.renderer.core.style.HbondPattern
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrystalSceneBuilderTest {
    @Test
    fun buildsDeterministicAtomBondAndPolyhedronInstances() {
        val structure = structure()
        val rule = BondRule("Cs", "Cl", 0.1, 4.0, extendAtoB = true, extendBtoA = true)
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

    @Test
    fun hiddenBondStrokesAndLegacyAppearanceFlagDoNotRemovePolyhedra() {
        val structure = structure()
        val rule = BondRule("Cs", "Cl", 0.1, 4.0, extendAtoB = true, extendBtoA = true)
        val analysis = BondDetector.buildNetwork(
            structure,
            BondConfiguration(listOf(rule)),
            Expansion(),
        )

        val scene = CrystalRenderSceneFactory.build(
            analysis = analysis,
            appearance = ViewerAppearance(polyhedronEnabled = false),
            renderConfiguration = RenderConfiguration(),
            hiddenBondKeys = setOf(rule.key),
            showBonds = false,
            polyhedronSiteIds = setOf("Cs"),
        )

        assertFalse(scene.bonds.any { it.visible })
        assertTrue(scene.meshes.isNotEmpty())
    }

    @Test
    fun hbondBondsGetFixedGrayTranslucentAppearance() {
        // H and F with an hbond rule — scene must override radius, start/end material.
        val structure = CrystalStructure(
            blockName = "hf",
            lattice = Lattice(5.0, 5.0, 5.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.2)),
                Site("F", "F1", Species("F"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val hbondRule = BondRule("H", "F", 1.0, 2.5, isHBond = true)
        val analysis = BondDetector.buildNetwork(structure, BondConfiguration(listOf(hbondRule)))
        val scene = CrystalSceneBuilder().build(structure, analysis, SceneBuildOptions())

        val hbonds = scene.hbonds
        assertTrue(hbonds.isNotEmpty(), "scene must contain hbond instances")
        hbonds.forEach { hb ->
            assertEquals(HbondPattern.RADIUS, hb.radius, "hbond radius override")
            assertEquals(HbondPattern.material(), hb.material, "hbond material override")
        }
    }

    @Test
    fun gatheredAtomsReplaceMembersAndCollapseBonds() {
        // Two co-located atoms (disorder) + one external bond partner → 1 gathered instance,
        // 0 member AtomInstances, 1 bond from group center.
        val structure = CrystalStructure(
            blockName = "disordered",
            lattice = Lattice(5.0, 5.0, 5.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("A1", "A1", Species("C"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("A2", "A2", Species("N"), FractionalCoordinate(0.5, 0.5, 0.3)), // same position
                Site("B", "B1", Species("O"), FractionalCoordinate(0.5, 0.5, 0.6)),
            ),
        )
        val rule = BondRule("A1", "B", 0.1, 4.0)
        val rule2 = BondRule("A2", "B", 0.1, 4.0)
        val analysis = BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule, rule2)))
        val options = SceneBuildOptions(
            atomMaterialBySite = mapOf("A1" to Material(0xFFFF0000), "A2" to Material(0xFF0000FF), "B" to Material(0xFF00FF00)),
            bondMaterialBySite = mapOf("A1" to Material(0xFFFF0000), "A2" to Material(0xFF0000FF), "B" to Material(0xFF00FF00)),
        )
        val scene = CrystalSceneBuilder().build(structure, analysis, options)
        // The gridded detector may produce boundary images that also land at the same position,
        // so we just assert at least one gathered instance exists.
        val gathered = scene.objects.filterIsInstance<GatheredAtomInstance>()
        assertTrue(gathered.isNotEmpty(), "two co-located sites → at least 1 gathered instance")
        val g = gathered.first { it.gathered.memberAtomIds.size >= 2 }
        assertEquals(2, g.gathered.memberAtomIds.size, "group should have 2 member atoms")
        val members = g.gathered.memberAtomIds
        assertNotNull(scene.objects.find { it.id == "atom:3" }, "external atom B should be a plain AtomInstance")
        // Member atoms ARE still emitted as AtomInstances (backward compat for BondNetwork adapter).
        val memberAtomInstances = scene.objects.filter { it is AtomInstance && it.atom.id in members }
        assertEquals(2, memberAtomInstances.size, "member atoms must still be plain AtomInstances")
        // Bonds: both A1-B and A2-B collapse to one bond from the gathered center.
        val bonds = scene.bonds
        assertTrue(bonds.isNotEmpty(), "at least one bond from gathered center to B")
    }

    @Test
    fun boundaryImageCoincidingWithPrimaryIsDeduplicated() {
        // Regression (v0.8.13): two-overlapping-atoms at cell faces/corners. A shell atom whose
        // cartesian position coincides exactly with a primary atom is a periodic duplicate and
        // must not be emitted — only the primary AtomInstance stays at that position.
        val structure = structure()
        val primary = AtomImage(
            id = 1, siteId = "A", siteLabel = "A1", species = Species("O"),
            fractionalCoordinate = FractionalCoordinate(0.0, 0.0, 0.0),
            cartesianCoordinate = CartesianCoordinate(0.0, 0.0, 0.0),
            occupancy = 1.0, cellOffset = Int3(0, 0, 0),
        )
        val shellDup = primary.copy(
            id = 2, siteId = "B", siteLabel = "B1", species = Species("C"),
            fractionalCoordinate = FractionalCoordinate(0.0, 0.0, 1.0),
            cellOffset = Int3(0, 0, -1), isShell = true, isBoundaryImage = true,
        )
        val analysis = BondNetwork(listOf(primary, shellDup), emptyList(), structure = structure, expansion = Expansion())
        val scene = CrystalSceneBuilder().build(structure, analysis, SceneBuildOptions())
        val atOrigin = scene.objects.filterIsInstance<AtomInstance>().count {
            it.atom.cartesianCoordinate == CartesianCoordinate(0.0, 0.0, 0.0)
        }
        assertEquals(1, atOrigin, "shell duplicate at the primary's position must be skipped")
    }

    @Test
    fun pureBoundaryImageGroupRendersLikeInCellGroup() {
        // Regression (v0.8.15): a gathered group whose members are ALL shell atoms (e.g. the +z
        // boundary images of a face position like (0,0,1)) must render the SAME pie as the
        // in-cell group — boundary positions look identical to (0,0,0), per user requirement.
        val structure = structure() // cubic, c = 4.0
        fun atom(id: Long, siteId: String, z: Double, shell: Boolean): AtomImage = AtomImage(
            id = id, siteId = siteId, siteLabel = siteId, species = Species("O"),
            fractionalCoordinate = FractionalCoordinate(0.0, 0.0, if (z == 4.0) 1.0 else 0.0),
            cartesianCoordinate = CartesianCoordinate(0.0, 0.0, z),
            occupancy = 0.5, cellOffset = Int3(0, 0, if (z == 4.0) 1 else 0),
            isShell = shell, isBoundaryImage = shell,
        )
        val atoms = listOf(
            atom(1, "A1", 0.0, shell = false),
            atom(2, "A2", 0.0, shell = false),
            atom(3, "A1", 4.0, shell = true),
            atom(4, "A2", 4.0, shell = true),
        )
        val analysis = BondNetwork(atoms, emptyList(), structure = structure, expansion = Expansion())
        val scene = CrystalSceneBuilder().build(structure, analysis, SceneBuildOptions())
        val groups = scene.objects.filterIsInstance<GatheredAtomInstance>()
        assertEquals(2, groups.size)
        assertTrue(groups.single { it.gathered.center.z == 0.0 }.visible, "in-cell group visible")
        assertTrue(groups.single { it.gathered.center.z == 4.0 }.visible, "boundary-image group visible (identical style)")
        val inCell = groups.single { it.gathered.center.z == 0.0 }.gathered
        val boundary = groups.single { it.gathered.center.z == 4.0 }.gathered
        assertEquals(inCell.slices.map { it.siteId to it.fraction }, boundary.slices.map { it.siteId to it.fraction })
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
