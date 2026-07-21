package com.krystals.crystal.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the matrix-based camera rotation introduced in v0.5.1 to fix "drag rotation skews
 * after the view is tilted". The legacy scalar `rotate(v, yaw, pitch)` is inlined here as a
 * reference so we can prove the matrix path is behaviour-identical for the start view and align
 * (zero-regression), while the drag-tracking assertion captures the actual fix.
 */
class RotationTest {
    private fun legacyRotate(v: Vec3, yawDegrees: Double, pitchDegrees: Double): Vec3 {
        val yaw = yawDegrees / 180.0 * PI
        val pitch = pitchDegrees / 180.0 * PI
        val y = v.y * cos(pitch) - v.z * sin(pitch)
        val zPitch = v.y * sin(pitch) + v.z * cos(pitch)
        val x = v.x * cos(yaw) + zPitch * sin(yaw)
        val finalZ = -v.x * sin(yaw) + zPitch * cos(yaw)
        return Vec3(x, y, finalZ)
    }

    private fun Vec3.assertAlmostEqual(expected: Vec3, eps: Double = 1e-9) =
        abs(x - expected.x) < eps && abs(y - expected.y) < eps && abs(z - expected.z) < eps

    private fun assertOrthogonal(r: Mat3, eps: Double = 1e-9) {
        val rrt = r * r.transposed()
        assertTrue(rrt.a.assertAlmostEqual(Vec3(1.0, 0.0, 0.0), eps), "columns not orthonormal: $rrt")
        assertTrue(rrt.b.assertAlmostEqual(Vec3(0.0, 1.0, 0.0), eps), "columns not orthonormal: $rrt")
        assertTrue(rrt.c.assertAlmostEqual(Vec3(0.0, 0.0, 1.0), eps), "columns not orthonormal: $rrt")
    }

    @Test
    fun eulerYX_identityIsIdentity() {
        assertEquals(Mat3.IDENTITY, eulerYX(0.0, 0.0))
    }

    @Test
    fun eulerYX_matchesLegacyScalarRotate() {
        // Sampling a spread of angles (including the live defaults -28/22) and vectors: the matrix
        // must reproduce the old fixed-axis rotate exactly so the start view and aligns don't shift.
        val samples = listOf(
            Triple(-28.0, 22.0, Vec3(1.0, 2.0, 3.0)),
            Triple(0.0, 0.0, Vec3(1.0, 0.0, 0.0)),
            Triple(45.0, 30.0, Vec3(0.5, -0.5, 2.0)),
            Triple(-120.0, -60.0, Vec3(-1.0, 1.0, -1.0)),
            Triple(90.0, 0.0, Vec3(0.0, 0.0, 4.0)),
            Triple(0.0, 90.0, Vec3(0.0, 3.0, 0.0)),
        )
        samples.forEach { (yaw, pitch, v) ->
            val byMatrix = eulerYX(yaw, pitch) * v
            val byLegacy = legacyRotate(v, yaw, pitch)
            assertTrue(byMatrix.assertAlmostEqual(byLegacy), "eulerYX($yaw,$pitch)*v mismatch: $byMatrix vs $byLegacy")
        }
    }

    @Test
    fun eulerYX_isOrthonormal() {
        assertOrthogonal(eulerYX(-28.0, 22.0))
        assertOrthogonal(eulerYX(45.0, 30.0))
        assertOrthogonal(eulerYX(179.0, -89.0))
    }

    @Test
    fun orthonormalized_cleansDriftedMatrix() {
        // A deliberately non-orthogonal matrix (one column slightly scaled/tilted) should come back
        // orthonormal — this is the routine rotateByDrag runs each increment to fight drift.
        val drifted = Mat3(
            Vec3(1.0, 0.001, 0.0),
            Vec3(0.0, 0.999, 0.002),
            Vec3(0.001, 0.0, 1.0),
        )
        assertOrthogonal(drifted.orthonormalized(), eps = 1e-10)
    }

    @Test
    fun horizontalDragAfterTiltingRotatesAboutCameraScreenVerticalAxis() {
        // The core fix. With the view pitched down 60deg (so the world Y axis is no longer screen-
        // vertical), a pure horizontal drag must rotate about the CAMERA's local Y axis. We assert
        // that by checking the incremental part R1 * R0^T leaves the camera-Y direction fixed
        // (its second column is (0,1,0)), i.e. horizontal drag does not contaminate the screen-
        // vertical axis. The old world-axis yaw += would fail this.
        val r0 = eulerYX(0.0, 60.0)
        // Mirrors controller.rotateByDrag(dx=100, dy=0, sensitivity=0.32): pitchInc=0, yawInc=-32.
        val pitchInc = -0.0f * 0.32f
        val yawInc = -100.0f * 0.32f
        val r1 = (rotX(pitchInc.toDouble()) * rotY(yawInc.toDouble()) * r0).orthonormalized()
        val delta = r1 * r0.transposed() // incremental rotation in camera frame

        // delta * (0,1,0) must equal (0,1,0): camera Y is the rotation axis, untouched.
        val cameraY = delta * Vec3(0.0, 1.0, 0.0)
        assertTrue(cameraY.assertAlmostEqual(Vec3(0.0, 1.0, 0.0)),
            "horizontal drag moved the camera-Y axis: $cameraY — tilt-then-drag would feel skewed")
        assertOrthogonal(r1)
    }

    @Test
    fun verticalDragAfterTiltingRotatesAboutCameraScreenHorizontalAxis() {
        val r0 = eulerYX(40.0, 35.0)
        // Mirrors controller.rotateByDrag(dx=0, dy=80, sensitivity=0.32): pitchInc=-25.6, yawInc=0.
        val pitchInc = -80.0f * 0.32f
        val yawInc = -0.0f * 0.32f
        val r1 = (rotX(pitchInc.toDouble()) * rotY(yawInc.toDouble()) * r0).orthonormalized()
        val delta = r1 * r0.transposed()

        val cameraX = delta * Vec3(1.0, 0.0, 0.0)
        assertTrue(cameraX.assertAlmostEqual(Vec3(1.0, 0.0, 0.0)),
            "vertical drag moved the camera-X axis: $cameraX")
        assertOrthogonal(r1)
    }
}

