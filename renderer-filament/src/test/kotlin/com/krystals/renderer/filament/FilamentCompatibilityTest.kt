package com.krystals.renderer.filament

import com.krystals.renderer.core.style.DepthCueing
import kotlin.test.Test
import kotlin.test.assertEquals

class FilamentCompatibilityTest {
    @Test
    fun `depth cue thresholds use legacy visible depth scale`() {
        val (near, far) = depthCueViewRange(
            cue = DepthCueing(enabled = true, near = 0.5f, far = -4.5f),
            visibleNear = 10f,
            visibleFar = 4f,
        )

        assertEquals(7.5f, near, 0.0001f)
        assertEquals(2.5f, far, 0.0001f)
    }

    @Test
    fun `visible extrema map to plus and minus three`() {
        val (near, far) = depthCueViewRange(
            cue = DepthCueing(enabled = true, near = 3f, far = -3f),
            visibleNear = 9f,
            visibleFar = 3f,
        )

        assertEquals(9f, near, 0.0001f)
        assertEquals(3f, far, 0.0001f)
    }
}
