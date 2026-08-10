package com.krystals.renderer.core.style

import com.krystals.renderer.core.material.Material

/**
 * Per v0.7.0: fixed appearance for hydrogen-bond cylinders/lines.
 *
 * The scene builder emits hydrogen bonds as separate [com.krystals.renderer.core.primitive.HbondInstance]
 * objects (the hbond channel of [com.krystals.crystal.analysis.bonding.BondNetwork]); it overrides the
 * normal bond radius and material whenever a hydrogen bond is rendered. Both ends share the same
 * material so backends render H-bonds as a single segment (no split-cylinder).
 */
object HbondPattern {
    /** Cylinder radius in Å. */
    // Per v0.7.0: further reduced from 0.1 to 0.05 AA (user request).
    const val RADIUS: Double = 0.05

    /** Translucent gray (front-end backends may restyle, e.g. legacy uses dotted lines). */
    val COLOR: Long = 0xFF808080

    /** Opacity for the translucent cylinder. */
    const val OPACITY: Float = 0.2f

    /** Single material shared by both start and end of every H-bond.
     *  Per v0.7.0: radius/opacity are overridable via ViewerAppearance. */
    fun material(opacity: Float = OPACITY): Material = Material(argb = COLOR, opacity = opacity.toDouble(), reflective = false)
}
