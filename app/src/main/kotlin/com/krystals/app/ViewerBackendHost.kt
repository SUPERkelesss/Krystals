package com.krystals.app

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.krystals.app.ui.AppPalette
import com.krystals.crystal.core.model.AtomImage
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerCommand
import com.krystals.interaction.measure.MeasurementSelection
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.GatheredAtomGrouper
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.SceneBounds
import com.krystals.renderer.core.scene.allBounds
import com.krystals.renderer.core.scene.sceneProjection
import com.krystals.renderer.core.style.SelectionColors
import com.krystals.renderer.filament.FilamentRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

private class OverlayHitRegions {
    var measurements: List<Triple<Rect, MeasurementSelection?, Boolean>> = emptyList()
    var inspections: List<Triple<Rect, Long, Boolean>> = emptyList()
}

private class LastTap(var timeMillis: Long = 0L, var position: Offset = Offset.Unspecified)

private data class OverlaySceneCache(
    val bounds: SceneBounds?,
    val atomsById: Map<Long, AtomInstance>,
)

internal fun Modifier.filamentViewerGestures(
    key: Any,
    isLocked: () -> Boolean,
    onCommand: (ViewerCommand) -> Unit,
    onTap: (Offset) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val down = first.position
        var moved = false
        while (true) {
            // SurfaceView is an Android child view and may consume move events during the
            // Main pass. Read the gesture in Initial so orbit/pan/zoom still receive deltas
            // regardless of which native child handled the touch sequence.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            val movement = event.changes.sumOf {
                (it.position - it.previousPosition).getDistance().toDouble()
            }
            if (movement > 2.0) moved = true
            var handled = false
            if (!isLocked()) {
                if (pressed.size == 1) {
                    val delta = pressed.first().position - pressed.first().previousPosition
                    if (delta.getDistance() > 0f) {
                        onCommand(ViewerCommand.Orbit(-delta.x, -delta.y))
                        handled = true
                    }
                } else if (pressed.size >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (abs(zoom - 1f) > 0.001f) onCommand(ViewerCommand.Zoom(zoom))
                    if (pan.getDistance() > 0.5f) onCommand(ViewerCommand.Pan(pan.x, pan.y))
                    handled = true
                }
            }
            // Per v0.7.1: only consume events we actually handled (rotation/zoom/pan).
            // Unconditionally consuming ALL events (including UP/CANCEL) breaks the
            // system velocity tracker on Huawei devices, triggering the
            // getSplineFlingDurationByReflection exception.
            if (handled) {
                event.changes.forEach { it.consume() }
            }
            if (pressed.isEmpty()) {
                if (!moved) onTap(down)
                break
            }
        }
    }
}

@Composable
fun FilamentViewport(
    renderer: FilamentRenderer,
    scene: RenderScene,
    interactionState: InteractionState,
    onCommand: (ViewerCommand) -> Unit,
    onAtomTap: (AtomImage) -> Boolean,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    bondValenceBySite: Map<String, Double> = emptyMap(),
) {
    val scope = rememberCoroutineScope()
    val hitRegions = remember { OverlayHitRegions() }
    val lastTap = remember { LastTap() }
    val currentScene = rememberUpdatedState(scene)
    val currentInteraction = rememberUpdatedState(interactionState)
    val currentOnCommand = rememberUpdatedState(onCommand)
    val currentOnAtomTap = rememberUpdatedState(onAtomTap)

    LaunchedEffect(renderer, scene) { renderer.submit(scene) }
    LaunchedEffect(renderer, interactionState) { renderer.updateInteraction(interactionState) }
    // Per v0.7.0: pause frame scheduling while the app is in the background (the Surface may
    // survive lock screen / split view) and resume with one refresh frame on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(renderer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> renderer.pause()
                Lifecycle.Event.ON_START -> renderer.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { onCommand(ViewerCommand.SetViewport(it.width, it.height)) }
            .filamentViewerGestures(
                key = renderer,
                isLocked = { currentInteraction.value.session.locked },
                onCommand = { currentOnCommand.value(it) },
                onTap = { down ->
                    val measurementHit = hitRegions.measurements.firstOrNull { it.first.contains(down) }
                    val inspectionHit = hitRegions.inspections.firstOrNull { it.first.contains(down) }
                    when {
                        measurementHit != null -> currentOnCommand.value(
                            ViewerCommand.ToggleMeasurementLock(measurementHit.second, measurementHit.third),
                        )
                        inspectionHit != null -> currentOnCommand.value(
                            ViewerCommand.ToggleInspectionLock(inspectionHit.second, inspectionHit.third),
                        )
                        else -> scope.launch {
                            runCatching {
                                val hit = renderer.pick(down.x, down.y)
                                val atom = hit?.atomId?.let { id ->
                                    currentScene.value.atoms.firstOrNull { it.atom.id == id }?.atom
                                }
                                if (atom == null) {
                                    currentOnCommand.value(ViewerCommand.ClearTransientViewerState)
                                } else {
                                    val now = SystemClock.uptimeMillis()
                                    val isDoubleTap = now - lastTap.timeMillis < 300L &&
                                        lastTap.position != Offset.Unspecified &&
                                        (down - lastTap.position).getDistance() < 24f
                                    lastTap.timeMillis = now
                                    lastTap.position = down
                                    if (isDoubleTap) {
                                        currentOnCommand.value(ViewerCommand.InspectAtom(atom.id))
                                    } else if (!currentOnAtomTap.value(atom)) {
                                        currentOnCommand.value(ViewerCommand.SelectAtom(atom.id, atom.siteId))
                                    }
                                }
                            }.onFailure { error ->
                                if (error !is CancellationException) onFailure(error)
                            }
                        }
                    }
                },
            ),
    ) {
        AndroidView(
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            runCatching { renderer.attach(holder.surface) }.onFailure(onFailure)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // Per v0.7.0: update the viewport + camera immediately to prevent the
                            // aspect-ratio distortion that occurs when the surface size changes
                            // (e.g. screen rotation) but the old viewport dimensions linger for
                            // one or more frames until the state pipeline processes SetViewport.
                            renderer.onSurfaceChanged(width, height)
                            onCommand(ViewerCommand.SetViewport(width, height))
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            runCatching { renderer.detach() }.onFailure(onFailure)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        FilamentLegacyStyleOverlay(scene, interactionState, bondValenceBySite, hitRegions)
    }
}

@Composable
private fun FilamentLegacyStyleOverlay(
    scene: RenderScene,
    state: InteractionState,
    bondValenceBySite: Map<String, Double>,
    hitRegions: OverlayHitRegions,
) {
    val cache = remember(scene) {
        OverlaySceneCache(
            bounds = scene.allBounds(),
            atomsById = scene.atoms.asSequence().filter { it.visible }.associateBy { it.atom.id },
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        cache.bounds ?: return@Canvas
        val atomsById = cache.atomsById
        val camera = state.session.camera
        // Project through the shared SceneProjection so the overlay, picking and the Filament
        // camera always agree (matches the Legacy renderer's framing).
        val projection = scene.sceneProjection(camera, size.width.toInt(), size.height.toInt())
        val scale = projection.screenScale().toFloat()
        fun point(id: Long): Offset? = atomsById[id]?.let { atom ->
            val (px, py) = projection.project(atom.atom.cartesianCoordinate.toVec3())
            Offset(px.toFloat(), py.toFloat())
        }
        val lockedIds = state.document.lockedMeasurements.flatMap { it.atomIds }.toSet() + state.document.inspection.lockedInspectedAtomIds

        if (scene.environment.axes.visible) {
            // Shared native-Canvas axes drawing (same code path as ExportOverlay).
            OverlayDraw.drawAxes(
                drawContext.canvas.nativeCanvas,
                size.width.toInt(),
                size.height.toInt(),
                scene,
                state,
                projection,
            )
        }

        // P8: 2D selection rings (replaces 3D highlight spheres from GpuInstanceManager).
        // Draw rings around selected, locked, and inspected atoms — same projection as Legacy.
        // The ring radius tracks Filament's visual sphere (world radius × screen scale) without
        // Legacy's 4.5..42 px pixel clamp, so the ring hugs the rendered sphere at every zoom.
        val highlightedIds = state.document.selection.selectedAtomIds.toSet() + lockedIds +
            listOfNotNull(state.document.inspection.inspectedAtomId)
        highlightedIds.forEach { atomId ->
            val atom = atomsById[atomId] ?: return@forEach
            val center = point(atomId) ?: return@forEach
            val r = (atom.radius * scale).toFloat()
            val isLocked = atomId in lockedIds
            val ringColor = if (isLocked) Color(SelectionColors.LOCKED_ARGB) else Color(SelectionColors.SELECTED_ARGB)
            drawCircle(ringColor, r + 4f, center, style = Stroke(if (isLocked) 6f else 5f))
        }

        // Shared native-Canvas measurement drawing (same code path as ExportOverlay); the hit
        // regions are converted back to Compose Rects for tap-hit detection.
        val measurementHits = OverlayDraw.drawMeasurements(
            drawContext.canvas.nativeCanvas,
            scene,
            state,
            projection,
        )
        hitRegions.measurements = measurementHits.map { hit ->
            val b = hit.bounds
            Triple(Rect(b.left, b.top, b.right, b.bottom), hit.selection, hit.locked)
        }

        val inspectionBounds = mutableListOf<Triple<Rect, Long, Boolean>>()
        val inspectionIds = state.document.inspection.lockedInspectedAtomIds + listOfNotNull(state.document.inspection.inspectedAtomId).filterNot { it in state.document.inspection.lockedInspectedAtomIds }
        val infoPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
        }
        // Per v0.7.0: compute gathered group membership from the AtomInstance atoms.
        val atomImages = atomsById.values.map { it.atom }
        val colorBySite = atomsById.values.associate { it.atom.siteId to it.material.argb }
        val groupByMemberId = GatheredAtomGrouper.groupByAtomId(atomImages, colorBySite)

        inspectionIds.forEach { id ->
            val atom = atomsById[id]?.atom ?: return@forEach
            val anchor = point(id) ?: return@forEach
            val locked = id in state.document.inspection.lockedInspectedAtomIds
            // Per v0.7.0: if atom belongs to a gathered group, show up to 3 members.
            val group = groupByMemberId[id]
            val displayIds = group?.memberAtomIds ?: listOf(id)
            val displayMembers = displayIds.take(3).mapNotNull { mid -> atomsById[mid]?.atom }
            if (displayMembers.isEmpty()) return@forEach
            val lines = displayMembers.flatMapIndexed { index, m ->
                val valence = bondValenceBySite[m.siteId]?.let { "  s = %.2f".format(it) }.orEmpty()
                val f = m.fractionalCoordinate
                val memberLines = listOf(
                    "${m.species.symbol}  ${m.siteLabel}  occ ${m.occupancy}$valence",
                    "(${f.x.formatFract()}, ${f.y.formatFract()}, ${f.z.formatFract()})",
                )
                if (index > 0) listOf("---") + memberLines else memberLines
            } + if (displayIds.size > 3) listOf("...") else emptyList()
            val maxWidth = lines.maxOf(infoPaint::measureText)
            val lineHeight = infoPaint.fontMetrics.run { descent - ascent }
            val pad = 16f
            val atomRadius = (atomsById.getValue(id).radius * scale).toFloat()
            val left = anchor.x + atomRadius + 14f
            val top = anchor.y - atomRadius - 14f - lines.size * lineHeight - pad
            val rect = Rect(left, top, left + maxWidth + pad * 2f, anchor.y - atomRadius - 14f + pad)
            val panelColor = if (locked) Color(AppPalette.BRAND_MID).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
            drawRoundRect(panelColor, rect.topLeft, Size(rect.width, rect.height), androidx.compose.ui.geometry.CornerRadius(14f, 14f))
            lines.forEachIndexed { index, line ->
                drawContext.canvas.nativeCanvas.drawText(
                    line,
                    left + pad,
                    top + pad + (index + 1) * lineHeight - infoPaint.fontMetrics.descent,
                    infoPaint,
                )
            }
            inspectionBounds += Triple(rect, id, locked)
        }
        hitRegions.inspections = inspectionBounds
    }
}

private fun Double.formatFract() = "%.4f".format(this)

/**
 * Hosts the Filament backend (the only rendering backend since renderer-legacy was
 * declared end-of-life). All scene state and commands flow through the renderer SPI.
 */
@Composable
internal fun RendererHost(
    renderer: FilamentRenderer,
    scene: RenderScene,
    state: InteractionState,
    onCommand: (ViewerCommand) -> Unit,
    onAtomTap: (AtomImage) -> Boolean,
    bondValenceBySite: Map<String, Double>,
    onFilamentRendererChanged: (FilamentRenderer?) -> Unit,
    onFilamentFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(renderer) {
        onFilamentRendererChanged(renderer)
        onDispose {
            onFilamentRendererChanged(null)
            renderer.close()
        }
    }
    LaunchedEffect(renderer, scene) { renderer.submit(scene) }
    LaunchedEffect(renderer, state) { renderer.updateInteraction(state) }
    FilamentViewport(
        renderer = renderer,
        scene = scene,
        interactionState = state,
        onCommand = onCommand,
        onAtomTap = onAtomTap,
        onFailure = onFilamentFailure,
        bondValenceBySite = bondValenceBySite,
        modifier = modifier,
    )
}
