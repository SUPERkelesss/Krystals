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
        // v0.8.26: -sin(phi) — worldLightTravelDirection negates this for the lit
        // travel direction, so surface-to-light keeps the v0.8.19 sign.
        assertEquals(-sin(phi), direction.z, 1e-9)
    }

    @Test
    fun `world light travel direction is opposite of surface-to-light`() {
        // v0.8.19 regression: viewSpaceLightDirection returns surface-to-light (the
        // direction from the surface toward the light, used by the NdotL shader term),
        // but Filament's LightManager.setDirection wants the light TRAVEL direction
        // (from the light toward the scene). The two differ by a sign. At identity
        // camera the world travel direction must be the negated view direction.
        val travel = worldLightTravelDirection(
            azimuthDegrees = 35f,
            elevationDegrees = 60f,
            cameraRotation = com.krystals.crystal.core.math.Mat3.IDENTITY,
        )
        val surfaceToLight = viewSpaceLightDirection(35f, 60f)
        assertEquals(-surfaceToLight.x, travel.x, 1e-9)
        assertEquals(-surfaceToLight.y, travel.y, 1e-9)
        assertEquals(-surfaceToLight.z, travel.z, 1e-9)
    }

    @Test
    fun `world light travel direction is camera anchored`() {
        // The light is anchored to the camera: rotating the camera must rotate the
        // world-space travel direction by the inverse camera rotation, so the
        // light-to-camera relationship stays constant.
        val rot = com.krystals.crystal.core.math.rotY(90.0)
        val travel = worldLightTravelDirection(
            azimuthDegrees = 0f,
            elevationDegrees = 90f,
            cameraRotation = rot,
        )
        // surface-to-light at (az=0, el=90) = (0,0,-1) (v0.8.26 sign restored); travel
        // = (0,0,+1) in view space. World = rotY(90).transposed() * (0,0,1).
        // rotY(90) maps world->view, so view->world is rotY(-90): the c column
        // (sin(-90),0,cos(-90)) = (-1,0,0) applied to (0,0,1) gives (-1,0,0).
        val expected = com.krystals.crystal.core.math.rotY(-90.0) * com.krystals.crystal.core.math.Vec3(0.0, 0.0, 1.0)
        assertEquals(expected.x, travel.x, 1e-9)
        assertEquals(expected.y, travel.y, 1e-9)
        assertEquals(expected.z, travel.z, 1e-9)
    }

    @Test
    fun `default appearance light direction has negative z`() {
        val appearance = com.krystals.renderer.core.style.ViewerAppearance()
        val direction = viewSpaceLightDirection(
            appearance.lightAzimuth,
            appearance.lightElevation,
        )
        // 默认方位 150°、高度 45°:光从屏幕左上偏后方向来,负 Z 表示向屏幕内(v0.8.26)。
        assertEquals(-1.0, direction.z / kotlin.math.abs(direction.z), 1e-9)
        assertEquals(-1.0, direction.x / kotlin.math.abs(direction.x), 1e-9)
    }

    @Test
    fun `brighter light raises ambient so atoms brighten instead of darkening`() {
        // v0.8.14 regression: the old formula (0.62 - 0.32*intensity) inverted the
        // brightness slider — raising intensity lowered ambient and dimmed atoms.
        // v0.8.17: ambient is a faint base (0.12 + 0.10i) under the mirror highlight;
        // the positive correlation is preserved so the brightness slider still works.
        val low = diffuseAmbient(0.2f)
        val high = diffuseAmbient(0.8f)
        assertTrue(high > low, "ambient must rise with intensity: $low -> $high")
        assertEquals(0.16f, diffuseAmbient(0.4f), 0.001f) // default intensity: faint base
    }

    @Test
    fun `diffusion slider drives specular width without dead lower clamp`() {
        // v0.8.14 regression: shininess was clamped at 4.0, so moving the diffusion
        // slider barely changed the highlight. Shininess must respond across the range.
        // v0.8.16: base raised to 4.0/(r+0.01) so the mirror highlight stays tight.
        val tight = specularShininess(0.35f)  // diffusion 0   -> radius 0.35
        val wide = specularShininess(1.5f)    // diffusion 1   -> radius 1.5
        assertTrue(tight > wide, "smaller radius must give tighter highlight: $tight vs $wide")
        assertTrue(wide in 3.0f..5.0f, "wide highlight must stay below old 4.0 clamp, got $wide")
        assertEquals(4.28f, specularShininess(0.925f), 0.01f) // default diffusion 0.5
    }

    @Test
    fun `glass atom uses mirror specular only without frosted diffuse`() {
        // v0.8.17: the frosted (diffuse) share is fully removed — atoms are lit by the
        // mirror highlight alone on a faint ambient base, so they never wash out.
        assertEquals(0.0f, atomDiffuseWeight(), 0.001f, "frosted diffuse must be gone")
        assertEquals(1.0f, atomSpecularBlend(), 0.001f) // highlight blends to full white
    }

    @Test
    fun `world light intensity stays below overexposure threshold`() {
        // v0.8.21: with post-processing disabled (no tone mapping), 150k lux clipped the
        // PBR highlight to pure white and its arc edge read as a "bright edge" on atoms.
        // The mapping must stay strong but non-clipping.
        assertEquals(12_000f, worldLightIntensityLux(0.4f), 1f)   // default intensity
        assertEquals(30_000f, worldLightIntensityLux(1.0f), 1f)   // slider max
        assertEquals(1f, worldLightIntensityLux(0f), 1f)           // floor
        assertTrue(worldLightIntensityLux(1.0f) < 100_000f, "must not approach noon-sun levels")
    }

    @Test
    fun `atom pbr configuration matches spec`() {
        // v0.8.24: atoms are lit PBR with a 0.4 clear coat per user spec.
        assertEquals(0.0f, AtomPbr.METALLIC, 0.001f)
        assertEquals(0.32f, AtomPbr.ROUGHNESS, 0.001f)
        assertEquals(0.45f, AtomPbr.REFLECTANCE, 0.001f)
        assertEquals(0.4f, AtomPbr.CLEAR_COAT, 0.001f)
        // v0.8.25: clear-coat roughness 0.5 (softer coat highlight).
        assertEquals(0.5f, AtomPbr.CLEAR_COAT_ROUGHNESS, 0.001f)
        assertEquals(0.95f, AtomPbr.SATURATION_FACTOR, 0.001f)
    }

    @Test
    fun `atom base color keeps five percent desaturation`() {
        // CPK base color stays desaturated by 5% even with the lit PBR material.
        assertEquals(0xFF808080L, desaturateArgb(0xFF808080L, AtomPbr.SATURATION_FACTOR))
        val out = desaturateArgb(0xFFFF0000L, AtomPbr.SATURATION_FACTOR)
        assertTrue((out ushr 16 and 0xFF) > 200, "red must stay dominant")
    }

    @Test
    fun `desaturate keeps gray and identity unchanged`() {
        // Gray has zero saturation: factor must not change it.
        assertEquals(0xFF808080L, desaturateArgb(0xFF808080L, 0.95f))
        // Saturation factor 1.0 is identity.
        assertEquals(0xFFFF0000L, desaturateArgb(0xFFFF0000L, 1.0f))
        assertEquals(0xFF00A5C8L, desaturateArgb(0xFF00A5C8L, 1.0f))
    }

    @Test
    fun `desaturate reduces chroma while keeping hue and lightness`() {
        // Pure red, 5% desaturation: green and blue channels rise from 0 toward gray,
        // red stays dominant (hue preserved), overall lightness unchanged.
        val out = desaturateArgb(0xFFFF0000L, 0.95f)
        val r = (out ushr 16 and 0xFF).toInt()
        val g = (out ushr 8 and 0xFF).toInt()
        val b = (out and 0xFF).toInt()
        assertTrue(r > 200, "red must stay dominant, got $r")
        assertTrue(g in 1..16, "green must rise slightly toward gray, got $g")
        assertTrue(b in 1..16, "blue must rise slightly toward gray, got $b")
        assertTrue(g > 0 && b > 0, "fully saturated red must gain a little chroma of gray")
    }
}
