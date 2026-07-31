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
import com.google.android.filament.LightManager
import com.google.android.filament.RenderTarget
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
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.SceneBounds
import com.krystals.renderer.core.scene.allBounds
import com.krystals.renderer.core.scene.sceneProjection
import com.krystals.renderer.core.scene.toCameraDepthRange
import com.krystals.renderer.core.scene.visibleBounds
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.SelectionColors
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

/** Single owner for Filament engine, surface, scene, resources and frame scheduling. */
class FilamentRenderer(context: Context) : FilamentSceneRenderer, Choreographer.FrameCallback {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val engine: Engine
    private val renderer: Renderer
    private val filamentScene: Scene
    private val view: View
    private val cameraEntity: Int
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
    private var sceneRadius = 10.0
    private var sceneCenter = Vec3.ZERO
    private var sceneBounds: SceneBounds? = null
    private var allSceneBounds: SceneBounds? = null
    private var bondValenceBySite: Map<String, Double> = emptyMap()
    private var sceneSubmissions = 0L
    private var interactionUpdates = 0L
    private var framesRendered = 0L
    private var depthPointsEvaluated = 0
    private val lightEntity: Int
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
        view.scene = filamentScene
        view.camera = camera
        view.setPostProcessingEnabled(false)
        meshUploader = MeshUploader(engine)
        materialFactory = MaterialFactory(appContext, engine)
        gpuInstances = GpuInstanceManager(engine, filamentScene, meshUploader, materialFactory)
        lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(0f, -1f, -1f)
            .intensity(40_000f)
            .castShadows(false)
            .build(engine, lightEntity)
        filamentScene.addEntity(lightEntity)
    }

    override fun attach(surface: Surface) = onMain {
        checkOpen()
        detachInternal()
        require(surface.isValid) { "Filament surface is not valid" }
        // Request an sRGB swap chain so that linear clear colors and material outputs are
        // correctly encoded to the display color space, matching the Legacy backend.
        val flags = if (SwapChain.isSRGBSwapChainSupported(engine)) SwapChainFlags.CONFIG_SRGB_COLORSPACE else 0L
        swapChain = engine.createSwapChain(surface, flags)
        requestFrames(3)
    }

    override fun detach() = onMain { detachInternal() }

    override fun submit(scene: RenderScene) {
        checkOpen()
        // 4× bond mapping: Filament multiplies bond radius by 0.5 (4× thinner than the
        // previous 2.0×) so that at the same slider value, bonds render thinner.
        val scaledScene = scene.copy(
            objects = scene.objects.map { obj ->
                if (obj is BondInstance) obj.copy(radius = obj.radius * 0.5) else obj
            }
        )
        if (submittedScene === scaledScene) return
        submittedScene = scaledScene
        sceneSubmissions++
        gpuInstances.sync(scaledScene)
        gpuInstances.updateInteraction(scaledScene, interaction)
        pickingRenderer.submit(scaledScene)
        sceneBounds = scaledScene.visibleBounds()
        allSceneBounds = scaledScene.allBounds()
        sceneCenter = allSceneBounds?.center ?: sceneBounds?.center ?: Vec3.ZERO
        sceneRadius = sceneBounds?.radius ?: 10.0
        updateClearColor(scaledScene)
        updateLightingAndDepth()
        updateCamera()
        requestFrames(3)
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

    fun updateOverlayData(bondValenceBySite: Map<String, Double>) {
        if (this.bondValenceBySite == bondValenceBySite) return
        this.bondValenceBySite = bondValenceBySite
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

    override suspend fun renderToBitmap(width: Int, height: Int, useMsaa: Boolean): Bitmap? {
        // Per v0.6.3: when width/height are 0, use the live viewport dimensions so the
        // exported image matches the on-screen size at the same resolution.
        val actualWidth = if (width <= 0) interaction.session.viewportWidth.coerceIn(512, 4096) else width
        val actualHeight = if (height <= 0) interaction.session.viewportHeight.coerceIn(512, 4096) else height
        require(actualWidth in 512..4096 && actualHeight in 512..4096) { "export size must be between 512 and 4096" }
        if (closed.get() || submittedScene == null) return null
        return suspendCoroutine { continuation ->
            onMain {
                runCatching {
                    // Secret: when useMsaa is true, re-enable the MSAA+FXAA path that produces
                    // a corrupted (blue-noise) image. This is the "broken export" easter egg.
                    if (useMsaa) {
                        view.setSampleCount(4)
                        view.setPostProcessingEnabled(true)
                        view.setAntiAliasing(View.AntiAliasing.FXAA)
                    }
                    val color = Texture.Builder().width(actualWidth).height(actualHeight).levels(1)
                        .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8)
                        .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.BLIT_SRC).build(engine)
                    val depth = Texture.Builder().width(actualWidth).height(actualHeight).levels(1)
                        .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.DEPTH24)
                        .usage(Texture.Usage.DEPTH_ATTACHMENT).build(engine)
                    val target = RenderTarget.Builder()
                        .texture(RenderTarget.AttachmentPoint.COLOR, color)
                        .texture(RenderTarget.AttachmentPoint.DEPTH, depth)
                        .build(engine)
                    val previousTarget = view.renderTarget
                    val previousViewport = view.viewport
                    view.renderTarget = target
                    view.viewport = Viewport(0, 0, actualWidth, actualHeight)
                    updateCamera(actualWidth, actualHeight)
                    renderer.renderStandaloneView(view)
                    val pixels = ByteBuffer.allocateDirect(actualWidth * actualHeight * 4).order(ByteOrder.nativeOrder())
                    // Per v0.6.3: move pixel processing + composeOverlay to a background thread
                    // to prevent blocking the main thread (which caused "Skipped 76 frames!").
                    val descriptor = Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE).apply {
                        setCallback(mainHandler) {
                            Thread {
                                try {
                                    val argb = IntArray(actualWidth * actualHeight)
                                    pixels.rewind()
                                    for (sourceY in 0 until actualHeight) {
                                        val destinationY = sourceY
                                        for (xIndex in 0 until actualWidth) {
                                            val r = pixels.get().toInt() and 0xFF
                                            val g = pixels.get().toInt() and 0xFF
                                            val b = pixels.get().toInt() and 0xFF
                                            val a = pixels.get().toInt() and 0xFF
                                            argb[destinationY * actualWidth + xIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
                                        }
                                    }
                                    // Restore view on the main thread (Filament requires it).
                                    mainHandler.post {
                                        view.renderTarget = previousTarget
                                        view.viewport = previousViewport
                                        updateCamera(previousViewport.width, previousViewport.height)
                                        if (useMsaa) {
                                            view.setSampleCount(1)
                                            view.setPostProcessingEnabled(false)
                                            view.setAntiAliasing(View.AntiAliasing.NONE)
                                        }
                                        engine.destroyRenderTarget(target)
                                        engine.destroyTexture(depth)
                                        engine.destroyTexture(color)
                                        requestFrames(1)
                                    }
                                    // Bitmap creation + overlay compositing on background thread.
                                    val sourceBitmap = Bitmap.createBitmap(argb, actualWidth, actualHeight, Bitmap.Config.ARGB_8888)
                                    val bitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                    sourceBitmap.recycle()
                                    composeOverlay(bitmap)
                                    mainHandler.post { continuation.resume(bitmap) }
                                } catch (e: Exception) {
                                    mainHandler.post { continuation.resume(null) }
                                }
                            }.start()
                        }
                    }
                    renderer.readPixels(target, 0, 0, actualWidth, actualHeight, descriptor)
                }.onFailure {
                    if (useMsaa) {
                        view.setSampleCount(1)
                        view.setPostProcessingEnabled(false)
                        view.setAntiAliasing(View.AntiAliasing.NONE)
                    }
                    continuation.resume(null)
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
        filamentScene.removeEntity(lightEntity)
        engine.destroyEntity(lightEntity)
        EntityManager.get().destroy(lightEntity)
        materialFactory.close()
        meshUploader.close()
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
        val up = worldFromCamera * Vec3(0.0, 1.0, 0.0)
        camera.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, up.x, up.y, up.z)
    }

    private fun updateLightingAndDepth() {
        val scene = submittedScene ?: return
        val light = scene.environment.worldLight
        // Per v0.6.5: theta (azimuth) around camera forward (+Z); phi (elevation) from viewing axis.
        val theta = light.azimuthDegrees / 180.0 * PI
        val phi = light.elevationDegrees / 180.0 * PI
        // Per v0.6.3: flip phi so 0-90° elevation means light moves closer to camera.
        // Old: (sin(φ)·cos(θ), sin(φ)·sin(θ), cos(φ)) — 0°=at camera, 90°=at horizon
        // New: (cos(φ)·cos(θ), cos(φ)·sin(θ), sin(φ)) — 0°=at horizon, 90°=at camera
        val cosPhi = cos(phi)
        val surfaceToLight = Vec3(cosPhi * cos(theta), cosPhi * sin(theta), -sin(phi))
        val travelDir = surfaceToLight * -1.0
        val worldDirection = interaction.session.camera.rotation.transposed() * travelDir
        val lightInstance = engine.lightManager.getInstance(lightEntity)
        engine.lightManager.setDirection(lightInstance, worldDirection.x.toFloat(), worldDirection.y.toFloat(), worldDirection.z.toFloat())
        engine.lightManager.setIntensity(lightInstance, (light.intensity * 100_000f).coerceAtLeast(1f))

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

    private fun composeOverlay(bitmap: Bitmap) {
        val scene = submittedScene ?: return
        val atoms = scene.atoms.associateBy { it.atom.id }
        if (atoms.isEmpty()) return
        val state = interaction.document
        val cameraState = interaction.session.camera
        val projection = scene.sceneProjection(cameraState, bitmap.width, bitmap.height)
        fun project(id: Long): Pair<Float, Float>? {
            val atom = atoms[id]?.atom ?: return null
            val (px, py) = projection.project(atom.cartesianCoordinate.toVec3())
            return px.toFloat() to py.toFloat()
        }
        val canvas = Canvas(bitmap)
        val scale = (bitmap.width / 1080f).coerceIn(0.5f, 1.0f)
        val measurementPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f * scale
            setShadowLayer(5f * scale, scale, scale, Color.BLACK)
        }
        val measurements = buildList {
            state.lockedMeasurements.forEach { add(it to true) }
            if (state.measurementMode != MeasurementMode.NONE && state.selection.selectedAtomIds.isNotEmpty()) {
                add(com.krystals.interaction.measure.MeasurementSelection(state.selection.selectedAtomIds, state.measurementMode) to false)
            }
        }
        measurements.forEach { (selection, locked) ->
            val expected = when (selection.mode) {
                MeasurementMode.LENGTH -> 2
                MeasurementMode.ANGLE -> 3
                MeasurementMode.DIHEDRAL -> 4
                else -> 0
            }
            if (expected == 0 || selection.atomIds.size < expected) return@forEach
            val selectedIds = selection.atomIds.takeLast(expected)
            val coordinates = selectedIds.mapNotNull { atoms[it]?.atom?.cartesianCoordinate?.toVec3() }
            val projected = selectedIds.mapNotNull(::project)
            if (coordinates.size != expected || projected.size != expected) return@forEach
            // Per v0.6: dihedral plane gradient overlay (matches Legacy renderer's LinearGradient).
            if (selection.mode == MeasurementMode.DIHEDRAL) {
                DihedralTool.planes(coordinates[0], coordinates[1], coordinates[2], coordinates[3]).forEach { plane ->
                    val sv = plane.vertices.map { v ->
                        val (px, py) = projection.project(v)
                        px.toFloat() to py.toFloat()
                    }
                    if (sv.size == 4) {
                        val path = Path().apply { moveTo(sv[0].first, sv[0].second); sv.drop(1).forEach { lineTo(it.first, it.second) }; close() }
                        val shader = LinearGradient(sv[0].first, sv[0].second, sv[3].first, sv[3].second,
                            intArrayOf(Color.argb(112, 150, 95, 205), Color.argb(56, 128, 72, 180), Color.TRANSPARENT),
                            null, Shader.TileMode.CLAMP)
                        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
                    }
                }
            }
            val label = when (selection.mode) {
                MeasurementMode.LENGTH -> "%.4f \u00C5".format(DistanceTool.calculate(coordinates[0], coordinates[1]))
                MeasurementMode.ANGLE -> "%.3f\u00B0".format(AngleTool.calculate(coordinates[0], coordinates[1], coordinates[2]))
                MeasurementMode.DIHEDRAL -> "%.3f\u00B0".format(DihedralTool.calculate(coordinates[0], coordinates[1], coordinates[2], coordinates[3]))
                else -> return@forEach
            }
            val anchorX = projected.map { it.first }.average().toFloat()
            val anchorY = projected.map { it.second }.average().toFloat()
            val bounds = android.graphics.Rect()
            measurementPaint.getTextBounds(label, 0, label.length, bounds)
            val pad = 16f * scale
            val left = anchorX + 12f * scale - pad
            val top = anchorY - 12f * scale - bounds.height() - pad
            val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (locked) Color.argb(209, 153, 102, 204) else Color.argb(166, 0, 0, 0)
            }
            canvas.drawRoundRect(left, top, anchorX + 12f * scale + bounds.width() + pad, anchorY - 12f * scale + pad, 14f * scale, 14f * scale, panel)
            canvas.drawText(label, anchorX + 12f * scale, anchorY - 12f * scale, measurementPaint)
        }
        val inspectionIds = state.inspection.lockedInspectedAtomIds + listOfNotNull(state.inspection.inspectedAtomId).filterNot { it in state.inspection.lockedInspectedAtomIds }
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f * scale
            setShadowLayer(5f * scale, scale, scale, Color.BLACK)
        }
        inspectionIds.forEach { id ->
            val atomInstance = atoms[id] ?: return@forEach
            val atom = atomInstance.atom
            val anchor = project(id) ?: return@forEach
            val locked = id in state.inspection.lockedInspectedAtomIds
            // Per v0.6.5: find co-located atoms (same fractional coordinate) and display them stacked.
            val coLocated = scene.atoms.filter { it.atom.id != atom.id && it.atom.fractionalCoordinate == atom.fractionalCoordinate }
            val allAtoms = listOf(atom) + coLocated.map { it.atom }
            val atomBlocks = allAtoms.map { a ->
                val bvs = bondValenceBySite[a.siteId]?.let { "  s = %.2f".format(it) }.orEmpty()
                val fractional = a.fractionalCoordinate
                listOf(
                    "${a.species.symbol}  ${a.siteLabel}  occ ${a.occupancy}$bvs",
                    "(${fractional.x.formatFract()}, ${fractional.y.formatFract()}, ${fractional.z.formatFract()})",
                )
            }
            val allLines = atomBlocks.flatMapIndexed { i, block ->
                if (i > 0) listOf("---") + block else block
            }
            val lineHeight = infoPaint.fontMetrics.run { descent - ascent }
            val dividerHeight = lineHeight * 0.3f
            val maxWidth = allLines.maxOf(infoPaint::measureText)
            val pad = 16f * scale
            val atomRadius = projection.screenRadius(atomInstance.radius).toFloat()
            val totalHeight = allLines.size * lineHeight + (atomBlocks.size - 1) * dividerHeight
            val left = anchor.first + atomRadius + 14f * scale
            val top = anchor.second - atomRadius - 14f * scale - totalHeight - pad
            val bottom = anchor.second - atomRadius - 14f * scale + pad
            val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (locked) Color.argb(209, 153, 102, 204) else Color.argb(166, 0, 0, 0)
            }
            canvas.drawRoundRect(left, top, left + maxWidth + pad * 2f, bottom, 14f * scale, 14f * scale, panel)
            var currentY = top + pad + lineHeight - infoPaint.fontMetrics.descent
            allLines.forEach { line ->
                canvas.drawText(line, left + pad, currentY, infoPaint)
                currentY += if (line == "---") dividerHeight + lineHeight else lineHeight
            }
        }
        // Selection rings follow Filament's visual sphere radius (world radius × screen scale)
        // without Legacy's pixel clamp, so exported rings hug the rendered sphere at any zoom.
        val lockedIds = state.lockedMeasurements.flatMap { it.atomIds }.toSet() + state.inspection.lockedInspectedAtomIds
        val highlightedIds = state.selection.selectedAtomIds.toSet() + lockedIds + listOfNotNull(state.inspection.inspectedAtomId)
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        highlightedIds.forEach { id ->
            val atomInstance = atoms[id] ?: return@forEach
            val anchor = project(id) ?: return@forEach
            val isLocked = id in lockedIds
            ringPaint.color = if (isLocked) SelectionColors.LOCKED_ARGB.toInt() else SelectionColors.SELECTED_ARGB.toInt()
            ringPaint.strokeWidth = (if (isLocked) 6f else 5f) * scale
            val r = projection.screenRadius(atomInstance.radius).toFloat()
            canvas.drawCircle(anchor.first, anchor.second, r + 4f * scale, ringPaint)
        }
        if (scene.environment.axes.visible) drawAxesOverlay(canvas, scene, bitmap.width, bitmap.height, scale)
    }

    private fun drawAxesOverlay(canvas: Canvas, scene: RenderScene, width: Int, height: Int, scale: Float) {
        val matrix = scene.structure.lattice.matrix
        val directions = when (scene.environment.axes.mode) {
            AxisMode.ABC -> listOf(matrix.a, matrix.b, matrix.c)
            AxisMode.XYZ -> listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        }
        val labels = if (scene.environment.axes.mode == AxisMode.ABC) listOf("a", "b", "c") else listOf("X", "Y", "Z")
        val colors = intArrayOf(0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt())
    val originX = width * scene.environment.axes.offsetX + 28f * scale
    val originY = height * scene.environment.axes.offsetY + 40f * scale - 75f * scale
        val maxArrowLength = 75f * scale
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f * scale
            clearShadowLayer()
        }
        // Pre-compute rotated directions for depth sorting.
        val rotatedDirs = directions.mapIndexed { index, direction ->
            val rotated = interaction.session.camera.rotation * direction.normalized()
            Triple(index, direction, rotated)
        }
        fun drawArrow(index: Int, direction: Vec3, rotated: Vec3) {
            val dx = rotated.x.toFloat()
            val dy = -rotated.y.toFloat()
            val projectedLength = kotlin.math.sqrt(dx * dx + dy * dy)
            val visibleLength = maxArrowLength * projectedLength
            val ux = if (projectedLength > 0.0001f) dx / projectedLength else 0f
            val uy = if (projectedLength > 0.0001f) dy / projectedLength else 0f
            // Arrow starts 12f from center along the arrow direction.
            val hubR = 12f * scale
            val startX = originX + ux * hubR
            val startY = originY + uy * hubR
            val tipX = originX + ux * (hubR + visibleLength)
            val tipY = originY + uy * (hubR + visibleLength)
            val headLength = 14f * scale
            val baseX = tipX - ux * headLength
            val baseY = tipY - uy * headLength
            val shaft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[index]; strokeWidth = 5f * scale; strokeCap = Paint.Cap.ROUND }
            canvas.drawLine(startX, startY, baseX, baseY, shaft)
            val perpendicularX = -uy
            val perpendicularY = ux
            val halfHead = headLength * 0.6f
            val arrow = Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseX + perpendicularX * halfHead, baseY + perpendicularY * halfHead)
                lineTo(baseX - perpendicularX * halfHead, baseY - perpendicularY * halfHead)
                close()
            }
            canvas.drawPath(arrow, shaft)
            labelPaint.color = colors[index]
            canvas.drawText(labels[index], tipX + 4f * scale, tipY - 4f * scale, labelPaint)
        }
        // Draw back arrows (pointing away from viewer) first so the center sphere
        // correctly occludes them.
        rotatedDirs.filter { it.third.z <= 0.0 }.forEach { (index, direction, rotated) ->
            drawArrow(index, direction, rotated)
        }
        // Per v0.6.3: gray sphere at the origin (vertex of the three arrows).
        val light = scene.environment.worldLight
        val theta = light.azimuthDegrees / 180f * PI.toFloat()
        val phi = light.elevationDegrees / 180f * PI.toFloat()
        val hubRadius = 12f * scale
        val highlightX = originX + (cos(phi) * cos(theta) * hubRadius * 0.375f)
        val highlightY = originY - (cos(phi) * sin(theta) * hubRadius * 0.375f)
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(highlightX, highlightY, hubRadius, intArrayOf(0xFFE0E0E0.toInt(), 0xFF68686F.toInt()), null, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(originX, originY, hubRadius, centerPaint)
        // Draw front arrows (pointing toward the viewer) on top of the sphere.
        rotatedDirs.filter { it.third.z > 0.0 }.forEach { (index, direction, rotated) ->
            drawArrow(index, direction, rotated)
        }
    }

    private fun Double.formatFract() = "%.4f".format(this)

    private fun updateClearColor(scene: RenderScene) {
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = backgroundColor(scene.environment.backgroundArgb).toFilamentClearColor()
        }
    }

    private fun requestFrames(count: Int) = onMain {
        if (closed.get()) return@onMain
        frameBudget.request(count)
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!frameScheduled && !closed.get() && swapChain != null) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
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
