package com.krystals.interaction.measure

import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.angleDegrees
import com.krystals.crystal.core.math.dihedralDegrees
import com.krystals.crystal.core.math.distance

enum class MeasurementMode { NONE, LENGTH, ANGLE, DIHEDRAL }

data class MeasurementSelection(val atomIds: List<Long>, val mode: MeasurementMode)

data class DihedralPlaneGeometry(val vertices: List<Vec3>, val normal: Vec3)

object DistanceTool {
    fun calculate(a: Vec3, b: Vec3): Double = distance(a, b)
}

object AngleTool {
    fun calculate(a: Vec3, b: Vec3, c: Vec3): Double = angleDegrees(a, b, c)
}

object DihedralTool {
    fun calculate(a: Vec3, b: Vec3, c: Vec3, d: Vec3): Double = dihedralDegrees(a, b, c, d)

    fun planes(a: Vec3, b: Vec3, c: Vec3, d: Vec3): List<DihedralPlaneGeometry> {
        val bc = c - b
        val length = bc.length()
        if (length < 1e-9) return emptyList()
        val axis = bc / length
        return listOfNotNull(plane(b, c, a - b, (a - b).length(), axis), plane(b, c, d - c, (d - c).length(), axis))
    }

    private fun plane(b: Vec3, c: Vec3, toward: Vec3, width: Double, axis: Vec3): DihedralPlaneGeometry? {
        if (width < 1e-9) return null
        val perpendicular = toward - axis * toward.dot(axis)
        val perpendicularLength = perpendicular.length()
        if (perpendicularLength < 1e-9) return null
        val widthVector = perpendicular / perpendicularLength * width
        return DihedralPlaneGeometry(listOf(b, c, c + widthVector, b + widthVector), (c - b).cross(widthVector).normalized())
    }
}
