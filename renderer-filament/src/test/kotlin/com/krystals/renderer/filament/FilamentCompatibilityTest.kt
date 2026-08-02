package com.krystals.renderer.filament

import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.rotY
import com.krystals.renderer.core.style.DepthCueing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

    @Test
    fun `identity camera keeps original azimuth elevation direction`() {
        val direction = viewSpaceLightDirection(
            azimuthDegrees = 35f,
            elevationDegrees = 60f,
            cameraRotation = Mat3.IDENTITY,
        )

        val phi = 60.0 / 180.0 * PI
        val theta = 35.0 / 180.0 * PI
        val cosPhi = cos(phi)
        assertEquals((cosPhi * cos(theta)), direction.x, 1e-9)
        assertEquals((cosPhi * sin(theta)), direction.y, 1e-9)
        assertEquals(-sin(phi), direction.z, 1e-9)
    }

    @Test
    fun `camera rotation transforms world-fixed light into view space`() {
        // 光从正前方(世界 -Z)照来,相机绕 Y 轴转 90°:视空间方向应变为 -X。
        val direction = viewSpaceLightDirection(
            azimuthDegrees = 0f,
            elevationDegrees = 90f,
            cameraRotation = rotY(90.0),
        )

        assertEquals(-1.0, direction.x, 1e-9)
        assertEquals(0.0, direction.y, 1e-9)
        assertEquals(0.0, direction.z, 1e-9)
    }

    @Test
    fun `default appearance light direction has negative z at identity camera`() {
        val appearance = com.krystals.renderer.core.style.ViewerAppearance()
        val direction = viewSpaceLightDirection(
            appearance.lightAzimuth,
            appearance.lightElevation,
            Mat3.IDENTITY,
        )
        // 默认方位 150°、高度 45°:光从屏幕左上偏后方向来,负 Z 表示向屏幕内。
        assertEquals(-1.0, direction.z / kotlin.math.abs(direction.z), 1e-9)
        assertEquals(-1.0, direction.x / kotlin.math.abs(direction.x), 1e-9)
    }
}
