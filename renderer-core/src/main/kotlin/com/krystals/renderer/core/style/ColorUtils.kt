package com.krystals.renderer.core.style

/**
 * Bond (BICOLOR mode) cylinder colors: the site CPK color at 90% saturation.
 * Applied to the start/end materials of two-tone bonds in
 * [com.krystals.renderer.core.builder.CrystalRenderSceneFactory].
 */
const val BOND_SATURATION_FACTOR = 0.90f

/**
 * Desaturates an ARGB color by [factor] (1.0 = identity) in HSL space, preserving hue
 * and lightness. Shared by the renderer backends: atoms use
 * [com.krystals.renderer.filament.AtomPbr.SATURATION_FACTOR] (95%), two-tone bonds use
 * [BOND_SATURATION_FACTOR] (90%).
 */
fun desaturateArgb(argb: Long, factor: Float): Long {
    val alpha = (argb ushr 24 and 0xFF).toInt()
    var r = (argb ushr 16 and 0xFF).toInt() / 255f
    var g = (argb ushr 8 and 0xFF).toInt() / 255f
    var b = (argb and 0xFF).toInt() / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return argb // gray: saturation already 0
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val newSaturation = (saturation * factor).coerceIn(0f, 1f)
    if (newSaturation == 0f) {
        val gray = (lightness * 255f).toInt().coerceIn(0, 255)
        return (alpha.toLong() shl 24) or (gray.toLong() shl 16) or (gray.toLong() shl 8) or gray.toLong()
    }
    val q = if (lightness < 0.5f) lightness * (1f + newSaturation) else lightness + newSaturation - lightness * newSaturation
    val p = 2f * lightness - q
    fun hue2rgb(p: Float, q: Float, t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
    var h = 0f
    when (max) {
        r -> h = ((g - b) / delta + if (g < b) 6f else 0f) / 6f
        g -> h = ((b - r) / delta + 2f) / 6f
        b -> h = ((r - g) / delta + 4f) / 6f
    }
    r = hue2rgb(p, q, h + 1f / 3f)
    g = hue2rgb(p, q, h)
    b = hue2rgb(p, q, h - 1f / 3f)
    fun toByte(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return (alpha.toLong() shl 24) or
        (toByte(r).toLong() shl 16) or
        (toByte(g).toLong() shl 8) or
        toByte(b).toLong()
}
