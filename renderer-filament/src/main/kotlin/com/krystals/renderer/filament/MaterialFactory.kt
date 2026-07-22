package com.krystals.renderer.filament

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.krystals.renderer.core.style.RenderEnvironment
import com.krystals.renderer.core.style.DepthCueing
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MaterialKind(val assetName: String) {
    OPAQUE("materials/opaque.filamat"),
    TRANSPARENT("materials/transparent.filamat"),
    POLYHEDRON("materials/polyhedron.filamat"),
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
            runCatching { instance.setParameter("baseColor", red, green, blue, alpha) }
            runCatching { instance.setParameter("roughness", environment.worldLight.diffusion.coerceIn(0.04f, 1f)) }
            runCatching { instance.setParameter("specular", if (key.reflective) environment.worldLight.intensity else 0f) }
            runCatching { instance.setParameter("occupancy", Double.fromBits(key.occupancyBits).toFloat()) }
            instances += instance
            instanceCache[kind to key] = instance
            lastDepthState?.let { state -> applyDepthCueing(instance, state) }
        }
    }

    fun updateDepthCueing(instance: MaterialInstance, environment: RenderEnvironment, near: Float, far: Float) {
        applyDepthCueing(instance, DepthState(environment, near, far))
    }

    private fun applyDepthCueing(instance: MaterialInstance, state: DepthState) {
        val environment = state.environment
        val cue = environment.depthCueing
        val argb = environment.backgroundArgb
        val viewRange = depthCueViewRange(cue, state.near, state.far)
        runCatching { instance.setParameter("depthRange", viewRange.first, viewRange.second) }
        runCatching { instance.setParameter("depthCueEnabled", if (cue.enabled) 1f else 0f) }
        runCatching {
            instance.setParameter(
                "backgroundColor",
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
        instances.forEach { applyDepthCueing(it, state) }
    }

    private fun load(kind: MaterialKind): Material? = runCatching {
        val bytes = context.assets.open(kind.assetName).use { it.readBytes() }
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
        Material.Builder().payload(payload, bytes.size).build(engine)
    }.getOrNull()

    override fun close() {
        instances.asReversed().forEach(engine::destroyMaterialInstance)
        materials.values.toList().asReversed().forEach(engine::destroyMaterial)
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
