package com.krystals.renderer.legacy

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val LEGACY_HIGHLIGHT_EDGE_FRACTION = 0.95

internal data class LegacyDepthRange(val min: Double, val max: Double) {
    init {
        require(max >= min) { "depth range maximum must not be smaller than minimum" }
    }

    private val span: Double get() = max - min

    fun normalizedDepth(depth: Double): Float? {
        if (span <= 1e-9) return null
        val center = (min + max) / 2.0
        return ((depth - center) / (span / 2.0) * 5.0).toFloat()
    }
}

/** Screen-space light direction. X points right, Y points down and +Z points at the viewer. */
internal fun legacyLightDirection(azimuthDegrees: Float, elevationDegrees: Float): Vec3 {
    val azimuth = azimuthDegrees / 180.0 * PI
    val elevation = elevationDegrees.coerceIn(0f, 90f) / 180.0 * PI
    return Vec3(
        cos(azimuth) * cos(elevation),
        sin(azimuth) * cos(elevation),
        sin(elevation),
    )
}

/** Highlight displacement from a sphere's center toward its screen-space rim. */
internal fun legacyHighlightOffset(radius: Double, azimuthDegrees: Float, elevationDegrees: Float): Vec3 {
    val light = legacyLightDirection(azimuthDegrees, elevationDegrees)
    val scale = radius * LEGACY_HIGHLIGHT_EDGE_FRACTION
    return Vec3(light.x * scale, light.y * scale, 0.0)
}

/** Camera-space depth bounds of the displayed primary supercell, independent of atom visibility. */
internal fun expandedCellDepthRange(
    lattice: Lattice,
    expansion: Expansion,
    center: Vec3,
    rotation: Mat3,
): LegacyDepthRange? {
    val depths = buildList(8) {
        for (x in listOf(0.0, expansion.x.toDouble())) {
            for (y in listOf(0.0, expansion.y.toDouble())) {
                for (z in listOf(0.0, expansion.z.toDouble())) {
                    val cartesian = lattice.toCartesian(FractionalCoordinate(x, y, z)).toVec3()
                    add((rotation * (cartesian - center)).z)
                }
            }
        }
    }
    val min = depths.minOrNull() ?: return null
    val max = depths.maxOrNull() ?: return null
    return LegacyDepthRange(min, max).takeIf { max - min > 1e-9 }
}

internal fun legacyDepthCueFog(
    depth: Double,
    range: LegacyDepthRange?,
    near: Float,
    far: Float,
): Float {
    val normalized = range?.normalizedDepth(depth) ?: return 0f
    return legacyDepthCueFogValue(normalized, near, far)
}

internal fun legacyDepthCueFogValue(depth: Float, near: Float, far: Float): Float {
    if (near <= far) return if (depth >= near) 0f else 1f
    if (depth >= near) return 0f
    if (depth <= far) return 1f
    return ((near - depth) / (near - far)).coerceIn(0f, 1f)
}
