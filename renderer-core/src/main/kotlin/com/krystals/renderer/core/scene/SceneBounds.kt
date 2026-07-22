package com.krystals.renderer.core.scene

import com.krystals.crystal.core.math.Vec3
import kotlin.math.max

data class SceneBounds(
    val min: Vec3,
    val max: Vec3,
) {
    val center: Vec3 = (min + max) * 0.5
    val radius: Double = max(1.0, max(max.x - min.x, max(max.y - min.y, max.z - min.z)) / 1.44)
    val corners: List<Vec3> = buildList(8) {
        for (x in listOf(min.x, max.x)) {
            for (y in listOf(min.y, max.y)) {
                for (z in listOf(min.z, max.z)) add(Vec3(x, y, z))
            }
        }
    }
}

fun RenderScene.visibleBounds(): SceneBounds? {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY
    var found = false
    atoms.forEach { atom ->
        if (!atom.visible) return@forEach
        val point = atom.atom.cartesianCoordinate
        found = true
        if (point.x < minX) minX = point.x
        if (point.y < minY) minY = point.y
        if (point.z < minZ) minZ = point.z
        if (point.x > maxX) maxX = point.x
        if (point.y > maxY) maxY = point.y
        if (point.z > maxZ) maxZ = point.z
    }
    return if (found) SceneBounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ)) else null
}
