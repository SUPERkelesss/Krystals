package com.krystals.app

import android.content.Context
import android.graphics.Bitmap
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.style.RenderEnvironment
import com.krystals.renderer.core.style.ViewerAppearance
import com.krystals.renderer.core.style.WorldLight
import com.krystals.renderer.filament.FilamentRenderer

/**
 * Per v0.8.32: renders the Appearance dialog's preview spheres with the real Filament
 * backend (the same engine path as the viewer) so radius, size, color, reflection and
 * opacity reflect the current in-dialog values exactly. Renders off-screen via
 * [FilamentRenderer.renderToBitmap] — no Surface/swap chain needed.
 *
 * Spheres are laid out along x in unit space; the ortho zoom controls how large they
 * appear in the image (span = sceneRadius / zoom, sceneRadius ≈ |max x| + sphereRadius).
 */
object AppearancePreviewRenderer {
    /** Export floor used by FilamentRenderer (512..4096). */
    const val PREVIEW_PX = 512

    /** Preview sphere color, matching the old Canvas previews. */
    const val PREVIEW_SPHERE_ARGB: Long = 0xFFA8A8AD

    private var renderer: FilamentRenderer? = null

    /**
     * Renders N spheres and returns the bitmap (or null on failure).
     * Call from the main thread; the actual render hops onto the Filament thread.
     *
     * @param xOffsets        sphere centers along x (unit space), e.g. [-3.5, -1.75, 0, 1.75, 3.5]
     * @param sphereRadius    unit radius of every sphere
     * @param zoom            ortho zoom; sphere diameter occupies 1/(boundsRadius·zoom) of the
     *                        image width/height. Single centered sphere radius 1, zoom 0.76 → 76%.
     * @param centerYFraction where sphere centers sit vertically in the image (0.5 = centered).
     * @param fogFactors      per-sphere fog 0..1 (1 = fully fogged); the sphere color blends
     *                        toward [backgroundArgb] by this factor, mirroring the depth preview.
     */
    suspend fun renderSpheres(
        context: Context,
        appearance: ViewerAppearance,
        xOffsets: List<Double>,
        sphereRadius: Double,
        zoom: Double,
        centerYFraction: Float = 0.5f,
        fogFactors: List<Float> = emptyList(),
        backgroundArgb: Long = 0xFF101014,
        widthPx: Int = PREVIEW_PX,
        heightPx: Int = PREVIEW_PX,
    ): Bitmap? {
        return try {
            val r = renderer ?: FilamentRenderer(context).also { renderer = it }
            val boundsRadius = xOffsets.maxOfOrNull { kotlin.math.abs(it) }?.plus(sphereRadius) ?: sphereRadius
            val span = boundsRadius / zoom
            val centerY = (centerYFraction * 2f - 1f) * span.toFloat()
            val objects = xOffsets.mapIndexed { i, x ->
                val fog = fogFactors.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                AtomInstance(
                    id = "preview-$i",
                    atom = AtomImage(
                        id = i.toLong(),
                        siteId = "preview",
                        siteLabel = "preview",
                        species = Species("Si"),
                        fractionalCoordinate = FractionalCoordinate(0.0, 0.0, 0.0),
                        cartesianCoordinate = CartesianCoordinate(x, centerY.toDouble(), 0.0),
                        occupancy = 1.0,
                        cellOffset = Int3(0, 0, 0),
                    ),
                    radius = sphereRadius,
                    material = Material(
                        argb = blendColor(PREVIEW_SPHERE_ARGB, backgroundArgb, fog),
                        opacity = appearance.atomOpacity.toDouble(),
                        reflective = appearance.reflectionEnabled,
                    ),
                    visible = true,
                )
            }
            val structure = CrystalStructure(
                blockName = "preview",
                lattice = Lattice(1.0, 1.0, 1.0, 90.0, 90.0, 90.0),
                spaceGroup = SpaceGroupCatalog.find("P 1") ?: SpaceGroupCatalog.all.first(),
                symmetryOperations = emptyList(),
                sites = listOf(
                    Site("preview", "preview", Species("Si"), FractionalCoordinate(0.0, 0.0, 0.0), 1.0),
                ),
            )
            val scene = RenderScene(
                structure = structure,
                expansion = Expansion(),
                objects = objects,
                camera = Camera(zoom = zoom.toDouble()),
                environment = RenderEnvironment(
                    backgroundArgb = backgroundArgb,
                    worldLight = WorldLight(
                        azimuthDegrees = appearance.lightAzimuth,
                        elevationDegrees = appearance.lightElevation,
                        intensity = appearance.lightIntensity,
                        diffusion = appearance.diffusion,
                    ),
                ),
            )
            r.submit(scene)
            val bmp = r.renderToBitmap(widthPx, heightPx, useMsaa = false)
            if (bmp == null) {
                android.util.Log.e("AppearancePreview", "renderToBitmap returned null (engine=${r.hashCode()})")
            }
            bmp
        } catch (e: Exception) {
            android.util.Log.e("AppearancePreview", "renderSpheres failed", e)
            null
        }
    }

    /** Releases the dedicated preview engine (call when the dialog leaves composition). */
    fun release() {
        renderer?.close()
        renderer = null
    }

    /** Linear sRGB blend: base → bg by [f] (0 = pure base, 1 = pure bg). */
    private fun blendColor(base: Long, bg: Long, f: Float): Long {
        val t = f.coerceIn(0f, 1f)
        fun channel(shift: Int): Long {
            val b = (base shr shift) and 0xFF
            val g = (bg shr shift) and 0xFF
            return (b + ((g - b) * t)).toLong().toInt().coerceIn(0, 255).toLong()
        }
        return (0xFF000000L) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
