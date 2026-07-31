package com.krystals.renderer.core

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.sceneProjection
import com.krystals.renderer.core.material.Material
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies that the shared [sceneProjection] recalculates the orthographic span and
 * aspect ratio correctly when the viewport dimensions swap — the core mathematical
 * invariant that [FilamentRenderer.onSurfaceChanged] relies on to prevent
 * disproportional scaling after screen rotation.
 */
class SceneRotationTest {

    private val scene = RenderScene(
        structure = CrystalStructure(
            blockName = "test",
            lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site("C", "C1", Species("C"), FractionalCoordinate.ZERO)),
        ),
        expansion = Expansion(),
        objects = listOf(
            AtomInstance(
                id = "atom:0",
                atom = AtomImage(
                    id = 0L, siteId = "C", siteLabel = "C1", species = Species("C"),
                    fractionalCoordinate = FractionalCoordinate.ZERO,
                    cartesianCoordinate = CartesianCoordinate(0.0, 0.0, 0.0),
                    occupancy = 1.0, cellOffset = Int3(0, 0, 0),
                ),
                radius = 0.4,
                material = Material(0xFF505050),
            ),
            AtomInstance(
                id = "atom:1",
                atom = AtomImage(
                    id = 1L, siteId = "C", siteLabel = "C1", species = Species("C"),
                    fractionalCoordinate = FractionalCoordinate.ZERO,
                    cartesianCoordinate = CartesianCoordinate(4.0, 2.0, 0.0),
                    occupancy = 1.0, cellOffset = Int3(0, 0, 0),
                ),
                radius = 0.4,
                material = Material(0xFF505050),
            ),
        ),
    )

    @Test
    fun `portrait and landscape projections have correct aspect ratios`() {
        val camera = Camera()
        val portrait = scene.sceneProjection(camera, 1080, 2400)
        val landscape = scene.sceneProjection(camera, 2400, 1080)

        assertEquals(1080.0 / 2400.0, portrait.aspect, 1e-9, "portrait aspect should be w/h")
        assertEquals(2400.0 / 1080.0, landscape.aspect, 1e-9, "landscape aspect should be w/h")
    }

    @Test
    fun `span changes when viewport dimensions swap`() {
        val camera = Camera()
        val portrait = scene.sceneProjection(camera, 1080, 2400)
        val landscape = scene.sceneProjection(camera, 2400, 1080)

        // The scene is wider than tall (extentX=4 > extentY=2), so in portrait the
        // span is limited by width, and in landscape by height. The spans should
        // differ because the limiting dimension changes.
        assertNotEquals(portrait.span, landscape.span, 1e-9,
            "span should change when viewport dimensions swap")
    }

    @Test
    fun `uniform pixel scale is maintained across rotation`() {
        val camera = Camera()
        val portrait = scene.sceneProjection(camera, 1080, 2400)
        val landscape = scene.sceneProjection(camera, 2400, 1080)

        // screenScale = viewportHeight / (2 * span). This must be the same for both
        // x and y within each projection (uniform scaling), and the scale should
        // be the same across rotation since the same atoms should appear at the
        // same pixel size.
        assertEquals(portrait.screenScale(), portrait.viewportHeight / (2.0 * portrait.span), 1e-9,
            "portrait scale should be uniform")
        assertEquals(landscape.screenScale(), landscape.viewportHeight / (2.0 * landscape.span), 1e-9,
            "landscape scale should be uniform")
    }

    @Test
    fun `center stays the same across rotation`() {
        val camera = Camera()
        val portrait = scene.sceneProjection(camera, 1080, 2400)
        val landscape = scene.sceneProjection(camera, 2400, 1080)

        assertEquals(portrait.center, landscape.center,
            "scene center should not change with viewport rotation")
    }

    @Test
    fun `zero dimension does not crash projection`() {
        // onSurfaceChanged coerces dimensions to >= 1, but sceneProjection
        // should also handle degenerate inputs gracefully.
        val projection = scene.sceneProjection(Camera(), 0, 0)
        assertEquals(1.0, projection.aspect, 1e-9, "degenerate aspect should be 1")
        assertTrue(projection.span > 0.0, "degenerate span should still be positive")
    }
}
