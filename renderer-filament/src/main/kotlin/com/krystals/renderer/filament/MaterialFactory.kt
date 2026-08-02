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
            val alpha = (key.argb ushr 24 and 0xFF).toFloat() / 255f * Double.fromBits(key.opacityBits).toFloat()
            val red = (key.argb ushr 16 and 0xFF).toFloat() / 255f
            val green = (key.argb ushr 8 and 0xFF).toFloat() / 255f
            val blue = (key.argb and 0xFF).toFloat() / 255f
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
 * applied here. The phi-flip convention is kept (0° elevation = horizon, 90° = at the
 * camera, -sin(phi) on Z).
 */
internal fun viewSpaceLightDirection(
    azimuthDegrees: Float,
    elevationDegrees: Float,
): com.krystals.crystal.core.math.Vec3 {
    val theta = Math.toRadians(azimuthDegrees.toDouble())
    val phi = Math.toRadians(elevationDegrees.coerceIn(0f, 90f).toDouble())
    val cosPhi = kotlin.math.cos(phi)
    // Surface-to-light in view space: 0° elevation = horizon, 90° = toward the camera (-Z).
    return com.krystals.crystal.core.math.Vec3(
        cosPhi * kotlin.math.cos(theta),
        cosPhi * kotlin.math.sin(theta),
        -kotlin.math.sin(phi),
    )
}

/**
 * Ambient floor for the unlit shader lighting, as a function of the world-light
 * intensity. Must mirror the formula compiled into the .mat payloads (atom_opaque,
 * atom_transparent, opaque, transparent, polyhedron and their unlit variants).
 *
 * v0.8.14: intensity now RAISES ambient (brightness slider brightens atoms). The old
 * formula (0.62 - 0.32*intensity) inverted the slider — higher intensity dimmed atoms.
 */
internal fun diffuseAmbient(intensity: Float): Float =
    (0.35f + 0.30f * intensity).coerceIn(0.35f, 0.65f)

/**
 * Blinn-Phong shininess from the highlight radius (0.35 + 1.15*diffusion), mirroring
 * the .mat payloads. v0.8.14: no dead 4.0 lower clamp — the diffusion slider must
 * visibly widen/narrow the highlight across its full range.
 */
internal fun specularShininess(highlightRadius: Float): Float =
    maxOf(2.0f / (highlightRadius + 0.01f), 1.5f)
