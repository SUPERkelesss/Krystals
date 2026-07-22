package com.krystals.renderer.core.material

data class Material(
    val argb: Long,
    val opacity: Double = 1.0,
    val reflective: Boolean = true,
    val doubleSided: Boolean = false,
) {
    init {
        require(opacity in 0.0..1.0) { "opacity must be between 0 and 1" }
    }
}
