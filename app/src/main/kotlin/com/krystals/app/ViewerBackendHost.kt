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
import com.krystals.renderer.core.scene.visibleBounds
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.filament.FilamentRenderer
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
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
            if (!isLocked()) {
                if (pressed.size == 1) {
                    val delta = pressed.first().position - pressed.first().previousPosition
                    if (delta.getDistance() > 0f) onCommand(ViewerCommand.Orbit(delta.x, delta.y))
                } else if (pressed.size >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (abs(zoom - 1f) > 0.001f) onCommand(ViewerCommand.Zoom(zoom))
                    if (pan.getDistance() > 0.5f) onCommand(ViewerCommand.Pan(-pan.x, -pan.y))
                }
            }
            event.changes.forEach { it.consume() }
            if (pressed.isEmpty()) {
                if (!moved) onTap(down)
                break
            }
        }
    }
}

@Composable
fun FilamentViewport(
    scene: RenderScene,
    interactionState: InteractionState,
    onCommand: (ViewerCommand) -> Unit,
    onAtomTap: (AtomImage) -> Boolean,
    onFailure: (Throwable) -> Unit,
    onRendererChanged: (FilamentRenderer?) -> Unit,
    bondValenceBySite: Map<String, Double> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val rendererResult = remember(context) { runCatching { FilamentRenderer(context) } }
    val renderer = rendererResult.getOrNull()
    val scope = rememberCoroutineScope()
    val hitRegions = remember { OverlayHitRegions() }
    val lastTap = remember { LastTap() }
    val currentScene = rememberUpdatedState(scene)
    val currentInteraction = rememberUpdatedState(interactionState)
    val currentOnCommand = rememberUpdatedState(onCommand)
    val currentOnAtomTap = rememberUpdatedState(onAtomTap)

    LaunchedEffect(rendererResult) { rendererResult.exceptionOrNull()?.let(onFailure) }
    if (renderer == null) {
        Box(modifier.fillMaxSize())
        return
    }

    DisposableEffect(renderer) {
        onRendererChanged(renderer)
        onDispose {
            onRendererChanged(null)
            renderer.close()
        }
    }
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
        FilamentOverlay(scene, interactionState, bondValenceBySite, hitRegions)
    }
}

@Composable
private fun FilamentOverlay(
    scene: RenderScene,
    state: InteractionState,
    bondValenceBySite: Map<String, Double>,
    hitRegions: OverlayHitRegions,
) {
    val cache = remember(scene) {
        OverlaySceneCache(
            bounds = scene.visibleBounds(),
            atomsById = scene.atoms.asSequence().filter { it.visible }.associateBy { it.atom.id },
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        val bounds = cache.bounds ?: return@Canvas
        val atomsById = cache.atomsById
        val camera = state.session.camera
        val span = bounds.radius / camera.zoom
        val aspect = size.width.toDouble() / size.height.coerceAtLeast(1f)
        val scale = (size.height / (span * 2.0)).toFloat()
        fun point(id: Long): Offset? = atomsById[id]?.let { atom ->
            val rotated = camera.rotation * (atom.atom.cartesianCoordinate.toVec3() - bounds.center - camera.target)
            Offset(
                size.width / 2f + camera.panX.toFloat() + (rotated.x / (span * aspect) * size.width * 0.5).toFloat(),
                size.height / 2f + camera.panY.toFloat() - (rotated.y / span * size.height * 0.5).toFloat(),
            )
        }
        val lockedIds = state.document.lockedMeasurements.flatMap { it.atomIds }.toSet() + state.document.inspection.lockedInspectedAtomIds
        val highlighted = state.document.selection.selectedAtomIds.toSet() + lockedIds + listOfNotNull(state.document.inspection.inspectedAtomId)
        highlighted.forEach { id ->
            val atom = atomsById[id] ?: return@forEach
            val centerPoint = point(id) ?: return@forEach
            val radius = (atom.radius * scale).toFloat().coerceIn(4.5f, 42f) + 4f
            drawCircle(if (id in lockedIds) Color(0xFFCFA7F5) else Color(0xFF7542A5), radius, centerPoint, style = Stroke(3f))
        }

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
            val origin = Offset(size.width * 0.08f + 28f, size.height * 0.08f + 40f)
            val arrowLength = 56f
            val headLength = 16f
            val light = scene.environment.worldLight
            val azimuth = light.azimuthDegrees / 180f * PI.toFloat()
            val elevation = light.elevationDegrees / 180f * PI.toFloat()
            val lightOffset = Offset(cos(azimuth) * cos(elevation), sin(azimuth) * cos(elevation))
            val axisPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 30f
                setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
            }
            directions.forEachIndexed { index, axis ->
                val direction = camera.rotation * axis
                val projected = Offset(direction.x.toFloat(), -direction.y.toFloat())
                val projectedLength = projected.getDistance()
                val unit = if (projectedLength > 0.0001f) projected / projectedLength else Offset.Zero
                val end = origin + unit * arrowLength
                val shaftEnd = end - unit * headLength
                val color = colors[index]
                drawLine(Color.Black.copy(alpha = 0.48f), origin, shaftEnd, 7f)
                drawLine(color, origin, shaftEnd, 5f)
                val perpendicular = Offset(-unit.y, unit.x)
                val halfHead = headLength * 0.6f
                val base = end - unit * headLength
                val arrow = Path().apply {
                    moveTo(end.x, end.y)
                    lineTo(base.x + perpendicular.x * halfHead, base.y + perpendicular.y * halfHead)
                    lineTo(base.x - perpendicular.x * halfHead, base.y - perpendicular.y * halfHead)
                    close()
                }
                drawPath(arrow, color)
                axisPaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(labels[index], end.x + 4f, end.y - 4f, axisPaint)
            }
            drawCircle(Color.Black.copy(alpha = 0.5f), 9f, origin + Offset(1f, 1f))
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFE0E0E0), Color(0xFF68686F)),
                    center = origin - lightOffset * 3f,
                    radius = 8f,
                ),
                8f,
                origin,
            )
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
            val atomRadius = (atomsById.getValue(id).radius * scale).toFloat().coerceIn(4.5f, 42f)
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
