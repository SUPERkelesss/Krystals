package com.krystals.renderer.filament

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
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceManagerTest {
    @Test
    fun keepsStableIdsAndDoesNotRebuildForCameraOnlyChanges() {
        val manager = InstanceManager()
        val scene = scene(10)
        val first = manager.sync(scene)
        val ids = first.batches.values.flatten().associate { it.objectId to it.pickId }

        val second = manager.sync(scene.copy(camera = scene.camera.copy(zoom = 2.0)))
        val secondIds = second.batches.values.flatten().associate { it.objectId to it.pickId }

        assertEquals(ids, secondIds)
        assertTrue(second.added.isEmpty())
        assertTrue(second.updated.isEmpty())
        assertTrue(second.removed.isEmpty())
    }

    @Test
    fun hundredThousandAtomsKeepOneMaterialBatch() {
        val manager = InstanceManager()
        manager.sync(scene(100_000))

        assertEquals(100_000, manager.instanceCount)
        assertEquals(1, manager.drawCallCount)
    }

    @Test
    fun pickIdUsesAllThreeColorBytes() {
        assertEquals(0x563412, PickingRenderer().decodePickId(0x12, 0x34, 0x56))
    }

    private fun scene(count: Int): RenderScene {
        val species = Species("C")
        val material = Material(0xFF505050)
        val atoms = List(count) { index ->
            AtomInstance(
                id = "atom:$index",
                atom = AtomImage(
                    id = index.toLong(), siteId = "C", siteLabel = "C1", species = species,
                    fractionalCoordinate = FractionalCoordinate.ZERO,
                    cartesianCoordinate = CartesianCoordinate(index.toDouble(), 0.0, 0.0),
                    occupancy = 1.0, cellOffset = Int3(0, 0, 0),
                ),
                radius = 0.4,
                material = material,
            )
        }
        return RenderScene(structure(), Expansion(), atoms)
    }

    private fun structure() = CrystalStructure(
        blockName = "test",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(Site("C", "C1", Species("C"), FractionalCoordinate.ZERO)),
    )
}
