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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.krystals.crystal.core.model.AtomImage
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerCommand
import com.krystals.interaction.measure.AngleTool
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.DistanceTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.measure.MeasurementSelection
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.SceneBounds
import com.krystals.renderer.core.scene.allBounds
import com.krystals.renderer.core.scene.sceneProjection
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.SelectionColors
import com.krystals.renderer.core.SceneRenderer
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import com.krystals.renderer.filament.FilamentRenderer
import com.krystals.renderer.filament.FilamentSceneRenderer
import com.krystals.renderer.legacy.LegacySceneRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
            val event = awaitPointerEvent()
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
    bondValenceBySite: Map<String, Double> = emptyMap(),
    modifier: Modifier = Modifier,
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
    LaunchedEffect(renderer, bondValenceBySite) { renderer.updateOverlayData(bondValenceBySite) }

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
            val matrix = scene.structure.lattice.matrix
            val directions = when (scene.environment.axes.mode) {
                AxisMode.ABC -> listOf(matrix.a, matrix.b, matrix.c)
                AxisMode.XYZ -> listOf(
                    com.krystals.crystal.core.math.Vec3(1.0, 0.0, 0.0),
                    com.krystals.crystal.core.math.Vec3(0.0, 1.0, 0.0),
                    com.krystals.crystal.core.math.Vec3(0.0, 0.0, 1.0),
                )
            }
            val labels = if (scene.environment.axes.mode == AxisMode.ABC) listOf("a", "b", "c") else listOf("X", "Y", "Z")
            val colors = listOf(Color(0xFFE57373), Color(0xFF81C784), Color(0xFF64B5F6))
            val origin = Offset(size.width * scene.environment.axes.offsetX + 28f, size.height * scene.environment.axes.offsetY + 40f - 75f)
            val arrowLength = 75f
            val headLengthBase = 14f
            val light = scene.environment.worldLight
            val theta = light.azimuthDegrees / 180f * PI.toFloat()
            val phi = light.elevationDegrees / 180f * PI.toFloat()
            val lightOffset = Offset(cos(theta) * cos(phi), -sin(theta) * cos(phi))
            val axisPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 30f
                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
            }
            val rotatedDirs = directions.mapIndexed { index, axis ->
                val direction = (camera.rotation * axis).normalized()
                Triple(index, axis, direction)
            }
            fun drawArrow(index: Int, axis: com.krystals.crystal.core.math.Vec3, direction: com.krystals.crystal.core.math.Vec3) {
                val dx = direction.x.toFloat()
                val dy = -direction.y.toFloat()
                // Per v0.6.3: arrow length varies with projected direction (3D perspective).
                val projectedLength = kotlin.math.sqrt(dx * dx + dy * dy)
                val visibleLength = arrowLength * projectedLength
                val unit = if (projectedLength > 0.0001f) Offset(dx / projectedLength, dy / projectedLength) else Offset.Zero
                val end = origin + unit * visibleLength
                // Per v0.6.3: fixed arrowhead size (not scaled by projectedLength).
                val headLength = headLengthBase
                val shaftEnd = end - unit * headLength
                val color = colors[index]
                val perp = Offset(-unit.y, unit.x)
                val halfWidth = 4f
                // 3D cylinder shaft: draw as rotated rectangle with perpendicular gradient.
                val shaftAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(shaftAngle, origin.x, origin.y)
                val shaftLen = (visibleLength - headLength).coerceAtLeast(0f)
                val shaftBrush = Brush.linearGradient(
                    listOf(
                        color.copy(alpha = 0.4f),
                        color,
                        color.copy(red = (color.red * 0.6f + 1f * 0.4f).coerceIn(0f, 1f), green = (color.green * 0.6f + 1f * 0.4f).coerceIn(0f, 1f), blue = (color.blue * 0.6f + 1f * 0.4f).coerceIn(0f, 1f)),
                        color,
                        color.copy(alpha = 0.4f),
                    ),
                    start = Offset(origin.x, origin.y - halfWidth),
                    end = Offset(origin.x, origin.y + halfWidth),
                )
                drawRect(shaftBrush, topLeft = Offset(origin.x, origin.y - halfWidth), size = androidx.compose.ui.geometry.Size(shaftLen, halfWidth * 2f))
                drawContext.canvas.nativeCanvas.restore()
                // 3D cone arrowhead: filled triangle with perpendicular gradient.
                val halfHead = headLength * 0.6f
                val base = end - unit * headLength
                val arrow = Path().apply {
                    moveTo(end.x, end.y)
                    lineTo(base.x + perp.x * halfHead, base.y + perp.y * halfHead)
                    lineTo(base.x - perp.x * halfHead, base.y - perp.y * halfHead)
                    close()
                }
                val headBrush = Brush.linearGradient(
                    listOf(
                        color.copy(alpha = 0.4f),
                        color,
                        color.copy(red = (color.red * 0.6f + 1f * 0.4f).coerceIn(0f, 1f), green = (color.green * 0.6f + 1f * 0.4f).coerceIn(0f, 1f), blue = (color.blue * 0.6f + 1f * 0.4f).coerceIn(0f, 1f)),
                        color,
                        color.copy(alpha = 0.4f),
                    ),
                    start = Offset(base.x + perp.x * halfHead, base.y + perp.y * halfHead),
                    end = Offset(base.x - perp.x * halfHead, base.y - perp.y * halfHead),
                )
                drawPath(arrow, headBrush)
                axisPaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(labels[index], end.x + 4f, end.y - 4f, axisPaint)
            }
            // Draw back arrows (pointing away from viewer) first.
            rotatedDirs.filter { it.third.z <= 0.0 }.forEach { (index, axis, direction) ->
                drawArrow(index, axis, direction)
            }
            // Center sphere (radius 12f).
            drawCircle(Color.Black.copy(alpha = 0.5f), 13f, origin + Offset(1f, 1f))
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFE0E0E0), Color(0xFF68686F)),
                    center = origin + lightOffset * 4.5f,
                    radius = 12f,
                ),
                12f,
                origin,
            )
            // Draw front arrows (pointing toward viewer) on top of the sphere.
            rotatedDirs.filter { it.third.z > 0.0 }.forEach { (index, axis, direction) ->
                drawArrow(index, axis, direction)
            }
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

        val measurementBounds = mutableListOf<Triple<Rect, MeasurementSelection?, Boolean>>()
        val measurements = mutableListOf<Triple<MeasurementSelection, MeasurementSelection?, Boolean>>()
        measurements += state.document.lockedMeasurements.map { Triple(it, it, true) }
        if (state.document.measurementMode != MeasurementMode.NONE && state.document.selection.selectedAtomIds.isNotEmpty()) {
            measurements += Triple(MeasurementSelection(state.document.selection.selectedAtomIds, state.document.measurementMode), null, false)
        }
        val measurementPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 48f
            setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
        }
        measurements.forEach { (selection, lockValue, locked) ->
            val expected = when (selection.mode) {
                MeasurementMode.LENGTH -> 2
                MeasurementMode.ANGLE -> 3
                MeasurementMode.DIHEDRAL -> 4
                else -> 0
            }
            if (expected == 0 || selection.atomIds.size < expected) return@forEach
            val selectedIds = selection.atomIds.takeLast(expected)
            val points = selectedIds.mapNotNull(::point)
            val coordinates = selectedIds.mapNotNull { atomsById[it]?.atom?.cartesianCoordinate?.toVec3() }
            if (points.size != expected || coordinates.size != expected) return@forEach
            // Per v0.6: dihedral planes are gradient canvas overlays (matching the Legacy
            // renderer), same as FilamentRenderer.composeOverlay — they were missing here.
            if (selection.mode == MeasurementMode.DIHEDRAL) {
                DihedralTool.planes(coordinates[0], coordinates[1], coordinates[2], coordinates[3]).forEach { plane ->
                    val sv = plane.vertices.map { v ->
                        val (px, py) = projection.project(v)
                        Offset(px.toFloat(), py.toFloat())
                    }
                    if (sv.size == 4) {
                        val path = Path().apply {
                            moveTo(sv[0].x, sv[0].y)
                            sv.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(
                            path,
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(150, 95, 205, 112),
                                    Color(128, 72, 180, 56),
                                    Color.Transparent,
                                ),
                                start = sv[0],
                                end = sv[3],
                            ),
                        )
                    }
                }
            }
            val label = when (selection.mode) {
                MeasurementMode.LENGTH -> "%.4f \u00C5".format(DistanceTool.calculate(coordinates[0], coordinates[1]))
                MeasurementMode.ANGLE -> "%.3f\u00B0".format(AngleTool.calculate(coordinates[0], coordinates[1], coordinates[2]))
                MeasurementMode.DIHEDRAL -> "%.3f\u00B0".format(DihedralTool.calculate(coordinates[0], coordinates[1], coordinates[2], coordinates[3]))
                else -> return@forEach
            }
            val anchor = points.reduce { first, second -> first + second } / points.size.toFloat()
            val nativeBounds = android.graphics.Rect()
            measurementPaint.getTextBounds(label, 0, label.length, nativeBounds)
            val pad = 16f
            val rect = Rect(
                anchor.x + 12f - pad,
                anchor.y - 12f - nativeBounds.height() - pad,
                anchor.x + 12f + nativeBounds.width() + pad,
                anchor.y - 12f + pad,
            )
            val panelColor = if (locked) Color(0xFF9966CC).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
            drawRoundRect(panelColor, rect.topLeft, Size(rect.width, rect.height), androidx.compose.ui.geometry.CornerRadius(14f, 14f))
            drawContext.canvas.nativeCanvas.drawText(label, anchor.x + 12f, anchor.y - 12f, measurementPaint)
            measurementBounds += Triple(rect, lockValue, locked)
        }
        hitRegions.measurements = measurementBounds

        val inspectionBounds = mutableListOf<Triple<Rect, Long, Boolean>>()
        val inspectionIds = state.document.inspection.lockedInspectedAtomIds + listOfNotNull(state.document.inspection.inspectedAtomId).filterNot { it in state.document.inspection.lockedInspectedAtomIds }
        val infoPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
        }
        inspectionIds.forEach { id ->
            val atom = atomsById[id]?.atom ?: return@forEach
            val anchor = point(id) ?: return@forEach
            val locked = id in state.document.inspection.lockedInspectedAtomIds
            val valence = bondValenceBySite[atom.siteId]?.let { "  s = %.2f".format(it) }.orEmpty()
            val fractional = atom.fractionalCoordinate
            val lines = listOf(
                "${atom.species.symbol}  ${atom.siteLabel}  occ ${atom.occupancy}$valence",
                "(${fractional.x.formatFract()}, ${fractional.y.formatFract()}, ${fractional.z.formatFract()})",
            )
            val maxWidth = lines.maxOf(infoPaint::measureText)
            val lineHeight = infoPaint.fontMetrics.run { descent - ascent }
            val pad = 16f
            // Anchor at Filament's visual sphere radius (no Legacy pixel clamp) so the info
            // panel clears the rendered sphere at any zoom level.
            val atomRadius = (atomsById.getValue(id).radius * scale).toFloat()
            val left = anchor.x + atomRadius + 14f
            val top = anchor.y - atomRadius - 14f - lines.size * lineHeight - pad
            val rect = Rect(left, top, left + maxWidth + pad * 2f, anchor.y - atomRadius - 14f + pad)
            val panelColor = if (locked) Color(0xFF9966CC).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
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
 * Hosts either the Filament or Canvas-Legacy backend behind the shared [SceneRenderer] SPI.
 *
 * The small UI-specific branch here is unavoidable: Filament needs an [AndroidView] with a
 * [Surface], while Canvas-Legacy is a Compose [Canvas]. All scene state and commands flow
 * through [SceneRenderer].
 */
@Composable
internal fun RendererHost(
    renderer: SceneRenderer,
    scene: RenderScene,
    state: InteractionState,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    onCommand: (ViewerCommand) -> Unit,
    onAtomTap: (AtomImage) -> Boolean,
    bondValenceBySite: Map<String, Double>,
    onFilamentRendererChanged: (FilamentRenderer?) -> Unit,
    onFilamentFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(renderer) {
        if (renderer is FilamentRenderer) onFilamentRendererChanged(renderer)
        onDispose {
            if (renderer is FilamentRenderer) onFilamentRendererChanged(null)
            renderer.close()
        }
    }
    DisposableEffect(renderer, appearance, renderConfiguration, bondValenceBySite, onCommand, onAtomTap) {
        if (renderer is LegacySceneRenderer) {
            renderer.configure(appearance, renderConfiguration, bondValenceBySite, onCommand, onAtomTap)
        }
        onDispose { }
    }
    LaunchedEffect(renderer, scene) { renderer.submit(scene) }
    LaunchedEffect(renderer, state) { renderer.updateInteraction(state) }
    when (renderer) {
        is FilamentSceneRenderer -> FilamentViewport(
            renderer = renderer as FilamentRenderer,
            scene = scene,
            interactionState = state,
            onCommand = onCommand,
            onAtomTap = onAtomTap,
            onFailure = onFilamentFailure,
            bondValenceBySite = bondValenceBySite,
            modifier = modifier,
        )
        is LegacySceneRenderer -> renderer.Content(modifier)
    }
}
