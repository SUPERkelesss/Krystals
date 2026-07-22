package com.krystals.renderer.core.primitive

import com.krystals.crystal.core.model.AtomImage
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.RenderObject

data class AtomInstance(
    override val id: String,
    val atom: AtomImage,
    val radius: Double,
    val material: Material,
    override val visible: Boolean = true,
) : RenderObject {
    init {
        require(radius > 0.0) { "atom radius must be positive" }
    }
}
