package com.krystals.renderer.core.camera

import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3

data class Camera(
    val rotation: Mat3 = Mat3.IDENTITY,
    val target: Vec3 = Vec3.ZERO,
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
) {
    init {
        require(zoom > 0.0) { "zoom must be positive" }
    }
}
