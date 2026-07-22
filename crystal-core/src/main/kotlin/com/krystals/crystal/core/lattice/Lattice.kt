package com.krystals.crystal.core.lattice

import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
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
        require(listOf(a, b, c).all { it.isFinite() && it > 0.0 }) {
            "Cell lengths must be finite and positive"
        }
        require(listOf(alpha, beta, gamma).all { it.isFinite() && it > 0.0 && it < 180.0 }) {
            "Cell angles must be finite and strictly between 0 and 180 degrees"
        }
    }

    val matrix: Mat3 = buildMatrix().also { value ->
        val determinant = abs(value.determinant())
        require(determinant.isFinite() && determinant > MIN_CELL_VOLUME) {
            "Cell geometry must define a finite, non-degenerate volume"
        }
    }

    private fun buildMatrix(): Mat3 {
        val ar = alpha * PI / 180.0
        val br = beta * PI / 180.0
        val gr = gamma * PI / 180.0
        val cosAlpha = cos(ar)
        val cosBeta = cos(br)
        val cosGamma = cos(gr)
        val sinGamma = sin(gr)
        val volumeFactorSquared = 1.0 + 2.0 * cosAlpha * cosBeta * cosGamma -
            cosAlpha * cosAlpha - cosBeta * cosBeta - cosGamma * cosGamma
        val av = Vec3(a, 0.0, 0.0)
        val bv = Vec3(b * cosGamma, b * sinGamma, 0.0)
        val cx = c * cosBeta
        val cy = c * (cosAlpha - cosBeta * cosGamma) / sinGamma
        val cz = c * sqrt(volumeFactorSquared) / sinGamma
        return Mat3(av, bv, Vec3(cx, cy, cz))
    }

    val volume: Double get() = abs(matrix.determinant())

    fun toCartesian(fractional: FractionalCoordinate): CartesianCoordinate =
        CartesianCoordinate.fromVec3(matrix * fractional.toVec3())

    fun toFractional(cartesian: CartesianCoordinate): FractionalCoordinate =
        FractionalCoordinate.fromVec3(matrix.inverse() * cartesian.toVec3())

    companion object {
        private const val MIN_CELL_VOLUME = 1e-12

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
