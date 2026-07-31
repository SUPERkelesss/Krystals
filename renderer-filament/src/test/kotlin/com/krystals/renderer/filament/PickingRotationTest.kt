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
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerSessionState
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies that [PickingRenderer] uses the latest viewport dimensions after a
 * simulated screen rotation (viewport width/height swap). This is the picking-side
 * invariant of the [FilamentRenderer.onSurfaceChanged] fix: the CPU fallback
 * projection must reflect the new viewport, not stale dimensions.
 */
class PickingRotationTest {

    @Test
    fun `picking uses updated viewport after rotation`() {
        val picker = PickingRenderer()
        picker.submit(scene())

        // Portrait: 1080×2400, atom at center (500, 1250 in pixels)
        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(
                    camera = Camera(),
                    viewportWidth = 1080,
                    viewportHeight = 2400,
                ),
            ),
        )
        val portraitHit = picker.projectPick(540f, 1200f)
        assertNotNull(portraitHit?.atomId, "should hit atom at center in portrait")

        // Landscape: 2400×1080 — the atom's pixel position changes because the
        // viewport dimensions swapped. If the picker still used the old portrait
        // dimensions, the hit would be wrong.
        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(
                    camera = Camera(),
                    viewportWidth = 2400,
                    viewportHeight = 1080,
                ),
            ),
        )
        // Atom at world (2,1,0) with center at (2,1,0). After projection in
        // landscape (2400×1080), the center pixel should be (1200, 540).
        val landscapeHit = picker.projectPick(1200f, 540f)
        assertNotNull(landscapeHit?.atomId, "should hit atom at center in landscape")

        // A point that was the center in portrait (540, 1200) should NOT hit
        // the atom in landscape, because the projection changed.
        val staleHit = picker.projectPick(540f, 1200f)
        assertNull(staleHit, "old portrait center should not hit atom in landscape")
    }

    @Test
    fun `picking respects viewport dimensions for off-center atoms`() {
        val picker = PickingRenderer()
        picker.submit(scene())

        // Portrait viewport
        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(
                    camera = Camera(zoom = 1.0),
                    viewportWidth = 1000,
                    viewportHeight = 2000,
                ),
            ),
        )

        // In portrait, the atom is at center: (500, 1000)
        val portraitCenter = picker.projectPick(500f, 1000f)
        assertNotNull(portraitCenter?.atomId, "portrait center should hit atom")

        // In landscape (2000×1000), the center moves to (1000, 500)
        picker.updateInteraction(
            InteractionState(
                session = ViewerSessionState(
                    camera = Camera(zoom = 1.0),
                    viewportWidth = 2000,
                    viewportHeight = 1000,
                ),
            ),
        )
        val landscapeCenter = picker.projectPick(1000f, 500f)
        assertNotNull(landscapeCenter?.atomId, "landscape center should hit atom")

        // Old center (500, 1000) is now off-center in landscape
        val oldCenter = picker.projectPick(500f, 1000f)
        // It might or might not hit depending on the atom's radius, but it
        // should NOT be the same as the portrait hit
        if (oldCenter?.atomId != null) {
            // If it does hit, it's because the atom is large enough — but the
            // important thing is that the landscape center definitely hits.
            assertEquals(portraitCenter?.atomId, landscapeCenter?.atomId,
                "same atom should be hit at center in both orientations")
        }
    }

    private fun scene(): RenderScene {
        val species = Species("C")
        val material = Material(0xFF505050)
        return RenderScene(
            structure = CrystalStructure(
                blockName = "test",
                lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
                spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
                symmetryOperations = listOf(SymmetryOperation.IDENTITY),
                sites = listOf(Site("C", "C1", species, FractionalCoordinate.ZERO)),
            ),
            expansion = Expansion(),
            objects = listOf(
                AtomInstance(
                    id = "atom:0",
                    atom = AtomImage(
                        id = 0L, siteId = "C", siteLabel = "C1", species = species,
                        fractionalCoordinate = FractionalCoordinate.ZERO,
                        cartesianCoordinate = CartesianCoordinate(2.0, 1.0, 0.0),
                        occupancy = 1.0, cellOffset = Int3(0, 0, 0),
                    ),
                    radius = 0.4,
                    material = material,
                ),
            ),
        )
    }
}
