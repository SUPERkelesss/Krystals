package com.krystals.renderer.core.style

import kotlin.math.pow

/**
 * Decoded background color shared by both rendering backends.
 *
 * - [srgbArgb] is the original packed sRGB value for Canvas/Compose backends.
 * - [linearRgb] is the same color converted to linear-light RGB for GPU backends
 *   (Filament's clear color expects linear values).
 */
data class BackgroundColor(
    val srgbArgb: Long,
    val linearRgb: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackgroundColor) return false
        return srgbArgb == other.srgbArgb && linearRgb.contentEquals(other.linearRgb)
    }

    override fun hashCode(): Int {
        var result = srgbArgb.hashCode()
        result = 31 * result + linearRgb.contentHashCode()
        return result
    }
}

/**
 * Decodes a packed ARGB color into sRGB and linear-light components.
 *
 * The alpha channel is preserved only in [srgbArgb]; callers that need linear alpha
 * can extract it from the top 8 bits of the original value.
 */
fun backgroundColor(argb: Long): BackgroundColor {
    val r = ((argb ushr 16) and 0xFFL) / 255.0
    val g = ((argb ushr 8) and 0xFFL) / 255.0
    val b = (argb and 0xFFL) / 255.0
    return BackgroundColor(
        srgbArgb = argb,
        linearRgb = floatArrayOf(
            srgbToLinear(r).toFloat(),
            srgbToLinear(g).toFloat(),
            srgbToLinear(b).toFloat(),
        ),
    )
}

private fun srgbToLinear(srgb: Double): Double {
    return if (srgb <= 0.04045) {
        srgb / 12.92
    } else {
        ((srgb + 0.055) / 1.055).pow(2.4)
    }
}
