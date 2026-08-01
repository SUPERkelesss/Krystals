package com.krystals.renderer.core.style

import com.krystals.renderer.core.material.Material

/**
 * Per v0.8.1: fixed appearance for hydrogen-bond cylinders/lines.
 *
 * Scene builders override the normal bond radius and material when
 * [com.krystals.crystal.analysis.bonding.BondRule.isHBond] is true. Both ends
 * share the same material so backends render H-bonds as a single segment
 * (no split-cylinder).
 */
object HbondPattern {
    /** Cylinder radius in Å. */
    const val RADIUS: Double = 0.1

    /** Translucent gray (front-end backends may restyle, e.g. legacy uses dotted lines). */
    val COLOR: Long = 0xFF808080

    /** Opacity for the translucent cylinder. */
    const val OPACITY: Float = 0.2f

    /** Single material shared by both start and end of every H-bond. */
    fun material(): Material = Material(argb = COLOR, opacity = OPACITY.toDouble(), reflective = false)
}
