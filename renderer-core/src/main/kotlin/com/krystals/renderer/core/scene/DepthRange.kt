package com.krystals.renderer.core.scene

import com.krystals.renderer.core.camera.Camera

/**
 * Normalized view-space depth range produced from a scene bounding box.
 *
 * In the legacy convention used by Krystals:
 * - [near] maps to +3 (closest point of the visible scene to the camera).
 * - [far] maps to -3 (farthest point of the visible scene from the camera).
 */
data class SceneDepthRange(
    val near: Double,
    val far: Double,
)

/**
 * Rotates the 8 AABB corners of the visible scene into camera space and returns
 * the view-space depth span.
 *
 * The returned range covers only the atoms that are currently displayed
 * (`atom.visible == true`). It deliberately excludes hidden shell atoms or any
 * preloaded neighbor-cell content, so the depth-cueing scale adapts to what the
 * user actually sees.
 */
fun SceneBounds?.toCameraDepthRange(camera: Camera): SceneDepthRange? {
    val bounds = this ?: return null
    val corners = bounds.corners
    if (corners.isEmpty()) return null

    var near = Double.NEGATIVE_INFINITY
    var far = Double.POSITIVE_INFINITY
    corners.forEach { corner ->
        val viewZ = (camera.rotation * (corner - bounds.center - camera.target)).z
        if (viewZ > near) near = viewZ
        if (viewZ < far) far = viewZ
    }
    return SceneDepthRange(near = near, far = far)
}
