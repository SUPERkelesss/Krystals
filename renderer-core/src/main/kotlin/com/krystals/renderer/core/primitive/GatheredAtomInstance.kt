package com.krystals.renderer.core.primitive

import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.GatheredAtom
import com.krystals.renderer.core.scene.RenderObject

/**
 * Per v0.8.2: a single pie-chart sphere rendered in place of multiple atoms whose expanded
 * cartesian positions coincide (substitutional / mixed-occupancy disorder).
 *
 * Member atom [AtomInstance]s are NOT emitted when a group exists — this instance
 * replaces them. Bonds whose BOTH endpoints are in the same group are dropped;
 * bonds with one grouped endpoint are re-anchored at the group centre and deduped.
 */
data class GatheredAtomInstance(
    override val id: String,           // "gathered:<sorted member ids>"
    val gathered: GatheredAtom,        // group data (slices, center, colors, occupancy)
    val radius: Double,                // max member radius
    val remainderMaterial: Material,   // mixedColor at alpha 0.25
    override val visible: Boolean = true,
) : RenderObject
