package com.krystals.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
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
    fun wrapped() = Vec3(wrap01(x), wrap01(y), wrap01(z))
    fun almostEquals(other: Vec3, epsilon: Double = 1e-6): Boolean {
        val d = this - other
        return abs(d.x - d.x.roundToNearestInteger()) < epsilon &&
            abs(d.y - d.y.roundToNearestInteger()) < epsilon &&
            abs(d.z - d.z.roundToNearestInteger()) < epsilon
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun wrap01(value: Double): Double {
            val wrapped = value - floor(value)
            return if (wrapped >= 1.0 - 1e-12 || abs(wrapped) < 1e-12) 0.0 else wrapped
        }
    }
}

private fun Double.roundToNearestInteger() = kotlin.math.round(this)

data class Int3(val x: Int, val y: Int, val z: Int)

/** Matrix stored by columns, matching crystallographic lattice-vector notation. */
data class Mat3(val a: Vec3, val b: Vec3, val c: Vec3) {
    operator fun times(v: Vec3) = a * v.x + b * v.y + c * v.z
    operator fun times(other: Mat3) = Mat3(this * other.a, this * other.b, this * other.c)
    fun determinant() = a.dot(b.cross(c))
    fun inverse(): Mat3 {
        val det = determinant()
        require(abs(det) > 1e-12) { "Matrix is singular" }
        val r0 = b.cross(c) / det
        val r1 = c.cross(a) / det
        val r2 = a.cross(b) / det
        return Mat3(
            Vec3(r0.x, r1.x, r2.x),
            Vec3(r0.y, r1.y, r2.y),
            Vec3(r0.z, r1.z, r2.z),
        )
    }

    companion object {
        val IDENTITY = Mat3(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        fun fromRows(rows: List<List<Int>>) = Mat3(
            Vec3(rows[0][0].toDouble(), rows[1][0].toDouble(), rows[2][0].toDouble()),
            Vec3(rows[0][1].toDouble(), rows[1][1].toDouble(), rows[2][1].toDouble()),
            Vec3(rows[0][2].toDouble(), rows[1][2].toDouble(), rows[2][2].toDouble()),
        )
    }

    /** Transpose (rows <-> columns). For a rotation matrix this is also its inverse. */
    fun transposed() = Mat3(
        Vec3(a.x, b.x, c.x),
        Vec3(a.y, b.y, c.y),
        Vec3(a.z, b.z, c.z),
    )

    /**
     * Re-orthonormalize via Gram-Schmidt. Accumulated left-multiplied rotation matrices drift out
     * of orthogonality over many drag increments; this keeps the columns an orthonormal basis.
     */
    fun orthonormalized(): Mat3 {
        val c0 = a.normalized()
        val c1 = (b - c0 * b.dot(c0)).normalized()
        val c2 = c0.cross(c1)
        return Mat3(c0, c1, c2)
    }
}

/** Rotation matrix for `R_x(theta)`: rotation about the world X axis by [degrees]. */
fun rotX(degrees: Double): Mat3 {
    val t = degrees / 180.0 * PI
    val c = cos(t)
    val s = sin(t)
    // R_x acts on (y, z): y' = y*cos - z*sin, z' = y*sin + z*cos. Stored by columns.
    return Mat3(
        Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, c, s),
        Vec3(0.0, -s, c),
    )
}

/** Rotation matrix for `R_y(theta)`: rotation about the world Y axis by [degrees]. */
fun rotY(degrees: Double): Mat3 {
    val t = degrees / 180.0 * PI
    val c = cos(t)
    val s = sin(t)
    // R_y acts on (x, z): x' = x*cos + z*sin, z' = -x*sin + z*cos. Stored by columns.
    return Mat3(
        Vec3(c, 0.0, -s),
        Vec3(0.0, 1.0, 0.0),
        Vec3(s, 0.0, c),
    )
}

/**
 * Euler rotation `R = R_y(yaw) * R_x(pitch)` (fixed-axis, yaw then pitch application order).
 * Bit-for-bit equivalent to the legacy `rotate(v, yaw, pitch)` so the start view and the align
 * results are unchanged after the matrix migration.
 */
fun eulerYX(yawDegrees: Double, pitchDegrees: Double): Mat3 = rotY(yawDegrees) * rotX(pitchDegrees)

data class UnitCell(
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
    fun toCartesian(fractional: Vec3) = matrix * fractional
    fun toFractional(cartesian: Vec3) = matrix.inverse() * cartesian

    companion object {
        val DEFAULT = UnitCell(1.0, 1.0, 1.0, 90.0, 90.0, 90.0)
        fun fromMatrix(matrix: Mat3): UnitCell {
            fun angle(u: Vec3, v: Vec3): Double {
                val ratio = (u.dot(v) / (u.length() * v.length())).coerceIn(-1.0, 1.0)
                return acos(ratio) * 180.0 / PI
            }
            return UnitCell(
                matrix.a.length(), matrix.b.length(), matrix.c.length(),
                angle(matrix.b, matrix.c), angle(matrix.a, matrix.c), angle(matrix.a, matrix.b),
            )
        }
    }
}

fun distance(a: Vec3, b: Vec3) = (a - b).length()

fun angleDegrees(a: Vec3, b: Vec3, c: Vec3): Double {
    val ba = (a - b).normalized()
    val bc = (c - b).normalized()
    return acos(ba.dot(bc).coerceIn(-1.0, 1.0)) * 180.0 / PI
}

fun dihedralDegrees(a: Vec3, b: Vec3, c: Vec3, d: Vec3): Double {
    val b0 = b - a
    val b1 = c - b
    val b2 = d - c
    val n1 = b0.cross(b1).normalized()
    val n2 = b1.cross(b2).normalized()
    val m1 = n1.cross(b1.normalized())
    return kotlin.math.atan2(m1.dot(n2), n1.dot(n2)) * 180.0 / PI
}
