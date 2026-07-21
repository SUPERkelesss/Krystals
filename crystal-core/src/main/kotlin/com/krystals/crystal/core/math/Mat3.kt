package com.krystals.crystal.core.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

    fun transposed() = Mat3(
        Vec3(a.x, b.x, c.x),
        Vec3(a.y, b.y, c.y),
        Vec3(a.z, b.z, c.z),
    )

    fun orthonormalized(): Mat3 {
        val c0 = a.normalized()
        val c1 = (b - c0 * b.dot(c0)).normalized()
        val c2 = c0.cross(c1)
        return Mat3(c0, c1, c2)
    }

    companion object {
        val IDENTITY = Mat3(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))

        fun fromRows(rows: List<List<Int>>) = Mat3(
            Vec3(rows[0][0].toDouble(), rows[1][0].toDouble(), rows[2][0].toDouble()),
            Vec3(rows[0][1].toDouble(), rows[1][1].toDouble(), rows[2][1].toDouble()),
            Vec3(rows[0][2].toDouble(), rows[1][2].toDouble(), rows[2][2].toDouble()),
        )
    }
}

fun rotX(degrees: Double): Mat3 {
    val t = degrees / 180.0 * PI
    val c = cos(t)
    val s = sin(t)
    return Mat3(
        Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, c, s),
        Vec3(0.0, -s, c),
    )
}

fun rotY(degrees: Double): Mat3 {
    val t = degrees / 180.0 * PI
    val c = cos(t)
    val s = sin(t)
    return Mat3(
        Vec3(c, 0.0, -s),
        Vec3(0.0, 1.0, 0.0),
        Vec3(s, 0.0, c),
    )
}

fun eulerYX(yawDegrees: Double, pitchDegrees: Double): Mat3 = rotY(yawDegrees) * rotX(pitchDegrees)
