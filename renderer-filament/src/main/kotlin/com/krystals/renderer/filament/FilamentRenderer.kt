package com.krystals.renderer.filament

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.krystals.interaction.selection.PickResult
import com.krystals.interaction.measure.AngleTool
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.DistanceTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.primitive.GatheredAtomInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.SceneBounds
import com.krystals.renderer.core.scene.allBounds
import com.krystals.renderer.core.scene.sceneProjection
import com.krystals.renderer.core.scene.toCameraDepthRange
import com.krystals.renderer.core.scene.visibleBounds
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.SelectionColors
import com.krystals.renderer.core.style.WorldLight
import com.krystals.renderer.core.style.backgroundColor
import com.krystals.crystal.core.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal data class RendererPerformanceSnapshot(
    val sceneSubmissions: Long,
    val interactionUpdates: Long,
    val framesRendered: Long,
    val pendingFrames: Int,
    val frameScheduled: Boolean,
    val depthPointsEvaluated: Int,
)

internal const val FIXED_AMBIENT_OCCLUSION_INTENSITY = 0.60f

/** Single owner for Filament engine, surface, scene, resources and frame scheduling. */
/** Export render size per quality tier. LOW = live viewport. HIGH = 4x supersampling,
 *  fitted inside a 4096px long edge without changing the viewport aspect ratio. */
fun exportRenderSize(viewportWidth: Int, viewportHeight: Int, high: Boolean): Pair<Int, Int> {
    val floor = 512
    if (!high) return viewportWidth.coerceIn(floor, 4096) to viewportHeight.coerceIn(floor, 4096)
    val sourceWidth = viewportWidth.coerceAtLeast(1)
    val sourceHeight = viewportHeight.coerceAtLeast(1)
    val desiredScale = 4.0
    val edgeScale = 4096.0 / maxOf(sourceWidth, sourceHeight).toDouble()
    val scale = minOf(desiredScale, edgeScale)
    return maxOf(1, (sourceWidth * scale).toInt()) to maxOf(1, (sourceHeight * scale).toInt())
}

internal fun exportSwapChainFlags(srgbSupported: Boolean): Long =
    SwapChainFlags.CONFIG_READABLE or
        if (srgbSupported) SwapChainFlags.CONFIG_SRGB_COLORSPACE else 0L

internal fun rgbaBytesToArgb(pixels: ByteBuffer, pixelCount: Int): IntArray {
    require(pixelCount >= 0 && pixels.remaining() >= pixelCount * 4)
    val argb = IntArray(pixelCount)
    if (pixels.order() == ByteOrder.LITTLE_ENDIAN) {
        pixels.asIntBuffer().get(argb)
        for (index in argb.indices) {
            val rgba = argb[index]
            argb[index] = (rgba and 0xFF00FF00.toInt()) or
                ((rgba and 0x000000FF) shl 16) or
                ((rgba and 0x00FF0000) ushr 16)
        }
    } else {
        for (index in argb.indices) {
            val red = pixels.get().toInt() and 0xFF
            val green = pixels.get().toInt() and 0xFF
            val blue = pixels.get().toInt() and 0xFF
            val alpha = pixels.get().toInt() and 0xFF
            argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return argb
}

/** Per v0.7.0 (issue #9): hard timeout for one offscreen pixel readback. If the Filament
 *  readPixels callback never fires (GPU hang / renderer death), the export coroutine
 *  resumes with null instead of suspending forever. */
private const val EXPORT_TIMEOUT_MS = 12_000L

class FilamentRenderer(context: Context) : FilamentSceneRenderer, Choreographer.FrameCallback {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val engine: Engine
    private val renderer: Renderer
    private val filamentScene: Scene
    private val view: View
    private val cameraEntity: Int
    private val lightEntity: Int
    private val indirectLight: IndirectLight
    private val camera: Camera
    private val meshUploader: MeshUploader
    private val materialFactory: MaterialFactory
    private val gpuInstances: GpuInstanceManager
    private val pickingRenderer = PickingRenderer()
    private var swapChain: SwapChain? = null
    private var submittedScene: RenderScene? = null
    private var interaction = InteractionState()
    private var frameScheduled = false
    private val frameBudget = DirtyFrameBudget()
    // Per v0.7.0: while the app is in the background (ON_STOP) frame scheduling is paused —
    // the surface may still exist (lock screen, split view) and without this the renderer
    // would keep drawing frames on any pending request, wasting GPU/battery.
    private val paused = AtomicBoolean(false)
    private var sceneRadius = 10.0
    private var sceneCenter = Vec3.ZERO
    private var lastCameraPosition = Vec3.ZERO
    private var lastCameraUp = Vec3(0.0, 1.0, 0.0)  // Per v0.7.0: camera local +Y in world
    private var sceneBounds: SceneBounds? = null
    private var allSceneBounds: SceneBounds? = null
    private var sceneSubmissions = 0L
    private var interactionUpdates = 0L
    private var framesRendered = 0L
    private var depthPointsEvaluated = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        MaterialFactory.requirePayloads(appContext)
        Filament.init()
        engine = Engine.create(Engine.Backend.DEFAULT)
        renderer = engine.createRenderer()
        filamentScene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        // Atoms are lit (PBR) materials, so the scene needs a real light. The
        // directional light is anchored to the camera (view-space fixed direction) and a
        // constant indirect light prevents the unlit side from going pure black.
        lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .castLight(true)
            .castShadows(false)
            .build(engine, lightEntity)
        filamentScene.addEntity(lightEntity)
        // Scene light model: WorldLight.AMBIENT_RATIO (50%) ambient carried by the
        // IndirectLight + WorldLight.SUN_RATIO (50%) sun carried by the directional
        // light. Both intensities are refreshed in updateLightingAndDepth from the
        // world light. The 0.8 SH irradiance fills the shadow side of lit atoms.
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.8f, 0.8f, 0.8f))
            .intensity(ambientLightIntensityLux())
            .build(engine)
        filamentScene.indirectLight = indirectLight
        view.scene = filamentScene
        view.camera = camera
        view.setPostProcessingEnabled(false)
        configureAmbientOcclusion()
        meshUploader = MeshUploader(engine)
        materialFactory = MaterialFactory(appContext, engine)
        gpuInstances = GpuInstanceManager(engine, filamentScene, meshUploader, materialFactory)
    }

    override fun attach(surface: Surface) = onMain {
        checkOpen()
        detachInternal()
        require(surface.isValid) { "Filament surface is not valid" }
        // Request an sRGB swap chain so that linear clear colors and material outputs are
        // correctly encoded to the display color space, matching offscreen export.
        val flags = if (SwapChain.isSRGBSwapChainSupported(engine)) SwapChainFlags.CONFIG_SRGB_COLORSPACE else 0L
        swapChain = engine.createSwapChain(surface, flags)
        requestFrames(3)
    }

    override fun detach() = onMain { detachInternal() }

    override fun submit(scene: RenderScene) {
        checkOpen()
        // v0.7.0: bond radius is applied as-is (the old 0.5× Filament scaling was
        // removed; the UI slider max/default were halved to compensate).
        if (submittedScene === scene) return
        submittedScene = scene
        sceneSubmissions++
        gpuInstances.sync(scene)
        gpuInstances.updateInteraction(scene, interaction)
        pickingRenderer.submit(scene)
        sceneBounds = scene.visibleBounds()
        allSceneBounds = scene.allBounds()
        sceneCenter = allSceneBounds?.center ?: sceneBounds?.center ?: Vec3.ZERO
        sceneRadius = sceneBounds?.radius ?: 10.0
        updateClearColor(scene)
        updateLightingAndDepth()
        updateCamera()
        // v0.7.0: one redundant frame is enough on scene submit (the visible frame plus a
        // single follow-up); the previous 3-frame burst was unnecessary GPU work.
        requestFrames(2)
    }

    /**
     * Called immediately from [SurfaceHolder.Callback.surfaceChanged] to update the viewport
     * and camera projection without waiting for the state pipeline (which takes at least one
     * frame to process [ViewerCommand.SetViewport]). This prevents the brief but persistent
     * aspect-ratio distortion that occurs when the surface size changes (e.g. screen rotation)
     * but [view.viewport] still holds the old dimensions.
     */
    fun onSurfaceChanged(width: Int, height: Int) {
        if (closed.get()) return
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        updateCamera(w, h)
        requestFrames(1)
    }

    override fun updateInteraction(state: InteractionState) {
        checkOpen()
        if (interaction == state) return
        val previous = interaction
        interaction = state
        interactionUpdates++
        pickingRenderer.updateInteraction(state)
        val documentChanged = previous.document != state.document
        val cameraChanged = previous.session.camera != state.session.camera
        val viewportChanged = previous.session.viewportWidth != state.session.viewportWidth ||
            previous.session.viewportHeight != state.session.viewportHeight
        if (documentChanged) submittedScene?.let { gpuInstances.updateInteraction(it, state) }
        if (cameraChanged || viewportChanged) updateCamera()
        if (cameraChanged || documentChanged) updateLightingAndDepth()
        if (documentChanged || cameraChanged || viewportChanged) requestFrames(if (documentChanged) 2 else 1)
    }

    internal fun performanceSnapshot() = RendererPerformanceSnapshot(
        sceneSubmissions = sceneSubmissions,
        interactionUpdates = interactionUpdates,
        framesRendered = framesRendered,
        pendingFrames = frameBudget.pending,
        frameScheduled = frameScheduled,
        depthPointsEvaluated = depthPointsEvaluated,
    )

    override suspend fun pick(x: Float, y: Float): PickResult? {
        return pickingRenderer.pick(x, y)
    }

    override suspend fun renderToBitmap(
        width: Int,
        height: Int,
        msaaSamples: Int,
        background: ExportBitmapBackground,
    ): Bitmap? {
        // Per v0.6.3: when width/height are 0, use the live viewport dimensions so the
        // exported image matches the on-screen size at the same resolution.
        val actualWidth = if (width <= 0) interaction.session.viewportWidth.coerceIn(512, 4096) else width
        val actualHeight = if (height <= 0) interaction.session.viewportHeight.coerceIn(512, 4096) else height
        require(actualWidth in 1..4096 && actualHeight in 1..4096) { "export size must be between 1 and 4096" }
        // readPixels on a multisampled offscreen RenderTarget is not portable across Android GPU
        // drivers and can abort inside Filament. High quality is provided by supersampling instead.
        require(msaaSamples == 1) { "offscreen export does not support MSAA" }
        if (closed.get() || submittedScene == null) return null
        return suspendCoroutine { continuation ->
            // Per v0.7.0 (issue #9): every resume goes through this flag — a double resume
            // would throw IllegalStateException. The 12 s timeout also guarantees the caller
            // is resumed even when the readback callback never fires (GPU hang / renderer
            // death), which previously suspended the export coroutine forever.
            val completed = AtomicBoolean(false)
            val readbackCompleted = AtomicBoolean(false)
            var timeout: Runnable? = null
            fun resumeOnce(value: Bitmap?) {
                if (completed.compareAndSet(false, true)) {
                    timeout?.let(mainHandler::removeCallbacks)
                    continuation.resume(value)
                }
            }
            onMain {
                var exportSwapChain: SwapChain? = null
                var frameBegun = false
                val previousTarget = view.renderTarget
                val previousViewport = view.viewport
                fun restoreView() {
                    view.renderTarget = previousTarget
                    view.viewport = previousViewport
                    submittedScene?.let(::updateClearColor)
                    updateCamera(previousViewport.width, previousViewport.height)
                }
                fun destroyExportSwapChain() {
                    exportSwapChain?.let {
                        if (!closed.get()) engine.destroySwapChain(it)
                        exportSwapChain = null
                    }
                }
                timeout = Runnable {
                    android.util.Log.w("FilamentExport", "Pixel readback timed out after ${EXPORT_TIMEOUT_MS}ms")
                    if (!closed.get()) runCatching { restoreView() }
                    destroyExportSwapChain()
                    resumeOnce(null)
                }
                mainHandler.postDelayed(timeout!!, EXPORT_TIMEOUT_MS)
                try {
                    // A readable headless SwapChain follows Filament's normal frame lifecycle. This is
                    // more portable than renderStandaloneView + an attached texture RenderTarget.
                    exportSwapChain = engine.createSwapChain(
                        actualWidth,
                        actualHeight,
                        exportSwapChainFlags(SwapChain.isSRGBSwapChainSupported(engine)),
                    )
                    view.renderTarget = null
                    view.viewport = Viewport(0, 0, actualWidth, actualHeight)
                    updateExportClearColor(background)
                    updateCamera(actualWidth, actualHeight)
                    val pixels = ByteBuffer.allocateDirect(actualWidth * actualHeight * 4).order(ByteOrder.nativeOrder())
                    // Per v0.6.3: move pixel processing + overlay compositing to a background thread
                    // to prevent blocking the main thread (which caused "Skipped 76 frames!").
                    val descriptor = Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE).apply {
                        setCallback(mainHandler) {
                            timeout?.let(mainHandler::removeCallbacks)
                            if (completed.get()) {
                                destroyExportSwapChain()
                                return@setCallback
                            }
                            readbackCompleted.set(true)
                            restoreView()
                            destroyExportSwapChain()
                            requestFrames(1)
                            Thread {
                                try {
                                    pixels.rewind()
                                    val argb = rgbaBytesToArgb(pixels, actualWidth * actualHeight)
                                    // Bitmap creation on the background thread. Annotations (axes,
                                    // measurements, inspection panels, selection rings) are composed by
                                    // the app layer via ExportOverlay so the export matches the viewer.
                                    // The IntArray overload returns an immutable bitmap on Android.
                                    // ExportOverlay draws with Canvas, so allocate mutable storage and
                                    // copy the readback into it without creating another bitmap.
                                    val bitmap = Bitmap.createBitmap(actualWidth, actualHeight, Bitmap.Config.ARGB_8888)
                                    check(bitmap.isMutable) { "export bitmap must be mutable" }
                                    bitmap.setPixels(argb, 0, actualWidth, 0, 0, actualWidth, actualHeight)
                                    mainHandler.post { resumeOnce(bitmap) }
                                } catch (t: Throwable) {
                                    mainHandler.post {
                                        android.util.Log.w("FilamentExport", "Creating export bitmap failed", t)
                                        destroyExportSwapChain()
                                        resumeOnce(null)
                                    }
                                } finally {
                                    // Double insurance: any path (including Error) resumes the caller.
                                    mainHandler.post { resumeOnce(null) }
                                }
                            }.start()
                    }
                }
                    var readbackIssued = false
                    fun pumpExportFrame() {
                        if (completed.get() || readbackCompleted.get() || closed.get()) return
                        try {
                            val chain = checkNotNull(exportSwapChain)
                            if (renderer.beginFrame(chain, Engine.getSteadyClockTimeNano())) {
                                frameBegun = true
                                renderer.render(view)
                                if (!readbackIssued) {
                                    renderer.readPixels(0, 0, actualWidth, actualHeight, descriptor)
                                    readbackIssued = true
                                }
                                renderer.endFrame()
                                frameBegun = false
                            }
                            if (!completed.get() && !readbackCompleted.get()) {
                                mainHandler.postDelayed(::pumpExportFrame, 16L)
                            }
                        } catch (t: Throwable) {
                            if (frameBegun) runCatching { renderer.endFrame() }
                            frameBegun = false
                            if (!closed.get()) runCatching { restoreView() }
                            destroyExportSwapChain()
                            android.util.Log.w("FilamentExport", "Offscreen export frame failed", t)
                            resumeOnce(null)
                        }
                    }
                    pumpExportFrame()
                } catch (t: Throwable) {
                    if (frameBegun) runCatching { renderer.endFrame() }
                    if (!closed.get()) runCatching { restoreView() }
                    destroyExportSwapChain()
                    android.util.Log.w("FilamentExport", "Offscreen export failed", t)
                    resumeOnce(null)
                }
            }
        }
    }

    override fun clear() {
        submittedScene = null
        sceneBounds = null
        allSceneBounds = null
        sceneCenter = Vec3.ZERO
        sceneRadius = 10.0
        gpuInstances.clear()
        pickingRenderer.clear()
        requestFrames(1)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (closed.get()) return
        val chain = swapChain ?: return
        if (!frameBudget.hasPending) return
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            framesRendered++
            frameBudget.rendered()
        }
        if (frameBudget.hasPending && swapChain != null) scheduleFrame()
    }

    override fun close() = onMain {
        if (!closed.compareAndSet(false, true)) return@onMain
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        frameBudget.reset()
        detachInternal()
        gpuInstances.close()
        materialFactory.close()
        meshUploader.close()
        engine.destroyIndirectLight(indirectLight)
        filamentScene.removeEntity(lightEntity)
        engine.destroyEntity(lightEntity)
        EntityManager.get().destroy(lightEntity)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(filamentScene)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }

    private fun updateCamera(
        width: Int = interaction.session.viewportWidth.coerceAtLeast(1),
        height: Int = interaction.session.viewportHeight.coerceAtLeast(1),
    ) {
        val state = interaction.session
        view.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height
        // The ortho span comes from the shared SceneProjection so the rendered image, the 2D
        // overlay, bitmap export and CPU picking always agree on where an atom lands.
        val span = submittedScene?.sceneProjection(state.camera, width, height)?.span
            ?: (sceneRadius / state.camera.zoom)
        camera.setProjection(Camera.Projection.ORTHO, -span * aspect, span * aspect, -span, span, -1000.0, 1000.0)
        camera.setShift(state.camera.panX / width, -state.camera.panY / height)
        val worldFromCamera = state.camera.rotation.transposed()
        val target = sceneCenter + state.camera.target
        val eye = target + worldFromCamera * Vec3(0.0, 0.0, max(50.0, sceneRadius * 4.0))
        lastCameraPosition = eye
        val up = worldFromCamera * Vec3(0.0, 1.0, 0.0)
        lastCameraUp = up  // Per v0.7.0: for billboard roll anchoring
        camera.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, up.x, up.y, up.z)
    }

    private fun updateLightingAndDepth() {
        val scene = submittedScene ?: return
        // Filament uses the opposite screen-plane sign from the custom shader while
        // retaining +Z for this camera setup; worldLightManagerDirection applies that
        // device-calibrated conversion.
        val light = scene.environment.worldLight
        val worldDir = worldLightManagerDirection(
            light.azimuthDegrees,
            light.elevationDegrees,
            interaction.session.camera.rotation,
        )
        val lightInstance = engine.lightManager.getInstance(lightEntity)
        engine.lightManager.setDirection(
            lightInstance,
            worldDir.x.toFloat(),
            worldDir.y.toFloat(),
            worldDir.z.toFloat(),
        )
        // The sun carries SUN_RATIO (50%) of the total light; the ambient share lives on
        // the IndirectLight. With post-processing disabled (no tone mapping) this stays
        // well below noon-sun so the PBR highlight never clips to pure white.
        engine.lightManager.setIntensity(
            lightInstance,
            worldLightIntensityLux(light.intensity) * WorldLight.SUN_RATIO,
        )
        indirectLight.setIntensity(ambientLightIntensityLux())
        // Depth-cueing range is derived from the visible-atoms AABB, not the preloaded
        // neighbor-cell shell. +3 maps to the nearest visible corner, -3 to the farthest.
        val visibleBounds = scene.visibleBounds()
        val depthRange = visibleBounds.toCameraDepthRange(interaction.session.camera)
        val visibleCorners = visibleBounds?.corners.orEmpty()
        depthPointsEvaluated = visibleCorners.size
        if (depthRange != null && visibleBounds != null) {
            // toCameraDepthRange operates in the camera-aligned frame anchored at the visible
            // bounds center. Filament's view-space Z additionally contains the camera distance
            // offset used in updateCamera(), so shift the range by the same amount to keep the
            // normalized depth scale aligned with the actual rendered view depths.
            val cameraDistance = max(50.0, sceneRadius * 4.0)
            val target = sceneCenter + interaction.session.camera.target
            val centerShift = (interaction.session.camera.rotation * (visibleBounds.center - target)).z
            val near = (depthRange.near - cameraDistance + centerShift).toFloat()
            val far = (depthRange.far - cameraDistance + centerShift).toFloat()
            materialFactory.updateDepthCueing(scene.environment, near, far)
        }
    }

    private fun configureAmbientOcclusion() {
        view.setAmbientOcclusionOptions(View.AmbientOcclusionOptions().apply {
            aoType = View.AmbientOcclusionOptions.AmbientOcclusionType.SAO
            enabled = true
            intensity = FIXED_AMBIENT_OCCLUSION_INTENSITY
            radius = 0.5f
            bias = 0.01f
            resolution = 0.5f
            quality = View.QualityLevel.MEDIUM
            lowPassFilter = View.QualityLevel.MEDIUM
            upsampling = View.QualityLevel.MEDIUM
        })
    }

    private fun updateClearColor(scene: RenderScene) {
        updateClearColor(scene.environment.backgroundArgb)
    }

    private fun updateExportClearColor(background: ExportBitmapBackground) {
        when (background) {
            ExportBitmapBackground.FollowScene -> submittedScene?.let(::updateClearColor)
            ExportBitmapBackground.Transparent -> updateClearColor(0L)
            is ExportBitmapBackground.Solid -> updateClearColor(background.argb or 0xFF000000L)
        }
    }

    private fun updateClearColor(argb: Long) {
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = backgroundColor(argb).toFilamentClearColor()
        }
    }

    private fun requestFrames(count: Int) = onMain {
        if (closed.get()) return@onMain
        frameBudget.request(count)
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!paused.get() && !frameScheduled && !closed.get() && swapChain != null) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Pauses frame scheduling when the app goes to the background. Pending frames are dropped
     * and any further [requestFrames] calls are ignored until [resume] — the Filament engine
     * stays alive but renders nothing, saving GPU/battery while the surface may still exist
     * (lock screen, split view, window covered).
     */
    fun pause() = onMain {
        if (closed.get()) return@onMain
        paused.set(true)
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        frameBudget.reset()
    }

    /** Resumes frame scheduling after [pause] and renders one refresh frame. */
    fun resume() = onMain {
        if (closed.get()) return@onMain
        paused.set(false)
        requestFrames(1)
    }

    private fun detachInternal() {
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        frameBudget.reset()
        swapChain?.let(engine::destroySwapChain)
        swapChain = null
    }

    private fun checkOpen() = check(!closed.get()) { "FilamentRenderer is closed" }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

private fun com.krystals.renderer.core.style.BackgroundColor.toFilamentClearColor(): DoubleArray {
    val alpha = (srgbArgb ushr 24 and 0xFFL).toDouble() / 255.0
    return doubleArrayOf(
        linearRgb[0].toDouble(),
        linearRgb[1].toDouble(),
        linearRgb[2].toDouble(),
        alpha,
    )
}
