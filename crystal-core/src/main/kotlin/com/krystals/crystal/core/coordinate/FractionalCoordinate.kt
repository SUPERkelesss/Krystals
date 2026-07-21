package com.krystals.crystal.core.coordinate

import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary

data class FractionalCoordinate(val x: Double, val y: Double, val z: Double) {
    operator fun plus(offset: Int3) = FractionalCoordinate(
        x + offset.x,
        y + offset.y,
        z + offset.z,
    )

    operator fun minus(other: FractionalCoordinate) = FractionalCoordinate(
        x - other.x,
        y - other.y,
        z - other.z,
    )

    fun toVec3() = Vec3(x, y, z)
    fun wrapped() = PeriodicBoundary.wrap(this)
    fun almostEquals(other: FractionalCoordinate, epsilon: Double = 1e-6) =
        PeriodicBoundary.equivalent(this, other, epsilon)

    companion object {
        val ZERO = FractionalCoordinate(0.0, 0.0, 0.0)
        fun fromVec3(value: Vec3) = FractionalCoordinate(value.x, value.y, value.z)
    }
}
