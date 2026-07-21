package com.krystals.crystal.core.math

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2

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
    return atan2(m1.dot(n2), n1.dot(n2)) * 180.0 / PI
}
