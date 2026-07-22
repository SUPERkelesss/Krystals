package com.krystals.renderer.core.primitive

import com.krystals.crystal.analysis.bonding.Bond
import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.RenderObject

data class BondInstance(
    override val id: String,
    val bond: Bond,
    val start: Vec3,
    val end: Vec3,
    val radius: Double,
    val startMaterial: Material,
    val endMaterial: Material,
    override val visible: Boolean = true,
) : RenderObject {
    init {
        require(radius > 0.0) { "bond radius must be positive" }
    }
}
