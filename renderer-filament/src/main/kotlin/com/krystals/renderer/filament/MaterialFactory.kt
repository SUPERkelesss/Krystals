package com.krystals.renderer.filament

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.Colors
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.krystals.renderer.core.style.RenderEnvironment
import com.krystals.renderer.core.style.DepthCueing
import com.krystals.renderer.core.style.WorldLight
import com.krystals.renderer.core.style.desaturateArgb
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Material tree — one Filament material per semantic kind:
 *
 * ├── Atom
 * │   ├── Solid        lit PBR ceramic, fully occupied opaque atoms
 * │   ├── Occupancy    lit PBR ceramic, partial-occupancy atoms: occupied share opaque,
 * │   │                missing share a solid 30%-color/70%-background wedge (opaque pass)
 * │   ├── Pie          lit PBR ceramic, gathered (mixed-occupancy) atoms: per-element
 * │   │                sector colors from twelve o'clock clockwise + missing wedge
 * │   └── Transparent  lit PBR ceramic + user atom opacity (translucent pass)
 * ├── Bond
 * │   ├── Normal       hand-lit (diffuse + Blinn-Phong), opaque / transparent variants
 * │   └── Hydrogen     hand-lit diffuse only, translucent
 * └── Mesh
 *     └── Polyhedron   hand-lit, translucent, two-sided
 *
 * Cell frame / axis / measurement / polyhedron outline reuse the Bond materials:
 * with reflective=false their shader degrades to plain diffuse, matching how they
 * rendered before the tree consolidation.
 */
enum class MaterialKind(val assetName: String) {
    // ── Atom ─────────────────────────────────────────────────────────────
    ATOM_SOLID("materials/atom_solid.filamat"),
    ATOM_OCCUPANCY("materials/atom_occupancy.filamat"),
    ATOM_PIE("materials/atom_pie.filamat"),
    ATOM_TRANSPARENT("materials/atom_transparent.filamat"),
    // ── Bond ─────────────────────────────────────────────────────────────
    BOND_NORMAL("materials/bond_normal.filamat"),
    BOND_NORMAL_TRANSPARENT("materials/bond_normal_transparent.filamat"),
    BOND_HYDROGEN("materials/bond_hydrogen.filamat"),
    // ── Mesh ─────────────────────────────────────────────────────────────
    MESH_POLYHEDRON("materials/mesh_polyhedron.filamat"),
}

/** True for the four atom materials (lit PBR ceramic). */
internal val MaterialKind.isAtom: Boolean
    get() = this == MaterialKind.ATOM_SOLID || this == MaterialKind.ATOM_OCCUPANCY ||
        this == MaterialKind.ATOM_PIE || this == MaterialKind.ATOM_TRANSPARENT

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
            // Atom base color is the CPK color desaturated by 5% (AtomPbr).
            val argb = if (kind.isAtom) desaturateArgb(key.argb, AtomPbr.SATURATION_FACTOR) else key.argb
            val alpha = (argb ushr 24 and 0xFF).toFloat() / 255f * Double.fromBits(key.opacityBits).toFloat()
            val red = (argb ushr 16 and 0xFF).toFloat() / 255f
            val green = (argb ushr 8 and 0xFF).toFloat() / 255f
            val blue = (argb and 0xFF).toFloat() / 255f
            runCatching { instance.setParameter("baseColor", Colors.RgbaType.SRGB, red, green, blue, alpha) }
            runCatching { instance.setParameter("reflectionEnabled", if (key.reflective) 1f else 0f) }
            runCatching { instance.setParameter("occupancy", Double.fromBits(key.occupancyBits).toFloat()) }
            // Gathered pie: sector colors + cumulative end angles, twelve o'clock clockwise.
            if (kind == MaterialKind.ATOM_PIE && key.slices.isNotEmpty()) {
                val segments = FloatArray(32) // 8 × float4 (rgb + cumulative fraction)
                var cum = 0f
                key.slices.forEachIndexed { i, enc ->
                    if (i >= 8) return@forEachIndexed
                    val argb = enc ushr 16
                    val frac = (enc and 0xFFFF).toFloat() / 65535f
                    cum += frac
                    segments[i * 4] = ((argb ushr 16) and 0xFF).toFloat() / 255f
                    segments[i * 4 + 1] = ((argb ushr 8) and 0xFF).toFloat() / 255f
                    segments[i * 4 + 2] = (argb and 0xFF).toFloat() / 255f
                    segments[i * 4 + 3] = cum
                }
                // Filament array parameters: the [size] suffix belongs to the TYPE in
                // the .mat declaration (`float4[8]`), not the name — declaring
                // `name : segments[8]` bakes the literal "segments[8] " (with a stray
                // space) as the uniform name and every setParameter misses silently.
                runCatching { instance.setParameter("segments", MaterialInstance.FloatElement.FLOAT4, segments, 0, segments.size / 4) }
            }
            instances += instance
            instanceCache[kind to key] = instance
            applyEnvironment(instance, kind, key, lastDepthState ?: DepthState(environment, 0f, 0f))
        }
    }

    fun updateDepthCueing(instance: MaterialInstance, environment: RenderEnvironment, near: Float, far: Float) {
        val cached = instanceCache.entries.firstOrNull { it.value === instance } ?: return
        applyEnvironment(instance, cached.key.first, cached.key.second, DepthState(environment, near, far))
    }

    private fun applyEnvironment(instance: MaterialInstance, kind: MaterialKind, key: MaterialKey, state: DepthState) {
        val environment = state.environment
        val cue = environment.depthCueing
        val argb = environment.backgroundArgb
        val viewRange = depthCueViewRange(cue, state.near, state.far)
        val light = environment.worldLight
        runCatching { instance.setParameter("depthRange", viewRange.first, viewRange.second) }
        runCatching { instance.setParameter("depthCueEnabled", if (cue.enabled) 1f else 0f) }
        // The light is anchored to the camera (view space): rotating the crystal never
        // changes the light-to-camera relationship, so the direction is camera-independent.
        val direction = materialLightDirection(light.azimuthDegrees, light.elevationDegrees)
        runCatching {
            instance.setParameter(
                "lightDirection",
                direction.x.toFloat(),
                direction.y.toFloat(),
                direction.z.toFloat(),
            )
        }
        runCatching { instance.setParameter("highlightIntensity", light.intensity) }
            runCatching { instance.setParameter("highlightRadius", 0.35f + 1.65f * light.diffusion) }
        // The diffusion slider widens the highlight on every instance class: hand-lit
        // shaders (bond/mesh) via highlightRadius → shininess, lit atom materials via
        // PBR roughness. Both are driven by the same WorldLight.diffusion value.
        if (kind.isAtom) {
            runCatching { instance.setParameter("roughness", atomRoughness(light.diffusion)) }
        }
        // Bonds stay subordinate to same-colour atoms. Polyhedra retain their existing
        // brightness so this hierarchy change does not alter unrelated mesh styling.
        runCatching { instance.setParameter("brightness", handLitBrightness(kind)) }
        // Sun/ambient split of the hand-lit shaders, injected from the same WorldLight
        // constants that drive the scene's directional light and IndirectLight — atom,
        // bond and mesh instances always follow one light model.
        runCatching { instance.setParameter("sunShare", WorldLight.SUN_RATIO) }
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
        instanceCache.forEach { (cacheKey, instance) -> applyEnvironment(instance, cacheKey.first, cacheKey.second, state) }
    }

    /** Destroys cached instances that are no longer referenced by any live renderable. */
    fun retainInstances(usedKeys: Set<Pair<MaterialKind, MaterialKey>>) {
        val iterator = instanceCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in usedKeys) continue
            engine.destroyMaterialInstance(entry.value)
            instances.remove(entry.value)
            iterator.remove()
        }
    }

    private fun load(kind: MaterialKind): Material? = runCatching {
        val bytes = context.assets.open(kind.assetName).use { it.readBytes() }
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
        Material.Builder().payload(payload, bytes.size).build(engine)
    }.getOrNull()

    override fun close() {
        instances.asReversed().forEach(engine::destroyMaterialInstance)
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

/** Maps the normalized visible-depth scale (+3 near, -3 far) to Filament view-space Z. */
internal fun depthCueViewRange(cue: DepthCueing, visibleNear: Float, visibleFar: Float): Pair<Float, Float> {
    val span = (visibleNear - visibleFar).coerceAtLeast(1e-6f)
    val center = (visibleNear + visibleFar) * 0.5f
    fun toViewDepth(normalizedDepth: Float): Float = center + normalizedDepth * span / 6f
    return toViewDepth(cue.near) to toViewDepth(cue.far)
}

/**
 * Sun direction expressed in view space, fixed relative to the camera (surface-to-light:
 * the direction FROM the surface TOWARD the sun, used by the NdotL shader term).
 *
 * The sun is anchored to the camera: no matter how the crystal is rotated, the
 * sun-to-camera relationship stays constant, so [cameraRotation] must NOT be applied.
 *
 * Sign convention: Filament view space looks down -Z, so a camera-side light has
 * surface-to-light +Z. This same vector also feeds the device-calibrated LightManager.
 */
internal fun viewSpaceLightDirection(
    azimuthDegrees: Float,
    elevationDegrees: Float,
): com.krystals.crystal.core.math.Vec3 {
    val theta = Math.toRadians(azimuthDegrees.toDouble())
    val phi = Math.toRadians(elevationDegrees.coerceIn(0f, 89.9f).toDouble())
    val cosPhi = kotlin.math.cos(phi)
    // Filament view space looks down -Z, so a camera-side light is surface-to-light +Z.
    return com.krystals.crystal.core.math.Vec3(
        cosPhi * kotlin.math.cos(theta),
        cosPhi * kotlin.math.sin(theta),
        kotlin.math.sin(phi),
    )
}

/**
 * Direction supplied to Filament's LightManager in world space.
 *
 * Device verification shows that this camera setup needs +Z to light the camera-facing
 * atom hemisphere, while Filament's screen-plane convention is opposite to the shader's
 * surface-to-light X/Y convention. Flip X/Y only, then rotate the camera-anchored vector
 * into world space.
 */
internal fun worldLightManagerDirection(
    azimuthDegrees: Float,
    elevationDegrees: Float,
    cameraRotation: com.krystals.crystal.core.math.Mat3,
): com.krystals.crystal.core.math.Vec3 {
    val viewSurfaceToLight = viewSpaceLightDirection(azimuthDegrees, elevationDegrees)
    val filamentDirection = com.krystals.crystal.core.math.Vec3(
        -viewSurfaceToLight.x,
        -viewSurfaceToLight.y,
        viewSurfaceToLight.z,
    )
    return cameraRotation.transposed() * filamentDirection
}

/** Custom materials use the renderer's front surface at view-space -Z. */
internal fun materialLightDirection(
    azimuthDegrees: Float,
    elevationDegrees: Float,
): com.krystals.crystal.core.math.Vec3 {
    val uiDirection = viewSpaceLightDirection(azimuthDegrees, elevationDegrees)
    return com.krystals.crystal.core.math.Vec3(uiDirection.x, uiDirection.y, -uiDirection.z)
}

/**
 * Sun angular radius -> Blinn-Phong shininess (highlight radius 0.35 + 1.65*diffusion),
 * mirroring the hand-lit .mat shaders. No lower clamp: the diffusion slider must visibly
 * widen/narrow the highlight across its full range.
 */
internal fun specularShininess(highlightRadius: Float): Float =
    maxOf(2.0f / (highlightRadius + 0.01f), 0.8f)

/**
 * Atom ceramic PBR parameters — must mirror the values compiled into
 * atom_solid.mat / atom_transparent.mat.
 *
 * Glazed ceramic: non-metallic dielectric with a controlled, visible highlight.
 * The scene light model (60% ambient / 40% sun) is applied by
 * Filament's IndirectLight + directional light. The 5% CPK desaturation is atom-specific.
 */
internal object AtomPbr {
    const val METALLIC = 0.0f
    const val ROUGHNESS = 0.44f
    const val REFLECTANCE = 0.45f
    const val CLEAR_COAT = 1.0f
    const val CLEAR_COAT_ROUGHNESS = 0.18f
    /** CPK base color is desaturated by 5% before it reaches the material. */
    const val SATURATION_FACTOR = 0.95f
}

/**
 * PBR roughness for lit atom materials, driven by the sun diffusion slider.
 * diffusion 0 → sharp highlight (0.28), 0.5 → base ceramic roughness, 1 →
 * soft but still visible highlight (0.60). Keeping the upper end below 1 prevents
 * the highlight from disappearing at the default diffusion setting.
 */
internal fun atomRoughness(diffusion: Float): Float =
    (0.28f + diffusion * 0.32f).coerceIn(0.28f, 0.60f)

/**
 * PBR atoms receive their stable base illumination from the indirect light. Their
 * directional highlight is bounded in the atom material, so the Filament directional
 * light stays at a negligible intensity and cannot clip the whole sphere near 90°.
 */
internal const val DIRECTIONAL_LIGHT_LUX_BASE = 1f
internal const val AMBIENT_LIGHT_LUX = 28_000f

/**
 * Overall brightness multiplier for the hand-lit (bond/mesh/hydrogen) shaders. The lit
 * atom path uses a stable indirect-light base; this factor keeps bonds subordinate.
 */
internal const val BOND_BRIGHTNESS = 0.85f
internal const val MESH_BRIGHTNESS = 1.0f

internal fun handLitBrightness(kind: MaterialKind): Float = when (kind) {
    MaterialKind.BOND_NORMAL,
    MaterialKind.BOND_NORMAL_TRANSPARENT,
    MaterialKind.BOND_HYDROGEN,
    -> BOND_BRIGHTNESS
    MaterialKind.MESH_POLYHEDRON -> MESH_BRIGHTNESS
    else -> 1.0f
}

/**
 * Negligible PBR directional light. The slider-controlled atom highlight is evaluated
 * and bounded in the material; hand-lit bonds and meshes use the slider directly.
 */
internal fun worldLightIntensityLux(intensity: Float): Float =
    intensity.coerceIn(0f, 1f) * DIRECTIONAL_LIGHT_LUX_BASE

/** Ambient fill remains stable while the slider controls only directional light. */
internal fun ambientLightIntensityLux(): Float = AMBIENT_LIGHT_LUX
