package com.krystals.interaction.camera

import com.krystals.crystal.core.math.rotX
import com.krystals.crystal.core.math.rotY
import com.krystals.renderer.core.camera.Camera

object OrbitController {
    fun orbit(camera: Camera, dxPx: Float, dyPx: Float, sensitivity: Float = 0.32f): Camera {
        val increment = rotX((-dyPx * sensitivity).toDouble()) * rotY((-dxPx * sensitivity).toDouble())
        return camera.copy(rotation = (increment * camera.rotation).orthonormalized())
    }
}
