package com.krystals.interaction.camera

import com.krystals.renderer.core.camera.Camera

object PanController {
    fun pan(camera: Camera, dxPx: Float, dyPx: Float): Camera = camera.copy(
        panX = camera.panX + dxPx,
        panY = camera.panY + dyPx,
    )
}
