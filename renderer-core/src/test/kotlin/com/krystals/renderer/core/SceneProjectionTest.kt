package com.krystals.renderer.core.scene

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SceneProjectionTest {

    private fun atom(id: Long, position: Vec3): AtomInstance = AtomInstance(
        id = "atom:$id",
        atom = AtomImage(
            id = id,
            siteId = "C",
            siteLabel = "C$id",
            species = Species("C"),
            fractionalCoordinate = FractionalCoordinate.ZERO,
            cartesianCoordinate = CartesianCoordinate(position.x, position.y, position.z),
            occupancy = 1.0,
            cellOffset = Int3(0, 0, 0),
        ),
        radius = 0.5,
        material = Material(0xFF505050),
    )

    private fun scene(atoms: List<AtomInstance>) = RenderScene(
        structure = CrystalStructure(
            blockName = "test",
            lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site("C", "C1", Species("C"), FractionalCoordinate.ZERO)),
        ),
        expansion = Expansion(),
        objects = atoms,
    )

    // Atoms spanning x in [0, 4], y in [0, 2]; AABB center (2, 1, 0).
    private val atoms = listOf(
        atom(1L, Vec3(0.0, 0.0, 0.0)),
        atom(2L, Vec3(4.0, 2.0, 0.0)),
    )

    @Test
    fun spanMatchesLegacyScaleFormula() {
        val projection = scene(atoms).sceneProjection(Camera(rotation = Mat3.IDENTITY), 200, 100)
        // legacy: scale = min(w/extentX, h/extentY) * 0.72 * zoom = min(50, 50) * 0.72 = 36
        assertEquals(100.0 / (2.0 * 36.0), projection.span, 1e-9)
        assertEquals(Vec3(2.0, 1.0, 0.0), projection.center)
    }

    @Test
    fun boundsCenterProjectsToViewportCenterPlusPan() {
        val camera = Camera(rotation = Mat3.IDENTITY, panX = 10.0, panY = -5.0)
        val projection = scene(atoms).sceneProjection(camera, 200, 100)
        val (px, py) = projection.project(Vec3(2.0, 1.0, 0.0))
        assertEquals(110.0, px, 1e-9)
        assertEquals(45.0, py, 1e-9)
    }

    @Test
    fun viewSpaceEdgesMapToViewportEdges() {
        val projection = scene(atoms).sceneProjection(Camera(rotation = Mat3.IDENTITY), 200, 100)
        // +span in view Y is the top edge, -span the bottom edge.
        assertEquals(0.0, projection.projectView(Vec3(0.0, projection.span, 0.0)).second, 1e-9)
        assertEquals(100.0, projection.projectView(Vec3(0.0, -projection.span, 0.0)).second, 1e-9)
        // +span*aspect in view X is the right edge.
        assertEquals(200.0, projection.projectView(Vec3(projection.span * projection.aspect, 0.0, 0.0)).first, 1e-9)
    }

    @Test
    fun projectionMatchesLegacyPixelPositions() {
        val projection = scene(atoms).sceneProjection(Camera(rotation = Mat3.IDENTITY), 200, 100)
        // legacy: px = w/2 + panX + x*scale, py = h/2 + panY - y*scale with scale = 36.
        val (px, py) = projection.project(Vec3(4.0, 1.0, 0.0))
        assertEquals(100.0 + 2.0 * 36.0, px, 1e-9)
        assertEquals(50.0, py, 1e-9)
    }

    @Test
    fun cameraTargetShiftsProjectedPositions() {
        val camera = Camera(rotation = Mat3.IDENTITY, target = Vec3(1.0, 0.0, 0.0))
        val projection = scene(atoms).sceneProjection(camera, 200, 100)
        val (px, py) = projection.project(Vec3(2.0, 1.0, 0.0))
        assertEquals(100.0 - 36.0, px, 1e-9)
        assertEquals(50.0, py, 1e-9)
    }

    @Test
    fun screenRadiusScalesWithWorldUnits() {
        val projection = scene(atoms).sceneProjection(Camera(rotation = Mat3.IDENTITY), 200, 100)
        assertEquals(0.5 * 36.0, projection.screenRadius(0.5), 1e-9)
    }
}
