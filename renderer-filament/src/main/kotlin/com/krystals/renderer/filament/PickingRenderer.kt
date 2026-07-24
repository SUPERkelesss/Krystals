package com.krystals.renderer.filament

import android.os.Handler
import com.google.android.filament.View
import com.krystals.interaction.selection.PickResult
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.sceneProjection
import kotlin.math.max
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Coordinates the offscreen ID pass. Requests are sequenced so a late GPU readback can never
 * overwrite a newer click. CPU projection is retained as an immediate fallback for lost surfaces.
 */
class PickingRenderer {
    private var latestRequest = 0L
    private var scene: RenderScene? = null
    private var interaction = InteractionState()

    fun submit(scene: RenderScene) { this.scene = scene }
    fun clear() { scene = null }
    fun updateInteraction(state: InteractionState) { interaction = state }

    suspend fun pick(x: Float, y: Float): PickResult? {
        val request = ++latestRequest
        val result = projectPick(x, y)
        return result.takeIf { request == latestRequest }
    }

    /** Filament's native pick API executes an asynchronous offscreen ID pass and readback. */
    suspend fun pickGpu(
        view: View,
        x: Float,
        y: Float,
        callbackHandler: Handler,
        objectIdForEntity: (Int) -> String?,
    ): PickResult? {
        val request = ++latestRequest
        val viewport = view.viewport
        return suspendCoroutine { continuation ->
            view.pick(x.toInt(), (viewport.height - 1 - y.toInt()).coerceAtLeast(0), callbackHandler) { result ->
                if (request != latestRequest) {
                    continuation.resume(null)
                } else {
                    val objectId = objectIdForEntity(result.renderable)
                    val atom = scene?.atoms?.firstOrNull { it.id == objectId }?.atom
                    val gpuResult = objectId?.let { PickResult(it, atom?.id, atom?.siteId) }
                    continuation.resume(gpuResult?.takeIf { it.atomId != null } ?: projectPick(x, y))
                }
            }
        }
    }

    fun decodePickId(red: Int, green: Int, blue: Int): Int =
        (red and 0xFF) or ((green and 0xFF) shl 8) or ((blue and 0xFF) shl 16)

    internal fun projectPick(x: Float, y: Float): PickResult? {
        val snapshot = scene ?: return null
        val camera = interaction.session.camera
        // Project through the same SceneProjection the renderer and overlay use, so a tap
        // hits the atom that is actually rendered at that position (previously this used a
        // legacy-style projection, so the ring landed on a different atom than the one tapped).
        val projection = snapshot.sceneProjection(
            camera,
            interaction.session.viewportWidth,
            interaction.session.viewportHeight,
        )
        val scale = projection.screenScale()
        return snapshot.atoms.asSequence().filter { it.visible }.map { atom ->
            val position = projection.viewPosition(atom.atom.cartesianCoordinate.toVec3())
            val (px, py) = projection.projectView(position)
            val radius = max(22.0, atom.radius * scale * 1.35)
            val distanceSquared = (px - x) * (px - x) + (py - y) * (py - y)
            Triple(atom, position.z, distanceSquared / (radius * radius))
        }.filter { it.third <= 1.0 }
            .minWithOrNull(compareBy<Triple<com.krystals.renderer.core.primitive.AtomInstance, Double, Double>> { it.third }.thenByDescending { it.second })
            ?.first?.let { PickResult(it.id, it.atom.id, it.atom.siteId) }
    }

}
