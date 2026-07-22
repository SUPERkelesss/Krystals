package com.krystals.crystal.renderer

import com.krystals.crystal.core.math.Vec3

data class DihedralPlaneGeometry(val vertices: List<Vec3>, val normal: Vec3)

object DihedralPlaneGeometryBuilder {
    fun build(a: Vec3, b: Vec3, c: Vec3, d: Vec3): List<DihedralPlaneGeometry> {
        val bc = c - b
        val bcLength = bc.length()
        if (bcLength < 1e-9) return emptyList()
        val axis = bc / bcLength
        return listOfNotNull(
            plane(b, c, a - b, (a - b).length(), axis),
            plane(b, c, d - c, (d - c).length(), axis),
        )
    }

    private fun plane(b: Vec3, c: Vec3, toward: Vec3, width: Double, axis: Vec3): DihedralPlaneGeometry? {
        if (width < 1e-9) return null
        val perpendicular = toward - axis * toward.dot(axis)
        val perpendicularLength = perpendicular.length()
        if (perpendicularLength < 1e-9) return null
        val widthVector = perpendicular / perpendicularLength * width
        return DihedralPlaneGeometry(
            listOf(b, c, c + widthVector, b + widthVector),
            (c - b).cross(widthVector).normalized(),
        )
    }
}
