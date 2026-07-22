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
internal const val LEGACY_NORMALIZED_DEPTH_EXTENT = 3.0

internal data class LegacyReflectionParameters(
    val highlightAlpha: Float,
    val highlightMiddleAlpha: Float,
    val radialRadiusMultiplier: Float,
    val bondHighlightFactor: Float,
    val bondHighlightBand: Float,
    val polyhedronSpecularFactor: Float,
)

internal data class LegacyDepthRange(val min: Double, val max: Double) {
    init {
        require(max >= min) { "depth range maximum must not be smaller than minimum" }
    }

    private val span: Double get() = max - min

    fun normalizedDepth(depth: Double): Float? {
        if (span <= 1e-9) return null
        val center = (min + max) / 2.0
        return ((depth - center) / (span / 2.0) * LEGACY_NORMALIZED_DEPTH_EXTENT).toFloat()
    }
}

/** Canvas draws later items over earlier ones, so negative/far depth must precede positive/near depth. */
internal fun <T> Iterable<T>.legacyBackToFront(
    depthOf: (T) -> Double,
    layerOf: (T) -> Int = { 0 },
): List<T> = sortedWith(compareBy<T> { depthOf(it) }.thenBy { layerOf(it) })

/** Legacy camera space uses +Z toward the viewer and -Z away from the viewer. */
internal fun legacyCameraDepth(cameraSpace: Vec3): Double = cameraSpace.z

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
                    add(legacyCameraDepth(rotation * (cartesian - center)))
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
    val start = far
    val end = near
    if (start >= end) return if (depth < end) 1f else 0f
    if (depth <= start) return 1f
    if (depth >= end) return 0f
    return ((end - depth) / (end - start)).coerceIn(0f, 1f)
}

/** Mixes RGB toward the background without changing the source alpha. */
internal fun legacyBlendArgbPreservingAlpha(source: Int, background: Int, mix: Float): Int {
    val amount = mix.coerceIn(0f, 1f)
    val inverse = 1f - amount
    fun channel(shift: Int): Int {
        val sourceChannel = source ushr shift and 0xFF
        val backgroundChannel = background ushr shift and 0xFF
        return (sourceChannel * inverse + backgroundChannel * amount).toInt().coerceIn(0, 255)
    }
    return (source and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun legacyReflectionParameters(intensity: Float, diffusion: Float): LegacyReflectionParameters {
    val normalizedIntensity = intensity.coerceIn(0f, 1f)
    val normalizedDiffusion = diffusion.coerceIn(0f, 1f)
    return LegacyReflectionParameters(
        highlightAlpha = normalizedIntensity,
        highlightMiddleAlpha = normalizedIntensity * 0.4f,
        radialRadiusMultiplier = 0.35f + 1.15f * normalizedDiffusion,
        bondHighlightFactor = 0.75f * normalizedIntensity,
        bondHighlightBand = 0.06f + 0.26f * normalizedDiffusion,
        polyhedronSpecularFactor = 0.85f * normalizedIntensity,
    )
}
