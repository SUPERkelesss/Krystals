package com.krystals.renderer.core.camera

sealed interface Projection {
    val near: Double
    val far: Double

    data class Orthographic(
        val verticalSpan: Double = 1.0,
        override val near: Double = -1_000.0,
        override val far: Double = 1_000.0,
    ) : Projection {
        init {
            require(verticalSpan > 0.0) { "verticalSpan must be positive" }
            require(near < far) { "near must be less than far" }
        }
    }

    data class Perspective(
        val verticalFieldOfViewDegrees: Double = 45.0,
        override val near: Double = 0.01,
        override val far: Double = 1_000.0,
    ) : Projection {
        init {
            require(verticalFieldOfViewDegrees in 0.0..180.0) { "field of view must be between 0 and 180 degrees" }
            require(near > 0.0 && near < far) { "perspective clipping range is invalid" }
        }
    }
}
