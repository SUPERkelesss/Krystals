package com.krystals.renderer.filament

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.Colors
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.krystals.renderer.core.style.RenderEnvironment
import com.krystals.renderer.core.style.DepthCueing
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MaterialKind(val assetName: String) {
    ATOM_OPAQUE("materials/atom_opaque.filamat"),
    ATOM_TRANSPARENT("materials/atom_transparent.filamat"),
    OPAQUE("materials/opaque.filamat"),
    TRANSPARENT("materials/transparent.filamat"),
    POLYHEDRON("materials/polyhedron.filamat"),
    UNLIT_OPAQUE("materials/unlit_opaque.filamat"),
    UNLIT_TRANSPARENT("materials/unlit_transparent.filamat"),
    UNLIT_POLYHEDRON("materials/unlit_polyhedron.filamat"),
    HIGHLIGHT("materials/highlight.filamat"),
    DEPTH_CUEING("materials/depth_cueing.filamat"),
    PICKING("materials/picking.filamat"),
}

/** True for the two atom materials (lit PBR since v0.8.18). */
internal val MaterialKind.isAtom: Boolean
    get() = this == MaterialKind.ATOM_OPAQUE || this == MaterialKind.ATOM_TRANSPARENT

/** Loads fixed-version filamat payloads and owns all Material / MaterialInstance objects. */
class MaterialFactory(
    private val context: Context,
    private val engine: Engine,
) : AutoCloseable {
    private val materials = linkedMapOf<MaterialKind, Material>()
    private val instances = mutableListOf<MaterialInstance>()
    private val instanceCache = linkedMapOf<Pair<MaterialKind, MaterialKey>, MaterialInstance>()
    private var lastDepthState: DepthState? = null

    companion object {
        fun requirePayloads(context: Context) {
            MaterialKind.entries.forEach { kind ->
                context.assets.open(kind.assetName).use { stream ->
                    require(stream.available() > 0) { "Empty Filament material: ${kind.assetName}" }
                }
            }
        }
    }

    fun create(kind: MaterialKind, key: MaterialKey, environment: RenderEnvironment): MaterialInstance? {
        instanceCache[kind to key]?.let { return it }
        val material = materials[kind] ?: load(kind)?.also { materials[kind] = it } ?: return null
        return material.createInstance().also { instance ->
            // v0.8.18: atom base color is the CPK color desaturated by 5% (AtomPbr).
            val argb = if (kind.isAtom) desaturateArgb(key.argb, AtomPbr.SATURATION_FACTOR) else key.argb
            val alpha = (argb ushr 24 and 0xFF).toFloat() / 255f * Double.fromBits(key.opacityBits).toFloat()
            val red = (argb ushr 16 and 0xFF).toFloat() / 255f
            val green = (argb ushr 8 and 0xFF).toFloat() / 255f
            val blue = (argb and 0xFF).toFloat() / 255f
            runCatching { instance.setParameter("baseColor", Colors.RgbaType.SRGB, red, green, blue, alpha) }
            runCatching { instance.setParameter("reflectionEnabled", if (key.reflective) 1f else 0f) }
            runCatching { instance.setParameter("occupancy", Double.fromBits(key.occupancyBits).toFloat()) }
            instances += instance
            instanceCache[kind to key] = instance
            applyEnvironment(instance, key, lastDepthState ?: DepthState(environment, 0f, 0f))
        }
    }

    fun updateDepthCueing(instance: MaterialInstance, environment: RenderEnvironment, near: Float, far: Float) {
        applyEnvironment(instance, instanceCache.entries.firstOrNull { it.value === instance }?.key?.second ?: return, DepthState(environment, near, far))
    }

    private fun applyEnvironment(instance: MaterialInstance, key: MaterialKey, state: DepthState) {
        val environment = state.environment
        val cue = environment.depthCueing
        val argb = environment.backgroundArgb
        val viewRange = depthCueViewRange(cue, state.near, state.far)
        val light = environment.worldLight
        runCatching { instance.setParameter("depthRange", viewRange.first, viewRange.second) }
        runCatching { instance.setParameter("depthCueEnabled", if (cue.enabled) 1f else 0f) }
        // The light is anchored to the camera (view space): rotating the crystal never
        // changes the light-to-camera relationship, so the direction is camera-independent.
        val direction = viewSpaceLightDirection(light.azimuthDegrees, light.elevationDegrees)
        runCatching {
            instance.setParameter(
                "lightDirection",
                direction.x.toFloat(),
                direction.y.toFloat(),
                direction.z.toFloat(),
            )
        }
        runCatching { instance.setParameter("highlightIntensity", light.intensity) }
        runCatching { instance.setParameter("highlightRadius", 0.35f + 1.15f * light.diffusion) }
        runCatching {
            instance.setParameter(
                "backgroundColor",
                Colors.RgbType.SRGB,
                (argb ushr 16 and 0xFF).toFloat() / 255f,
                (argb ushr 8 and 0xFF).toFloat() / 255f,
                (argb and 0xFF).toFloat() / 255f,
            )
        }
    }

    fun updateDepthCueing(environment: RenderEnvironment, near: Float, far: Float) {
        val state = DepthState(environment, near, far)
        if (state == lastDepthState) return
        lastDepthState = state
        instanceCache.forEach { (cacheKey, instance) -> applyEnvironment(instance, cacheKey.second, state) }
    }

    private fun load(kind: MaterialKind): Material? = runCatching {
        val bytes = context.assets.open(kind.assetName).use { it.readBytes() }
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
        Material.Builder().payload(payload, bytes.size).build(engine)
    }.getOrNull()

    override fun close() {
        instances.asReversed().forEach(engine::destroyMaterialInstance)
        // Per v0.8.1: destroyMaterial does not mutate the materials map — forward iteration avoids
        // the toList() snapshot (reverse order within a shutdown pass is irrelevant).
        materials.values.forEach(engine::destroyMaterial)
        instances.clear()
        instanceCache.clear()
        materials.clear()
        lastDepthState = null
    }
}

private data class DepthState(
    val environment: RenderEnvironment,
    val near: Float,
    val far: Float,
)

/** Maps Legacy's normalized visible-depth scale (+3 near, -3 far) to Filament view-space Z. */
internal fun depthCueViewRange(cue: DepthCueing, visibleNear: Float, visibleFar: Float): Pair<Float, Float> {
    val span = (visibleNear - visibleFar).coerceAtLeast(1e-6f)
    val center = (visibleNear + visibleFar) * 0.5f
    fun toViewDepth(normalizedDepth: Float): Float = center + normalizedDepth * span / 6f
    return toViewDepth(cue.near) to toViewDepth(cue.far)
}

/**
 * Light direction expressed in view space, fixed relative to the camera.
 *
 * The world light is anchored to the camera: no matter how the crystal is rotated,
 * the light-to-camera relationship stays constant, so [cameraRotation] must NOT be
 * applied here. The phi convention: 0° elevation = horizon, 90° = at the camera
 * (+sin(phi) on Z). In Filament view space the camera looks down -Z, so the surface
 * facing the camera has normal +Z; a 90° elevation light therefore points at +Z
 * (surface-to-light). v0.8.24: Z sign flipped from -sin(phi) — the old sign put the
 * light behind the scene, darkening the camera-facing hemisphere.
 */
internal fun viewSpaceLightDirection(
    azimuthDegrees: Float,
    elevationDegrees: Float,
): com.krystals.crystal.core.math.Vec3 {
    val theta = Math.toRadians(azimuthDegrees.toDouble())
    val phi = Math.toRadians(elevationDegrees.coerceIn(0f, 90f).toDouble())
    val cosPhi = kotlin.math.cos(phi)
    // Surface-to-light in view space: 0° elevation = horizon, 90° = toward the camera (+Z).
    return com.krystals.crystal.core.math.Vec3(
        cosPhi * kotlin.math.cos(theta),
        cosPhi * kotlin.math.sin(theta),
        kotlin.math.sin(phi),
    )
}

/**
 * World-space light TRAVEL direction for Filament's LightManager.setDirection.
 *
 * The camera-anchored light is expressed in view space by [viewSpaceLightDirection]
 * as surface-to-light (the direction from the surface toward the light, used by the
 * shader's NdotL term). Filament's directional light wants the opposite sign — the
 * direction the light TRAVELS (from the light toward the scene). Negate, then rotate
 * from view space into world space via the inverse camera rotation, keeping the
 * light-to-camera relationship constant as the crystal rotates.
 */
internal fun worldLightTravelDirection(
    azimuthDegrees: Float,
    elevationDegrees: Float,
    cameraRotation: com.krystals.crystal.core.math.Mat3,
): com.krystals.crystal.core.math.Vec3 {
    val viewSurfaceToLight = viewSpaceLightDirection(azimuthDegrees, elevationDegrees)
    val viewTravel = viewSurfaceToLight * -1.0
    return cameraRotation.transposed() * viewTravel
}

/**
 * Ambient floor for the unlit shader lighting, as a function of the world-light
 * intensity. Must mirror the formula compiled into the .mat payloads (atom_opaque,
 * atom_transparent, opaque, transparent, polyhedron and their unlit variants).
 *
 * v0.8.14: intensity now RAISES ambient (brightness slider brightens atoms). The old
 * formula (0.62 - 0.32*intensity) inverted the slider — higher intensity dimmed atoms.
 * v0.8.17: ambient is a faint base (0.12 + 0.10i) under the mirror highlight — atoms
 * are lit by specular alone, never washed out; the positive correlation is preserved.
 */
internal fun diffuseAmbient(intensity: Float): Float =
    (0.12f + 0.10f * intensity).coerceIn(0.12f, 0.30f)

/**
 * Blinn-Phong shininess from the highlight radius (0.35 + 1.15*diffusion), mirroring
 * the .mat payloads. v0.8.14: no dead 4.0 lower clamp — the diffusion slider must
 * visibly widen/narrow the highlight across its full range.
 * v0.8.16: base raised to 4.0 so the mirror highlight stays tight on glass atoms.
 */
internal fun specularShininess(highlightRadius: Float): Float =
    maxOf(4.0f / (highlightRadius + 0.01f), 3.0f)

/**
 * Frosted (diffuse) share of the atom glass shading — mirrors the NdotL weight in the
 * atom_opaque / atom_transparent payloads. v0.8.17: fully removed (0.0) — atoms are
 * lit by the mirror highlight alone on the faint ambient base.
 */
internal fun atomDiffuseWeight(): Float = 0.0f

/**
 * Specular blend factor for the atom glass shading — mirrors the `specular * X` mix in
 * the atom payloads. Full white makes the mirror highlight pop on the faint base.
 */
internal fun atomSpecularBlend(): Float = 1.0f

/**
 * Atom material shading parameters.
 *
 * v0.8.24: atoms are lit PBR materials again (user spec) with a 0.4 clear coat on top
 * of the CPK base color. Must mirror the values baked into atom_opaque.mat /
 * atom_transparent.mat. The 5% CPK desaturation remains atom-specific.
 */
internal object AtomPbr {
    const val METALLIC = 0.0f
    const val ROUGHNESS = 0.32f
    const val REFLECTANCE = 0.45f
    const val CLEAR_COAT = 0.4f
    // v0.8.25: clear-coat roughness raised 0.25 -> 0.5 for a softer coat highlight.
    const val CLEAR_COAT_ROUGHNESS = 0.5f
    /** CPK base color is desaturated by 5% before it reaches the material. */
    const val SATURATION_FACTOR = 0.95f
}

/**
 * Directional light intensity in lux for a [worldLightIntensityLux]-style mapping.
 *
 * v0.8.21: 150_000 lux (noon sun) overexposed the PBR highlight to pure white because
 * the view has post-processing (tone mapping) disabled — the highlight clipped and its
 * arc edge read as a "bright edge" on the atom. 30_000 lux at intensity=1.0 (12_000 at
 * the 0.4 default) keeps a strong studio key light without clipping.
 */
internal fun worldLightIntensityLux(intensity: Float): Float =
    (intensity * 30_000f).coerceAtLeast(1f)

/**
 * Desaturates an ARGB color by [factor] (1.0 = identity) in HSL space, preserving hue
 * and lightness. Used for the atom base color (CPK colors at 95% saturation).
 */
internal fun desaturateArgb(argb: Long, factor: Float): Long {
    val alpha = (argb ushr 24 and 0xFF).toInt()
    var r = (argb ushr 16 and 0xFF).toInt() / 255f
    var g = (argb ushr 8 and 0xFF).toInt() / 255f
    var b = (argb and 0xFF).toInt() / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return argb // gray: saturation already 0
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val newSaturation = (saturation * factor).coerceIn(0f, 1f)
    if (newSaturation == 0f) {
        val gray = (lightness * 255f).toInt().coerceIn(0, 255)
        return (alpha.toLong() shl 24) or (gray.toLong() shl 16) or (gray.toLong() shl 8) or gray.toLong()
    }
    val q = if (lightness < 0.5f) lightness * (1f + newSaturation) else lightness + newSaturation - lightness * newSaturation
    val p = 2f * lightness - q
    fun hue2rgb(p: Float, q: Float, t: Float): Float {
        var tt = t
        if (tt < 0f) tt += 1f
        if (tt > 1f) tt -= 1f
        return when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
    }
    var h = 0f
    when (max) {
        r -> h = ((g - b) / delta + if (g < b) 6f else 0f) / 6f
        g -> h = ((b - r) / delta + 2f) / 6f
        b -> h = ((r - g) / delta + 4f) / 6f
    }
    r = hue2rgb(p, q, h + 1f / 3f)
    g = hue2rgb(p, q, h)
    b = hue2rgb(p, q, h - 1f / 3f)
    fun toByte(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    return (alpha.toLong() shl 24) or
        (toByte(r).toLong() shl 16) or
        (toByte(g).toLong() shl 8) or
        toByte(b).toLong()
}
