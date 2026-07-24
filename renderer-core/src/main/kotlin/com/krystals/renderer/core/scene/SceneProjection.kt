package com.krystals.renderer.core.scene

import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.camera.Camera

/**
 * The single orthographic projection shared by every screen-space consumer: the Filament
 * camera, the 2D selection/measurement overlay, bitmap export and CPU picking. All of them
 * must project through this type so an atom always lands on the same pixel regardless of
 * who computes it — a tap, its picked atom and its selection ring stay in sync.
 *
 * Pixels have their origin at the viewport's top-left corner with y pointing down. View
 * space follows the legacy convention: x right, y up, z toward the viewer.
 */
data class SceneProjection(
    val camera: Camera,
    val center: Vec3,
    val span: Double,
    val aspect: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
) {
    /** Camera-space position of a world [position] (x right, y up, z toward the viewer). */
    fun viewPosition(position: Vec3): Vec3 = camera.rotation * (position - center)

    /** Projects a world position to viewport pixels. */
    fun project(position: Vec3): Pair<Double, Double> = projectView(viewPosition(position))

    /** Projects an already camera-space position (see [viewPosition]) to viewport pixels. */
    fun projectView(position: Vec3): Pair<Double, Double> =
        viewportWidth * 0.5 + camera.panX + position.x / (span * aspect) * viewportWidth * 0.5 to
            (viewportHeight * 0.5 + camera.panY - position.y / span * viewportHeight * 0.5)

    /** Screen pixels per world unit (uniform on both axes). */
    fun screenScale(): Double = viewportHeight * 0.5 / span

    /** Screen-space radius in pixels of a world-space [radius]. */
    fun screenRadius(radius: Double): Double = radius * screenScale()
}

/**
 * Builds the shared projection for a [width] x [height] viewport (pixels).
 *
 * The framing matches the legacy renderer exactly: the span derives from the rotated screen
 * extents of every atom in the scene (including hidden shell atoms, so hiding sites cannot
 * reframe the view), with the legacy 0.72 margin factor. [Camera.target] shifts the center
 * and [Camera.zoom] scales the span.
 */
fun RenderScene.sceneProjection(camera: Camera, width: Int, height: Int): SceneProjection {
    val w = width.coerceAtLeast(1).toDouble()
    val h = height.coerceAtLeast(1).toDouble()
    val bounds = allBounds()
    val center = (bounds?.center ?: Vec3.ZERO) + camera.target
    val span = if (atoms.isNotEmpty()) {
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        atoms.forEach { atom ->
            val rotated = camera.rotation * (atom.atom.cartesianCoordinate.toVec3() - center)
            if (rotated.x < minX) minX = rotated.x
            if (rotated.x > maxX) maxX = rotated.x
            if (rotated.y < minY) minY = rotated.y
            if (rotated.y > maxY) maxY = rotated.y
        }
        val extentX = (maxX - minX).coerceAtLeast(1.0)
        val extentY = (maxY - minY).coerceAtLeast(1.0)
        val baseScale = minOf(w / extentX, h / extentY) * 0.72
        h / (2.0 * baseScale * camera.zoom)
    } else {
        (bounds?.radius ?: 10.0) / camera.zoom
    }
    return SceneProjection(
        camera = camera,
        center = center,
        span = span,
        aspect = w / h,
        viewportWidth = w,
        viewportHeight = h,
    )
}
