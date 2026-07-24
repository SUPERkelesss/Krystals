package com.krystals.renderer.core.scene

import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.rotY
import com.krystals.renderer.core.camera.Camera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DepthRangeTest {

    @Test
    fun nullBoundsReturnsNull() {
        val bounds: SceneBounds? = null
        assertNull(bounds.toCameraDepthRange(Camera()))
    }

    @Test
    fun identityCameraMapsAabbCornersToPlusMinusThree() {
        val bounds = SceneBounds(Vec3(-1.0, -2.0, -3.0), Vec3(1.0, 2.0, 3.0))
        val range = bounds.toCameraDepthRange(Camera(rotation = Mat3.IDENTITY))!!
        assertEquals(3.0, range.near, 1e-9)
        assertEquals(-3.0, range.far, 1e-9)
    }

    @Test
    fun cameraTargetOffsetsCenter() {
        val bounds = SceneBounds(Vec3(-1.0, -1.0, -1.0), Vec3(1.0, 1.0, 1.0))
        val target = Vec3(0.0, 0.0, 2.0)
        val range = bounds.toCameraDepthRange(Camera(rotation = Mat3.IDENTITY, target = target))!!
        // Corners at z = +/-1, target at z = 2 => view-space z = corner - target
        assertEquals(-1.0, range.near, 1e-9)
        assertEquals(-3.0, range.far, 1e-9)
    }

    @Test
    fun rotatedCameraUsesMaxAndMinViewZ() {
        val bounds = SceneBounds(Vec3(-1.0, -1.0, -1.0), Vec3(1.0, 1.0, 1.0))
        // Rotate 90 degrees around Y so the world +X axis points toward camera -Z.
        val rotation = rotY(90.0)
        val range = bounds.toCameraDepthRange(Camera(rotation = rotation))!!
        assertNotNull(range)
        // After this rotation the extent along view Z is still [-1, 1].
        assertEquals(1.0, range.near, 1e-9)
        assertEquals(-1.0, range.far, 1e-9)
    }
}
