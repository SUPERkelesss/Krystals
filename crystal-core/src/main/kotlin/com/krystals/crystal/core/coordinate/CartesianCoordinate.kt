package com.krystals.crystal.core.coordinate

import com.krystals.crystal.core.math.Vec3

data class CartesianCoordinate(val x: Double, val y: Double, val z: Double) {
    fun toVec3() = Vec3(x, y, z)

    companion object {
        val ZERO = CartesianCoordinate(0.0, 0.0, 0.0)
        fun fromVec3(value: Vec3) = CartesianCoordinate(value.x, value.y, value.z)
    }
}
