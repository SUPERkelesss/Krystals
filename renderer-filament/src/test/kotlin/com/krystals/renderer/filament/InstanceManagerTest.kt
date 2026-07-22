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
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerSessionState
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

    @Test
    fun sphereLodUsesStableSceneSizeThresholds() {
        assertEquals(GeometryKind.SPHERE_HIGH, InstanceManager.sphereGeometryForVisibleAtoms(2_000))
        assertEquals(GeometryKind.SPHERE_MEDIUM, InstanceManager.sphereGeometryForVisibleAtoms(2_001))
        assertEquals(GeometryKind.SPHERE_MEDIUM, InstanceManager.sphereGeometryForVisibleAtoms(20_000))
        assertEquals(GeometryKind.SPHERE_LOW, InstanceManager.sphereGeometryForVisibleAtoms(20_001))
    }

    @Test
    fun reflectionSettingChoosesLitOrUnlitMaterial() {
        val litOpaque = MaterialKey(Material(0xFFFFFFFF, reflective = true))
        val unlitOpaque = MaterialKey(Material(0xFFFFFFFF, reflective = false))
        val litTransparent = MaterialKey(Material(0xFFFFFFFF, opacity = 0.5, reflective = true))
        val unlitTransparent = MaterialKey(Material(0xFFFFFFFF, opacity = 0.5, reflective = false))

        assertEquals(MaterialKind.OPAQUE, materialKindFor(GeometryKind.SPHERE_HIGH, litOpaque))
        assertEquals(MaterialKind.UNLIT_OPAQUE, materialKindFor(GeometryKind.SPHERE_HIGH, unlitOpaque))
        assertEquals(MaterialKind.TRANSPARENT, materialKindFor(GeometryKind.CYLINDER, litTransparent))
        assertEquals(MaterialKind.UNLIT_TRANSPARENT, materialKindFor(GeometryKind.CYLINDER, unlitTransparent))
        assertEquals(MaterialKind.POLYHEDRON, materialKindFor(GeometryKind.POLYHEDRON, litTransparent))
        assertEquals(MaterialKind.UNLIT_POLYHEDRON, materialKindFor(GeometryKind.POLYHEDRON, unlitTransparent))
    }

    @Test
    fun projectedPickingUsesCurrentOrthographicCamera() {
        val picker = PickingRenderer()
        picker.submit(scene(3))
        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(
                    camera = Camera(),
                    viewportWidth = 1_000,
                    viewportHeight = 500,
                ),
            ),
        )

        assertEquals(1L, picker.projectPick(500f, 250f)?.atomId)
        assertEquals(null, picker.projectPick(20f, 20f))

        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(
                    camera = Camera(zoom = 2.0, panX = 60.0, panY = -30.0),
                    viewportWidth = 1_000,
                    viewportHeight = 500,
                ),
            ),
        )
        assertEquals(0L, picker.projectPick(200f, 220f)?.atomId)
    }

    @Test
    fun projectedPickingIgnoresHiddenAtoms() {
        val snapshot = scene(3)
        val picker = PickingRenderer()
        picker.submit(snapshot.copy(objects = snapshot.objects.map {
            val atom = it as? AtomInstance
            if (atom?.atom?.id == 1L) atom.copy(visible = false) else it
        }))
        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(camera = Camera(), viewportWidth = 1_000, viewportHeight = 500),
            ),
        )

        assertEquals(null, picker.projectPick(500f, 250f))
    }

    @Test
    fun meshBoundsAreComputedFromLocalGeometry() {
        val bounds = MeshBounds.fromPositions(
            floatArrayOf(-2f, 1f, 3f, 4f, 5f, -1f, 1f, -3f, 2f),
        )

        assertEquals(1f, bounds.centerX)
        assertEquals(1f, bounds.centerY)
        assertEquals(1f, bounds.centerZ)
        assertEquals(3f, bounds.halfExtentX)
        assertEquals(4f, bounds.halfExtentY)
        assertEquals(2f, bounds.halfExtentZ)
    }

    @Test
    fun dirtyFrameBudgetStopsAfterRequestedFrames() {
        val budget = DirtyFrameBudget()
        budget.request(2)
        budget.request(1)

        assertEquals(2, budget.pending)
        budget.rendered()
        assertTrue(budget.hasPending)
        budget.rendered()
        assertEquals(0, budget.pending)
        assertTrue(!budget.hasPending)

        budget.request(1)
        budget.reset()
        assertEquals(0, budget.pending)
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
