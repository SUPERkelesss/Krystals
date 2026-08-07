package com.krystals.renderer.core.primitive

import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.HydrogenBond
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.RenderObject

/**
 * 氢键渲染实例:单段圆柱(两端同一材质,无两半拆分)。
 *
 * 与 [BondInstance] 分离 —— 氢键不是共价键,样式独立(细、半透明灰,
 * 见 HbondPattern)。消费端(filament)用独立的 HBOND 几何类型实例化。
 */
data class HbondInstance(
    override val id: String,
    val hbond: HydrogenBond,
    val start: Vec3,
    val end: Vec3,
    val radius: Double,
    val material: Material,
    override val visible: Boolean = true,
) : RenderObject {
    init {
        require(radius > 0.0) { "hbond radius must be positive" }
    }
}
