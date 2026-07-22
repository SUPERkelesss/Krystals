package com.krystals.interaction.camera

import com.krystals.crystal.core.math.eulerYX
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.renderer.core.camera.Camera
import kotlin.math.atan2

object AlignmentController {
    fun alignCartesian(camera: Camera, axis: Char): Camera {
        val (yaw, pitch) = when (axis.lowercaseChar()) {
            'x' -> -90.0 to 0.0
            'y' -> 0.0 to 90.0
            else -> 0.0 to 0.0
        }
        return camera.copy(rotation = eulerYX(yaw, pitch), panX = 0.0, panY = 0.0)
    }

    fun alignCellAxis(camera: Camera, axis: Char, lattice: Lattice): Camera {
        val vector = when (axis.lowercaseChar()) {
            'a' -> lattice.matrix.a
            'b' -> lattice.matrix.b
            else -> lattice.matrix.c
        }
        val pitch = atan2(vector.y, vector.z)
        val zPlane = vector.y * kotlin.math.sin(pitch) + vector.z * kotlin.math.cos(pitch)
        val yaw = atan2(-vector.x, zPlane)
        return camera.copy(rotation = eulerYX(Math.toDegrees(yaw), Math.toDegrees(pitch)), panX = 0.0, panY = 0.0)
    }
}
