package com.krystals.interaction.camera

import com.krystals.renderer.core.camera.Camera

object ZoomController {
    const val MIN_ZOOM = 0.08
    const val MAX_ZOOM = 25.0

    fun zoom(camera: Camera, factor: Float): Camera = camera.copy(
        zoom = (camera.zoom * factor.toDouble()).coerceIn(MIN_ZOOM, MAX_ZOOM),
    )
}
