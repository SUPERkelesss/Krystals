package com.krystals.renderer.filament

import android.os.Handler
import com.google.android.filament.View
import com.krystals.crystal.core.math.Vec3
import com.krystals.interaction.selection.PickResult
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.scene.RenderScene
import kotlin.math.max
import kotlin.math.min
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
                    continuation.resume(objectId?.let { PickResult(it, atom?.id, atom?.siteId) })
                }
            }
        }
    }

    fun decodePickId(red: Int, green: Int, blue: Int): Int =
        (red and 0xFF) or ((green and 0xFF) shl 8) or ((blue and 0xFF) shl 16)

    private fun projectPick(x: Float, y: Float): PickResult? {
        val snapshot = scene ?: return null
        val visible = snapshot.atoms.filter { it.visible }
        if (visible.isEmpty()) return null
        val camera = interaction.session.camera
        val width = interaction.session.viewportWidth.coerceAtLeast(1)
        val height = interaction.session.viewportHeight.coerceAtLeast(1)
        val center = Vec3(
            (visible.minOf { it.atom.cartesianCoordinate.x } + visible.maxOf { it.atom.cartesianCoordinate.x }) * 0.5,
            (visible.minOf { it.atom.cartesianCoordinate.y } + visible.maxOf { it.atom.cartesianCoordinate.y }) * 0.5,
            (visible.minOf { it.atom.cartesianCoordinate.z } + visible.maxOf { it.atom.cartesianCoordinate.z }) * 0.5,
        )
        val positions = visible.map { it to camera.rotation * (it.atom.cartesianCoordinate.toVec3() - center - camera.target) }
        val minX = positions.minOf { it.second.x }; val maxX = positions.maxOf { it.second.x }
        val minY = positions.minOf { it.second.y }; val maxY = positions.maxOf { it.second.y }
        val scale = min(width / max(1.0, maxX - minX), height / max(1.0, maxY - minY)) * 0.72 * camera.zoom
        return positions.asSequence().map { (atom, position) ->
            val px = width / 2.0 + camera.panX + position.x * scale
            val py = height / 2.0 + camera.panY - position.y * scale
            val radius = (atom.radius * scale).coerceIn(4.5, 42.0)
            val distanceSquared = (px - x) * (px - x) + (py - y) * (py - y)
            Triple(atom, position.z, distanceSquared / (radius * radius))
        }.filter { it.third <= 1.35 * 1.35 }
            .minWithOrNull(compareBy<Triple<com.krystals.renderer.core.primitive.AtomInstance, Double, Double>> { it.third }.thenByDescending { it.second })
            ?.first?.let { PickResult(it.id, it.atom.id, it.atom.siteId) }
    }

}
