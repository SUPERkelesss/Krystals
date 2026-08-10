package com.krystals.renderer.filament

import com.google.android.filament.SwapChainFlags
import com.krystals.renderer.core.style.DepthCueing
import com.krystals.renderer.core.style.WorldLight
import com.krystals.renderer.core.style.desaturateArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilamentCompatibilityTest {
    @Test
    fun `rgba readback converts channels in bulk`() {
        val pixels = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
            put(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
            rewind()
        }

        assertEquals(
            listOf(0x44112233, 0xDDAABBCC.toInt()),
            rgbaBytesToArgb(pixels, 2).toList(),
        )
    }

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
        // Filament view space looks down -Z; a camera-side light is surface-to-light +Z.
        assertEquals(sin(phi), direction.z, 1e-9)
    }

    @Test
    fun `sun direction lights camera facing atom hemisphere`() {
        // Shader and LightManager both use +Z at the camera-facing pole.
        val surfaceToLight = viewSpaceLightDirection(0f, 89.9f)
        assertEquals(kotlin.math.cos(Math.toRadians(89.9)), surfaceToLight.x, 1e-6)
        assertEquals(0.0, surfaceToLight.y, 1e-6)
        assertEquals(kotlin.math.sin(Math.toRadians(89.9)), surfaceToLight.z, 1e-6)

        val lightManagerDirection = worldLightManagerDirection(
            azimuthDegrees = 0f,
            elevationDegrees = 89.9f,
            cameraRotation = com.krystals.crystal.core.math.Mat3.IDENTITY,
        )
        assertEquals(-surfaceToLight.x, lightManagerDirection.x, 1e-6)
        assertEquals(-surfaceToLight.y, lightManagerDirection.y, 1e-6)
        assertEquals(surfaceToLight.z, lightManagerDirection.z, 1e-6)
    }

    @Test
    fun `light manager flips screen plane but retains camera side`() {
        val managerDirection = worldLightManagerDirection(
            azimuthDegrees = 35f,
            elevationDegrees = 60f,
            cameraRotation = com.krystals.crystal.core.math.Mat3.IDENTITY,
        )
        val surfaceToLight = viewSpaceLightDirection(35f, 60f)
        assertEquals(-surfaceToLight.x, managerDirection.x, 1e-9)
        assertEquals(-surfaceToLight.y, managerDirection.y, 1e-9)
        assertEquals(surfaceToLight.z, managerDirection.z, 1e-9)
    }

    @Test
    fun `light manager direction is camera anchored`() {
        // Rotating the camera must rotate the manager direction by the inverse camera
        // rotation, so the light-to-camera relationship stays constant.
        val rot = com.krystals.crystal.core.math.rotY(90.0)
        val managerDirection = worldLightManagerDirection(
            azimuthDegrees = 0f,
            elevationDegrees = 89.9f,
            cameraRotation = rot,
        )
        val surfaceToLight = viewSpaceLightDirection(0f, 89.9f)
        val expected = com.krystals.crystal.core.math.rotY(-90.0) *
            com.krystals.crystal.core.math.Vec3(-surfaceToLight.x, -surfaceToLight.y, surfaceToLight.z)
        assertEquals(expected.x, managerDirection.x, 1e-9)
        assertEquals(expected.y, managerDirection.y, 1e-9)
        assertEquals(expected.z, managerDirection.z, 1e-9)
    }

    @Test
    fun `default appearance light direction has positive z`() {
        val appearance = com.krystals.renderer.core.style.ViewerAppearance()
        val direction = viewSpaceLightDirection(
            appearance.lightAzimuth,
            appearance.lightElevation,
        )
        // Default azimuth 150 degrees and elevation 30 degrees: upper-left, camera-side.
        assertEquals(30f, appearance.lightElevation)
        assertEquals(1.0, direction.z / kotlin.math.abs(direction.z), 1e-9)
        assertEquals(-1.0, direction.x / kotlin.math.abs(direction.x), 1e-9)
    }

    @Test
    fun `light model keeps stable ambient above directional sun`() {
        assertEquals(0.6f, WorldLight.AMBIENT_RATIO, 0.001f)
        assertEquals(0.4f, WorldLight.SUN_RATIO, 0.001f)
        assertEquals(1.0f, WorldLight.AMBIENT_RATIO + WorldLight.SUN_RATIO, 0.001f)
    }

    @Test
    fun `diffusion slider drives specular width without dead lower clamp`() {
        // v0.7.0 regression: shininess was clamped at 4.0, so moving the diffusion
        // slider barely changed the highlight. Shininess must respond across the range.
        val tight = specularShininess(0.35f)  // diffusion 0   -> radius 0.35
        val wide = specularShininess(2.0f)    // diffusion 1   -> radius 2.0
        assertTrue(tight > wide, "smaller radius must give tighter highlight: $tight vs $wide")
        assertTrue(wide in 0.8f..1.1f, "wide highlight must stay near the broad-light floor, got $wide")
        assertEquals(1.69f, specularShininess(1.175f), 0.01f) // default diffusion 0.5 -> 2/(1.185)
    }

    @Test
    fun `atom pbr configuration matches ceramic spec`() {
        // Glazed ceramic keeps a compact highlight and a clear silhouette reflection.
        assertEquals(0.0f, AtomPbr.METALLIC, 0.001f)
        assertEquals(0.44f, AtomPbr.ROUGHNESS, 0.001f)
        assertEquals(0.45f, AtomPbr.REFLECTANCE, 0.001f)
        assertEquals(1.0f, AtomPbr.CLEAR_COAT, 0.001f)
        assertEquals(0.18f, AtomPbr.CLEAR_COAT_ROUGHNESS, 0.001f)
        assertEquals(0.95f, AtomPbr.SATURATION_FACTOR, 0.001f)
    }

    @Test
    fun `world light intensity stays below overexposure threshold`() {
        // With post-processing disabled (no tone mapping), noon-sun lux levels clip the
        // PBR highlight to pure white — at 90° elevation the camera-facing sun lights
        // the whole visible hemisphere and the atom goes fully white. The lux base is
        // sized so the sun share (50%) stays below clipping across the slider range.
        assertEquals(0.4f, worldLightIntensityLux(0.4f), 0.001f)
        assertEquals(1f, worldLightIntensityLux(1.0f), 0.001f)
        assertEquals(0f, worldLightIntensityLux(0f), 0.001f)
        assertTrue(worldLightIntensityLux(1.0f) < 100_000f, "must not approach noon-sun levels")
        // Bonds are intentionally subordinate to same-colour atoms.
        assertEquals(0.85f, BOND_BRIGHTNESS, 0.001f)
        assertEquals(1.0f, MESH_BRIGHTNESS, 0.001f)
        assertEquals(1f, DIRECTIONAL_LIGHT_LUX_BASE, 0.001f)
        assertEquals(28_000f, AMBIENT_LIGHT_LUX, 1f)
        assertEquals(28_000f, ambientLightIntensityLux(), 1f)
    }

    @Test
    fun `atom roughness follows diffusion slider`() {
        assertEquals(0.28f, atomRoughness(0f), 0.001f)
        assertEquals(AtomPbr.ROUGHNESS, atomRoughness(0.5f), 0.001f)
        assertEquals(0.60f, atomRoughness(1f), 0.001f)
        assertEquals(0.456f, atomRoughness(0.55f), 0.001f)
    }

    @Test
    fun `material light uses front facing negative z convention`() {
        val uiDirection = viewSpaceLightDirection(150f, 30f)
        val materialDirection = materialLightDirection(150f, 30f)
        assertEquals(uiDirection.x, materialDirection.x, 1e-9)
        assertEquals(uiDirection.y, materialDirection.y, 1e-9)
        assertEquals(-uiDirection.z, materialDirection.z, 1e-9)
    }

    @Test
    fun `ambient occlusion is fixed at sixty percent`() {
        assertEquals(0.60f, FIXED_AMBIENT_OCCLUSION_INTENSITY, 0.001f)
    }

    @Test
    fun `hand lit brightness keeps bonds below mesh brightness`() {
        assertEquals(BOND_BRIGHTNESS, handLitBrightness(MaterialKind.BOND_NORMAL), 0.001f)
        assertEquals(BOND_BRIGHTNESS, handLitBrightness(MaterialKind.BOND_NORMAL_TRANSPARENT), 0.001f)
        assertEquals(BOND_BRIGHTNESS, handLitBrightness(MaterialKind.BOND_HYDROGEN), 0.001f)
        assertEquals(MESH_BRIGHTNESS, handLitBrightness(MaterialKind.MESH_POLYHEDRON), 0.001f)
    }

    @Test
    fun `export keeps readable and sRGB flags`() {
        assertEquals(SwapChainFlags.CONFIG_READABLE, exportSwapChainFlags(false))
        assertEquals(
            SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_SRGB_COLORSPACE,
            exportSwapChainFlags(true),
        )
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

    @Test
    fun `export render size follows quality tier`() {
        // LOW: same size as the live viewport (viewer-like image).
        assertEquals(1080 to 2200, exportRenderSize(1080, 2200, high = false))
        // LOW floors at 512.
        assertEquals(512 to 512, exportRenderSize(300, 300, high = false))
        // HIGH: 4x supersample fitted to the 4096 long edge without aspect distortion.
        assertEquals(2010 to 4096, exportRenderSize(1080, 2200, high = true))
        // Square viewports retain their aspect ratio at the cap.
        assertEquals(4096 to 4096, exportRenderSize(4096, 4096, high = true))
    }
}
