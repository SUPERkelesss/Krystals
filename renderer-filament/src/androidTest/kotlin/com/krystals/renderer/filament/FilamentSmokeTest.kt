package com.krystals.renderer.filament

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentSmokeTest {
    @Test
    fun engineCreatesAndClosesWithBundledMaterials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FilamentRenderer(context).close()
    }

    @Test
    fun repeatedSceneAndInteractionInputsAreIgnored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var snapshot: RendererPerformanceSnapshot
        instrumentation.runOnMainSync {
            val renderer = FilamentRenderer(instrumentation.targetContext)
            val scene = scene()
            renderer.submit(scene)
            renderer.submit(scene)
            renderer.updateInteraction(InteractionState())
            val moved = InteractionState(
                session = InteractionState().session.copy(
                    camera = InteractionState().session.camera.copy(zoom = 2.0),
                ),
            )
            renderer.updateInteraction(moved)
            renderer.updateInteraction(moved)
            snapshot = renderer.performanceSnapshot()
            renderer.close()
        }

        assertEquals(1L, snapshot.sceneSubmissions)
        assertEquals(1L, snapshot.interactionUpdates)
        assertEquals(8, snapshot.depthPointsEvaluated)
    }

    @Test
    fun dirtyFramesStopAfterSceneSettles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var renderer: FilamentRenderer
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        instrumentation.runOnMainSync {
            renderer = FilamentRenderer(instrumentation.targetContext)
            texture = SurfaceTexture(false).apply { setDefaultBufferSize(128, 128) }
            surface = Surface(texture)
            renderer.attach(surface)
            renderer.submit(scene())
        }

        SystemClock.sleep(500)

        lateinit var snapshot: RendererPerformanceSnapshot
        instrumentation.runOnMainSync {
            snapshot = renderer.performanceSnapshot()
            renderer.close()
            surface.release()
            texture.release()
        }
        assertTrue(snapshot.framesRendered in 1L..2L)
        assertEquals(0, snapshot.pendingFrames)
        assertFalse(snapshot.frameScheduled)
    }

    private fun scene(): RenderScene {
        val species = Species("C")
        val atom = AtomInstance(
            id = "atom:1",
            atom = AtomImage(
                id = 1L,
                siteId = "C",
                siteLabel = "C1",
                species = species,
                fractionalCoordinate = FractionalCoordinate.ZERO,
                cartesianCoordinate = CartesianCoordinate(0.0, 0.0, 0.0),
                occupancy = 1.0,
                cellOffset = Int3(0, 0, 0),
            ),
            radius = 0.4,
            material = Material(0xFF505050),
        )
        return RenderScene(structure(), Expansion(), listOf(atom))
    }

    private fun structure() = CrystalStructure(
        blockName = "test",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(Site("C", "C1", Species("C"), FractionalCoordinate.ZERO)),
    )
}
