package com.krystals.renderer.filament

import com.krystals.renderer.core.style.DepthCueing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `light direction follows azimuth elevation convention`() {
        val direction = viewSpaceLightDirection(
            azimuthDegrees = 35f,
            elevationDegrees = 60f,
        )

        val phi = 60.0 / 180.0 * PI
        val theta = 35.0 / 180.0 * PI
        val cosPhi = cos(phi)
        assertEquals((cosPhi * cos(theta)), direction.x, 1e-9)
        assertEquals((cosPhi * sin(theta)), direction.y, 1e-9)
        assertEquals(-sin(phi), direction.z, 1e-9)
    }

    @Test
    fun `default appearance light direction has negative z`() {
        val appearance = com.krystals.renderer.core.style.ViewerAppearance()
        val direction = viewSpaceLightDirection(
            appearance.lightAzimuth,
            appearance.lightElevation,
        )
        // 默认方位 150°、高度 45°:光从屏幕左上偏后方向来,负 Z 表示向屏幕内。
        assertEquals(-1.0, direction.z / kotlin.math.abs(direction.z), 1e-9)
        assertEquals(-1.0, direction.x / kotlin.math.abs(direction.x), 1e-9)
    }

    @Test
    fun `brighter light raises ambient so atoms brighten instead of darkening`() {
        // v0.8.14 regression: the old formula (0.62 - 0.32*intensity) inverted the
        // brightness slider — raising intensity lowered ambient and dimmed atoms.
        val low = diffuseAmbient(0.2f)
        val high = diffuseAmbient(0.8f)
        assertTrue(high > low, "ambient must rise with intensity: $low -> $high")
        assertEquals(0.47f, diffuseAmbient(0.4f), 0.001f) // default intensity
    }

    @Test
    fun `diffusion slider drives specular width without dead lower clamp`() {
        // v0.8.14 regression: shininess was clamped at 4.0, so moving the diffusion
        // slider barely changed the highlight. Shininess must respond across the range.
        val tight = specularShininess(0.35f)  // diffusion 0   -> radius 0.35
        val wide = specularShininess(1.5f)    // diffusion 1   -> radius 1.5
        assertTrue(tight > wide, "smaller radius must give tighter highlight: $tight vs $wide")
        assertTrue(wide < 4.0f, "wide highlight must not be clamped at 4.0, got $wide")
        assertEquals(2.14f, specularShininess(0.925f), 0.01f) // default diffusion 0.5
    }
}
