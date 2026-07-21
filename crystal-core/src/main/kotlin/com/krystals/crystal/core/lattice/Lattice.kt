package com.krystals.crystal.core.lattice

import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class Lattice(
    val a: Double,
    val b: Double,
    val c: Double,
    val alpha: Double,
    val beta: Double,
    val gamma: Double,
) {
    init {
        require(a > 0 && b > 0 && c > 0) { "Cell lengths must be positive" }
        require(alpha in 0.0..180.0 && beta in 0.0..180.0 && gamma in 0.0..180.0) {
            "Cell angles must be between 0 and 180 degrees"
        }
    }

    val matrix: Mat3 by lazy {
        val ar = alpha * PI / 180.0
        val br = beta * PI / 180.0
        val gr = gamma * PI / 180.0
        val av = Vec3(a, 0.0, 0.0)
        val bv = Vec3(b * cos(gr), b * sin(gr), 0.0)
        val cx = c * cos(br)
        val cy = c * (cos(ar) - cos(br) * cos(gr)) / max(1e-12, sin(gr))
        val cz = sqrt(max(0.0, c * c - cx * cx - cy * cy))
        Mat3(av, bv, Vec3(cx, cy, cz))
    }

    val volume: Double get() = abs(matrix.determinant())

    fun toCartesian(fractional: FractionalCoordinate): CartesianCoordinate =
        CartesianCoordinate.fromVec3(matrix * fractional.toVec3())

    fun toFractional(cartesian: CartesianCoordinate): FractionalCoordinate =
        FractionalCoordinate.fromVec3(matrix.inverse() * cartesian.toVec3())

    companion object {
        val DEFAULT = Lattice(1.0, 1.0, 1.0, 90.0, 90.0, 90.0)

        fun fromMatrix(matrix: Mat3): Lattice {
            fun angle(u: Vec3, v: Vec3): Double {
                val ratio = (u.dot(v) / (u.length() * v.length())).coerceIn(-1.0, 1.0)
                return acos(ratio) * 180.0 / PI
            }
            return Lattice(
                matrix.a.length(), matrix.b.length(), matrix.c.length(),
                angle(matrix.b, matrix.c), angle(matrix.a, matrix.c), angle(matrix.a, matrix.b),
            )
        }
    }
}
