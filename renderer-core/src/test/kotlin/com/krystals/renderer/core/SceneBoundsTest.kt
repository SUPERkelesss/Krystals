package com.krystals.renderer.core

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.eulerYX
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.visibleBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneBoundsTest {
    @Test
    fun emptyAndFullyHiddenScenesHaveNoVisibleBounds() {
        assertNull(RenderScene(structure(), Expansion(), emptyList()).visibleBounds())
        assertNull(RenderScene(structure(), Expansion(), listOf(atom(1, 2.0, 3.0, 4.0, visible = false))).visibleBounds())
    }

    @Test
    fun boundsIgnoreHiddenAtomsAndExposeEightCorners() {
        val scene = RenderScene(
            structure(),
            Expansion(),
            listOf(
                atom(1, -2.0, 1.0, 3.0),
                atom(2, 4.0, 5.0, -1.0),
                atom(3, 100.0, 100.0, 100.0, visible = false),
            ),
        )

        val bounds = assertNotNull(scene.visibleBounds())
        assertEquals(-2.0, bounds.min.x)
        assertEquals(-1.0, bounds.min.z)
        assertEquals(4.0, bounds.max.x)
        assertEquals(5.0, bounds.max.y)
        assertEquals(8, bounds.corners.size)
        assertEquals(6.0 / 1.44, bounds.radius, 1e-9)
    }

    @Test
    fun rotatedCornerDepthRangeCoversEveryVisibleAtom() {
        val scene = RenderScene(
            structure(),
            Expansion(),
            listOf(
                atom(1, -3.0, 2.0, 1.0),
                atom(2, 4.0, -5.0, 6.0),
                atom(3, 0.5, 1.5, -2.0),
            ),
        )
        val bounds = assertNotNull(scene.visibleBounds())
        val rotation = eulerYX(37.0, -21.0)
        val cornerDepths = bounds.corners.map { (rotation * (it - bounds.center)).z }
        val atomDepths = scene.atoms.map { (rotation * (it.atom.cartesianCoordinate.toVec3() - bounds.center)).z }

        assertTrue(atomDepths.min() >= cornerDepths.min() - 1e-9)
        assertTrue(atomDepths.max() <= cornerDepths.max() + 1e-9)
    }

    private fun atom(id: Long, x: Double, y: Double, z: Double, visible: Boolean = true) = AtomInstance(
        id = "atom:$id",
        atom = AtomImage(
            id = id,
            siteId = "C",
            siteLabel = "C1",
            species = Species("C"),
            fractionalCoordinate = FractionalCoordinate.ZERO,
            cartesianCoordinate = CartesianCoordinate(x, y, z),
            occupancy = 1.0,
            cellOffset = Int3(0, 0, 0),
        ),
        radius = 0.4,
        material = Material(0xFF505050),
        visible = visible,
    )

    private fun structure() = CrystalStructure(
        blockName = "test",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(Site("C", "C1", Species("C"), FractionalCoordinate.ZERO)),
    )
}
