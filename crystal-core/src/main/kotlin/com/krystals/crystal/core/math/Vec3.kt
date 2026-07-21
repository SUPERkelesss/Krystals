package com.krystals.crystal.core.math

import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vec3(x / scale, y / scale, z / scale)

    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun lengthSquared() = dot(this)
    fun length() = sqrt(lengthSquared())
    fun normalized() = if (length() < 1e-12) ZERO else this / length()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
