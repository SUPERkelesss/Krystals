package com.krystals.crystal.core.periodic

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

object PeriodicBoundary {
    fun wrap(value: FractionalCoordinate) = FractionalCoordinate(
        wrap01(value.x),
        wrap01(value.y),
        wrap01(value.z),
    )

    fun equivalent(
        first: FractionalCoordinate,
        second: FractionalCoordinate,
        epsilon: Double = 1e-6,
    ): Boolean {
        val dx = first.x - second.x
        val dy = first.y - second.y
        val dz = first.z - second.z
        return abs(dx - round(dx)) < epsilon &&
            abs(dy - round(dy)) < epsilon &&
            abs(dz - round(dz)) < epsilon
    }

    fun isIntegerTranslation(value: FractionalCoordinate, epsilon: Double = 1e-6): Boolean =
        abs(value.x - round(value.x)) < epsilon &&
            abs(value.y - round(value.y)) < epsilon &&
            abs(value.z - round(value.z)) < epsilon

    fun neighborOffsets(range: IntRange = -1..1): List<Int3> = buildList {
        for (x in range) for (y in range) for (z in range) add(Int3(x, y, z))
    }

    fun wrap01(value: Double): Double {
        val wrapped = value - floor(value)
        return if (wrapped >= 1.0 - 1e-12 || abs(wrapped) < 1e-12) 0.0 else wrapped
    }
}
