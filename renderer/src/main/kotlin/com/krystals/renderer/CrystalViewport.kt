package com.krystals.renderer

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.krystals.core.BondColorMode
import com.krystals.core.ExpandedAtom
import com.krystals.core.FrameMode
import com.krystals.core.LineStyle
import com.krystals.core.PeriodicTable
import com.krystals.core.SceneSnapshot
import com.krystals.core.UnitCell
import com.krystals.core.Vec3
import com.krystals.core.ViewerAppearance
import com.krystals.core.angleDegrees
import com.krystals.core.dihedralDegrees
import com.krystals.core.distance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MeasurementMode { NONE, LENGTH, ANGLE, DIHEDRAL }

data class ViewerVisibility(
    val hiddenSites: Set<String> = emptySet(),
    val hiddenBondPairs: Set<String> = emptySet(),
    val polyhedronSites: Set<String> = emptySet(),
    val showBonds: Boolean = true,
)

internal data class ProjectedAtom(val atom: ExpandedAtom, val point: Offset, val depth: Double, val radius: Float)

private sealed interface Renderable {
    val depth: Double
}

private data class AtomRenderable(val atom: ProjectedAtom, val selected: Boolean) : Renderable {
    override val depth = atom.depth
}

private data class BondRenderable(val a: ProjectedAtom, val b: ProjectedAtom, val width: Float) : Renderable {
    override val depth = (a.depth + b.depth) / 2.0
}

private data class PolyhedronRenderable(val center: ProjectedAtom, val vertices: List<ProjectedAtom>, val adjacency: Map<Long, Set<Long>>) : Renderable {
    override val depth = center.depth
}

@Stable
class ViewerController {
    var yaw by mutableFloatStateOf(-28f)
    var pitch by mutableFloatStateOf(22f)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var locked by mutableStateOf(false)
    internal var projectedAtoms: List<ProjectedAtom> = emptyList()
    var viewportWidth: Int = 1080
        internal set
    var viewportHeight: Int = 1080
        internal set

    fun align(axis: Char) {
        when (axis.lowercaseChar()) {
            'x' -> { yaw = -90f; pitch = 0f }
            'y' -> { yaw = 0f; pitch = 90f }
            else -> { yaw = 0f; pitch = 0f }
        }
        panX = 0f
        panY = 0f
    }

    fun alignCellAxis(axis: Char, cell: UnitCell) {
        val v = when (axis.lowercaseChar()) {
            'a' -> cell.matrix.a
            'b' -> cell.matrix.b
            else -> cell.matrix.c
        }
        val pitchRad = atan2(v.y, v.z)
        val zPlane = v.y * sin(pitchRad) + v.z * cos(pitchRad)
        val yawRad = atan2(-v.x, zPlane)
        yaw = Math.toDegrees(yawRad).toFloat()
        pitch = Math.toDegrees(pitchRad).toFloat()
        panX = 0f
        panY = 0f
    }

    fun reset() {
        yaw = -28f; pitch = 22f; zoom = 1f; panX = 0f; panY = 0f
    }

    internal fun pick(position: Offset): ExpandedAtom? = projectedAtoms
        .asSequence()
        .filter { (it.point - position).getDistance() <= max(22f, it.radius * 1.35f) }
        .minByOrNull { (it.point - position).getDistance() - it.depth.toFloat() * 0.0001f }
        ?.atom
}

@Composable
fun rememberViewerController() = remember { ViewerController() }

private data class TapEvent(val time: Long, val position: Offset)

@Composable
fun CrystalViewport(
    snapshot: SceneSnapshot,
    appearance: ViewerAppearance,
    modifier: Modifier = Modifier,
    controller: ViewerController = rememberViewerController(),
    visibility: ViewerVisibility = ViewerVisibility(),
    selectedAtomIds: List<Long> = emptyList(),
    measurementMode: MeasurementMode = MeasurementMode.NONE,
    measurementLocked: Boolean = false,
    onMeasurementLockToggle: () -> Unit = {},
    inspectedAtomId: Long? = null,
    inspectionLocked: Boolean = false,
    onInspectAtom: (ExpandedAtom) -> Unit = {},
    onInspectionLockToggle: () -> Unit = {},
    onAtomTap: (ExpandedAtom) -> Unit = {},
    onViewMoved: () -> Unit = {},
) {
    val background = colorFromArgb(appearance.backgroundArgb)
    val scope = rememberCoroutineScope()
    var longPressOffset by remember { mutableStateOf<Offset?>(null) }
    var lastTap by remember { mutableStateOf<TapEvent?>(null) }
    var measurementBounds by remember { mutableStateOf<Rect?>(null) }
    var atomInfoBounds by remember { mutableStateOf<Rect?>(null) }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(snapshot, controller.locked, measurementLocked, inspectionLocked) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val down = first.position
                    val downTime = SystemClock.uptimeMillis()
                    var moved = false
                    var longPressed = false
                    val longPressJob = scope.launch {
                        delay(400)
                        longPressed = true
                        longPressOffset = down
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val movement = event.changes.sumOf { it.position.minus(it.previousPosition).getDistance().toDouble() }
                        if (movement > 2.0) {
                            moved = true
                            if (!longPressed) longPressJob.cancel()
                        }
                        if (longPressed) {
                            if (pressed.size == 1) {
                                val delta = pressed.first().position - pressed.first().previousPosition
                                controller.yaw += delta.x * 0.08f
                                controller.pitch += delta.y * 0.08f
                                onViewMoved()
                            }
                        } else if (!controller.locked) {
                            if (pressed.size == 1) {
                                val delta = pressed.first().position - pressed.first().previousPosition
                                if (delta.getDistance() > 0f) {
                                    controller.yaw += delta.x * 0.32f
                                    controller.pitch += delta.y * 0.32f
                                    onViewMoved()
                                }
                            } else if (pressed.size >= 2) {
                                val zoomDelta = event.calculateZoom()
                                val pan = event.calculatePan()
                                controller.zoom = (controller.zoom * zoomDelta).coerceIn(0.08f, 25f)
                                controller.panX += pan.x
                                controller.panY += pan.y
                                if (abs(zoomDelta - 1f) > 0.001f || pan.getDistance() > 0.5f) onViewMoved()
                            }
                        }
                        event.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) {
                            longPressJob.cancel()
                            longPressOffset = null
                            if (!moved && !longPressed) {
                                val tapInMeasurementBox = measurementBounds?.contains(down) == true
                                val tapInAtomInfoBox = atomInfoBounds?.contains(down) == true
                                when {
                                    tapInMeasurementBox -> onMeasurementLockToggle()
                                    tapInAtomInfoBox -> onInspectionLockToggle()
                                    else -> controller.pick(down)?.let { atom ->
                                        val prev = lastTap
                                        val now = SystemClock.uptimeMillis()
                                        lastTap = TapEvent(now, down)
                                        if (prev != null && now - prev.time < 300 && (down - prev.position).getDistance() < 24f) {
                                            onInspectAtom(atom)
                                        } else {
                                            onAtomTap(atom)
                                        }
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            },
    ) {
        drawRect(background)
        controller.viewportWidth = size.width.toInt().coerceAtLeast(1)
        controller.viewportHeight = size.height.toInt().coerceAtLeast(1)
        measurementBounds = null
        atomInfoBounds = null
        if (snapshot.atoms.isEmpty()) {
            drawEmptyMessage()
            controller.projectedAtoms = emptyList()
            return@Canvas
        }

        val visibleAtoms = snapshot.atoms.filterNot { it.siteId in visibility.hiddenSites }
        if (visibleAtoms.isEmpty()) {
            drawEmptyMessage()
            controller.projectedAtoms = emptyList()
            return@Canvas
        }
        val center = boundingCenter(snapshot.atoms.map { it.cartesian })
        val rotated = visibleAtoms.associateWith { rotate(it.cartesian - center, controller.yaw, controller.pitch) }
        val fullRotated = snapshot.atoms.map { rotate(it.cartesian - center, controller.yaw, controller.pitch) }
        val extentX = fullRotated.maxOf { it.x } - fullRotated.minOf { it.x }
        val extentY = fullRotated.maxOf { it.y } - fullRotated.minOf { it.y }
        val baseScale = min(size.width / max(1.0, extentX).toFloat(), size.height / max(1.0, extentY).toFloat()) * 0.72f
        val scale = baseScale * controller.zoom
        fun project(value: Vec3) = Offset(
            size.width / 2f + controller.panX + value.x.toFloat() * scale,
            size.height / 2f + controller.panY - value.y.toFloat() * scale,
        )

        drawCellFrames(snapshot, appearance, center, controller, scale, ::project)

        val projected = visibleAtoms.map { atom ->
            val v = rotated.getValue(atom)
            val radius = (PeriodicTable.defaultRadius(atom.element).toFloat() * scale).coerceIn(4.5f, 42f)
            ProjectedAtom(atom, project(v), v.z, radius)
        }
        val byId = projected.associateBy { it.atom.id }
        val neighbors = mutableMapOf<Long, MutableList<ProjectedAtom>>()
        val bondAdjacency = mutableMapOf<Long, MutableSet<Long>>()
        val renderables = buildList<Renderable> {
            projected.forEach { add(AtomRenderable(it, it.atom.id in selectedAtomIds)) }
            if (visibility.showBonds) {
                snapshot.bonds.forEach { bond ->
                    val a = byId[bond.atomA] ?: return@forEach
                    val b = byId[bond.atomB] ?: return@forEach
                    if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                    neighbors.getOrPut(a.atom.id) { mutableListOf() } += b
                    neighbors.getOrPut(b.atom.id) { mutableListOf() } += a
                    bondAdjacency.getOrPut(a.atom.id) { mutableSetOf() } += b.atom.id
                    bondAdjacency.getOrPut(b.atom.id) { mutableSetOf() } += a.atom.id
                    val width = (appearance.bondRadius * scale * 0.65f).coerceIn(1.5f, 16f)
                    add(BondRenderable(a, b, width))
                }
            }
            if (appearance.polyhedronEnabled && visibility.polyhedronSites.isNotEmpty()) {
                projected.filter { it.atom.siteId in visibility.polyhedronSites }.forEach { center ->
                    val vertices = neighbors[center.atom.id].orEmpty()
                    if (vertices.size >= 3) add(PolyhedronRenderable(center, vertices, bondAdjacency))
                }
            }
        }.sortedBy { it.depth }

        renderables.forEach { renderable ->
            when (renderable) {
                is AtomRenderable -> drawAtom(renderable.atom, renderable.selected, appearance, snapshot.elementArgbOverrides)
                is BondRenderable -> drawBond(renderable.a, renderable.b, renderable.width, appearance, snapshot.elementArgbOverrides)
                is PolyhedronRenderable -> drawPolyhedron(renderable.center, renderable.vertices, renderable.adjacency, appearance, snapshot.elementArgbOverrides)
            }
        }
        measurementBounds = drawMeasurement(projected, selectedAtomIds, measurementMode, measurementLocked)
        atomInfoBounds = drawAtomInfo(projected, inspectedAtomId, inspectionLocked, appearance)
        longPressOffset?.let { drawLongPressHandle(it) }
        controller.projectedAtoms = projected
    }
}

private fun DrawScope.drawAtom(atom: ProjectedAtom, selected: Boolean, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>) {
    val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
    val base = colorFromArgb(PeriodicTable.resolveArgb(atom.atom.element, elementArgbOverrides)).copy(alpha = opacity)
    val sphere = Brush.radialGradient(
        listOf(base, base.darken(0.65f)),
        center = atom.point,
        radius = atom.radius,
    )
    drawCircle(sphere, atom.radius, atom.point)
    if (appearance.reflectionEnabled && opacity > 0.01f) {
        val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
        val elevation = appearance.lightElevation / 180f * PI.toFloat()
        val offset = atom.radius * 0.38f * cos(elevation)
        val highlightCenter = atom.point - Offset(cos(azimuth) * offset, sin(azimuth) * offset)
        val highlight = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = appearance.lightIntensity.coerceIn(0.05f, 1f) * opacity),
                Color.Transparent,
            ),
            center = highlightCenter,
            radius = atom.radius * (1.1f + appearance.diffusion * 0.45f),
        )
        drawCircle(highlight, atom.radius, atom.point)
    }
    drawCircle(Color.Black.copy(alpha = 0.28f * opacity), atom.radius, atom.point, style = Stroke(max(0.8f, atom.radius * 0.045f)))
    if (selected) drawCircle(Color(0xFF9966CC), atom.radius + 4f, atom.point, style = Stroke(3f))
}

private fun DrawScope.drawBond(a: ProjectedAtom, b: ProjectedAtom, width: Float, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>) {
    val opacity = appearance.bondOpacity.coerceIn(0f, 1f)
    if (opacity < 0.01f) return
    val delta = b.point - a.point
    val length = delta.getDistance()
    if (length < 0.001f) return
    val dir = delta / length
    val start = a.point + dir * a.radius
    val end = b.point - dir * b.radius
    val clipped = end - start
    if (clipped.getDistance() < 0.001f) return
    val perp = Offset(-dir.y, dir.x)

    val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
    val elevation = appearance.lightElevation / 180f * PI.toFloat()
    val lightX = cos(azimuth) * cos(elevation)
    val lightY = sin(azimuth) * cos(elevation)
    val lightOnPerp = (lightX * perp.x + lightY * perp.y).toDouble()

    if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
        val base = colorFromArgb(appearance.uniformBondArgb).copy(alpha = opacity)
        drawBondCylinder(start, end, width / 2f, perp, lightOnPerp, base, appearance.bondReflectionEnabled)
    } else {
        val midpoint = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
        val baseA = colorFromArgb(PeriodicTable.resolveArgb(a.atom.element, elementArgbOverrides)).copy(alpha = opacity)
        val baseB = colorFromArgb(PeriodicTable.resolveArgb(b.atom.element, elementArgbOverrides)).copy(alpha = opacity)
        drawBondCylinder(start, midpoint, width / 2f, perp, lightOnPerp, baseA, appearance.bondReflectionEnabled)
        drawBondCylinder(midpoint, end, width / 2f, perp, lightOnPerp, baseB, appearance.bondReflectionEnabled)
    }
}

private fun DrawScope.drawBondCylinder(
    start: Offset,
    end: Offset,
    halfWidth: Float,
    perp: Offset,
    lightOnPerp: Double,
    base: Color,
    reflectionEnabled: Boolean,
) {
    val offset = perp * halfWidth
    val p1 = start + offset
    val p2 = end + offset
    val p3 = end - offset
    val p4 = start - offset
    val path = Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        lineTo(p4.x, p4.y)
        close()
    }

    val center = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
    val shadowA = base.darken(0.55f)
    val shadowB = base.darken(0.45f)
    val highlight = if (reflectionEnabled) base.lighten(0.55f) else base
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to shadowA,
            (highlightPos - 0.15f).coerceIn(0.02f, 0.98f) to base,
            highlightPos to highlight,
            (highlightPos + 0.15f).coerceIn(0.02f, 0.98f) to base,
            1.0f to shadowB,
        ),
        start = center - perp * halfWidth,
        end = center + perp * halfWidth,
    )
    drawPath(path, brush)
}

private fun Color.darken(factor: Float) = Color(red * factor, green * factor, blue * factor, alpha)
private fun Color.lighten(factor: Float) = Color(
    red + (1f - red) * factor,
    green + (1f - green) * factor,
    blue + (1f - blue) * factor,
    alpha,
)

private fun DrawScope.drawPolyhedron(center: ProjectedAtom, vertices: List<ProjectedAtom>, adjacency: Map<Long, Set<Long>>, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>) {
    if (vertices.size < 3) return
    val baseArgb = PeriodicTable.resolveArgb(center.atom.element, elementArgbOverrides)
    val baseColor = colorFromArgb(baseArgb).copy(alpha = appearance.polyhedronOpacity.coerceIn(0f, 1f))
    val vertexIds = vertices.map { it.atom.id }
    val vertexIndex = vertexIds.withIndex().associate { it.value to it.index }

    val drawn = mutableSetOf<List<Long>>()
    val faces = mutableListOf<Triple<ProjectedAtom, ProjectedAtom, ProjectedAtom>>()
    vertices.forEachIndexed { i, vi ->
        val neighbors = vertexIds.mapNotNull { id ->
            if (adjacency[vi.atom.id]?.contains(id) == true) vertexIndex[id] else null
        }.filter { it > i }
        for (aIndex in 0 until neighbors.size) {
            for (bIndex in aIndex + 1 until neighbors.size) {
                val j = neighbors[aIndex]
                val k = neighbors[bIndex]
                if (adjacency[vertexIds[j]]?.contains(vertexIds[k]) == true) {
                    val key = listOf(vertexIds[i], vertexIds[j], vertexIds[k]).sorted()
                    if (drawn.add(key)) {
                        faces += Triple(vertices[i], vertices[j], vertices[k])
                    }
                }
            }
        }
    }

    faces.forEach { (va, vb, vc) ->
        val normal = (vb.atom.cartesian - va.atom.cartesian).cross(vc.atom.cartesian - va.atom.cartesian)
        val toCenter = center.atom.cartesian - va.atom.cartesian
        val (orderedA, orderedB, orderedC) = if (normal.dot(toCenter) > 0) Triple(vc, vb, va) else Triple(va, vb, vc)
        val path = Path().apply {
            moveTo(orderedA.point.x, orderedA.point.y)
            lineTo(orderedB.point.x, orderedB.point.y)
            lineTo(orderedC.point.x, orderedC.point.y)
            close()
        }
        val fill = if (appearance.polyhedronReflectionEnabled) {
            val faceCenter = (orderedA.atom.cartesian + orderedB.atom.cartesian + orderedC.atom.cartesian) / 3.0
            val outward = (faceCenter - center.atom.cartesian).let { diff ->
                val len = diff.length()
                if (len < 1e-12) Vec3.ZERO else diff / len
            }
            val azimuth = appearance.lightAzimuth / 180.0 * PI
            val elevation = appearance.lightElevation / 180.0 * PI
            val light = Vec3(cos(azimuth) * cos(elevation), sin(azimuth) * cos(elevation), sin(elevation))
            val factor = 0.55 + 0.45 * outward.dot(light).coerceIn(-1.0, 1.0)
            baseColor.copy(
                red = (baseColor.red * factor).toFloat().coerceIn(0f, 1f),
                green = (baseColor.green * factor).toFloat().coerceIn(0f, 1f),
                blue = (baseColor.blue * factor).toFloat().coerceIn(0f, 1f),
            )
        } else baseColor
        drawPath(path, fill)
    }

    val drawnEdges = mutableSetOf<List<Long>>()
    vertices.forEach { v ->
        adjacency[v.atom.id]?.forEach { neighborId ->
            val neighbor = vertices.firstOrNull { it.atom.id == neighborId } ?: return@forEach
            val key = listOf(v.atom.id, neighbor.atom.id).sorted()
            if (drawnEdges.add(key)) {
                drawLine(Color.White.copy(alpha = 0.35f), v.point, neighbor.point, 1.2f)
            }
        }
    }
}

private fun DrawScope.drawMeasurement(
    projected: List<ProjectedAtom>,
    ids: List<Long>,
    mode: MeasurementMode,
    locked: Boolean,
): Rect? {
    val expected = when (mode) { MeasurementMode.LENGTH -> 2; MeasurementMode.ANGLE -> 3; MeasurementMode.DIHEDRAL -> 4; else -> 0 }
    if (expected == 0 || ids.size < expected) return null
    val points = ids.takeLast(expected).mapNotNull { id -> projected.firstOrNull { it.atom.id == id } }
    if (points.size != expected) return null
    for (index in 0 until points.lastIndex) drawLine(Color(0xFFE1C6FF), points[index].point, points[index + 1].point, 2.5f)
    val label = when (mode) {
        MeasurementMode.LENGTH -> "%.4f Å".format(distance(points[0].atom.cartesian, points[1].atom.cartesian))
        MeasurementMode.ANGLE -> "%.3f°".format(angleDegrees(points[0].atom.cartesian, points[1].atom.cartesian, points[2].atom.cartesian))
        MeasurementMode.DIHEDRAL -> "%.3f°".format(dihedralDegrees(points[0].atom.cartesian, points[1].atom.cartesian, points[2].atom.cartesian, points[3].atom.cartesian))
        else -> return null
    }
    val anchor = points.map { it.point }.reduce { a, b -> a + b } / points.size.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 48f
        setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
    }
    val bounds = android.graphics.Rect()
    paint.getTextBounds(label, 0, label.length, bounds)
    val pad = 16f
    val boxLeft = anchor.x + 12f - pad
    val boxTop = anchor.y - 12f - bounds.height() - pad
    val boxRight = anchor.x + 12f + bounds.width() + pad
    val boxBottom = anchor.y - 12f + pad
    val boxColor = if (locked) Color(0xFF9966CC).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
    drawRoundRect(boxColor, topLeft = Offset(boxLeft, boxTop), size = androidx.compose.ui.geometry.Size(boxRight - boxLeft, boxBottom - boxTop), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f))
    drawContext.canvas.nativeCanvas.drawText(label, anchor.x + 12f, anchor.y - 12f, paint)
    return Rect(Offset(boxLeft, boxTop), Offset(boxRight, boxBottom))
}

private fun DrawScope.drawAtomInfo(
    projected: List<ProjectedAtom>,
    inspectedAtomId: Long?,
    locked: Boolean,
    appearance: ViewerAppearance,
): Rect? {
    val atom = inspectedAtomId?.let { id -> projected.firstOrNull { it.atom.id == id } } ?: return null
    val label = "${atom.atom.element}  occ ${atom.atom.occupancy}\n(${atom.atom.fractional.x.formatFract()}, ${atom.atom.fractional.y.formatFract()}, ${atom.atom.fractional.z.formatFract()})"
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 40f
        setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
    }
    val lines = label.split('\n')
    val widths = lines.map { line -> paint.measureText(line) }
    val maxWidth = widths.maxOrNull() ?: 0f
    val lineHeight = paint.fontMetrics.run { descent - ascent }
    val pad = 16f
    val boxLeft = atom.point.x + atom.radius + 14f
    val boxTop = atom.point.y - atom.radius - 14f - lines.size * lineHeight - pad
    val boxRight = boxLeft + maxWidth + pad * 2
    val boxBottom = atom.point.y - atom.radius - 14f + pad
    val boxColor = if (locked) Color(0xFF9966CC).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
    drawRoundRect(boxColor, topLeft = Offset(boxLeft, boxTop), size = androidx.compose.ui.geometry.Size(boxRight - boxLeft, boxBottom - boxTop), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f))
    lines.forEachIndexed { index, line ->
        drawContext.canvas.nativeCanvas.drawText(line, boxLeft + pad, boxTop + pad + (index + 1) * lineHeight - paint.fontMetrics.descent, paint)
    }
    return Rect(Offset(boxLeft, boxTop), Offset(boxRight, boxBottom))
}

private fun Double.formatFract() = "%.4f".format(this)

private fun DrawScope.drawLongPressHandle(center: Offset) {
    drawCircle(Color.White.copy(alpha = 0.25f), 56f, center)
    drawCircle(Color(0xFF9966CC).copy(alpha = 0.9f), 12f, center)
    drawCircle(Color.White.copy(alpha = 0.6f), 46f, center, style = Stroke(2f))
}

private fun DrawScope.drawCellFrames(
    snapshot: SceneSnapshot,
    appearance: ViewerAppearance,
    center: Vec3,
    controller: ViewerController,
    scale: Float,
    project: (Vec3) -> Offset,
) {
    if (appearance.frameMode == FrameMode.NONE) return
    val ex = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.x else 1
    val ey = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.y else 1
    val ez = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.z else 1
    val effect = if (appearance.lineStyle == LineStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
    val edges = listOf(
        0 to 1, 0 to 2, 0 to 4, 1 to 3, 1 to 5, 2 to 3, 2 to 6, 3 to 7,
        4 to 5, 4 to 6, 5 to 7, 6 to 7,
    )
    for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
        val vertices = listOf(
            Vec3(ix.toDouble(), iy.toDouble(), iz.toDouble()), Vec3(ix + 1.0, iy.toDouble(), iz.toDouble()),
            Vec3(ix.toDouble(), iy + 1.0, iz.toDouble()), Vec3(ix + 1.0, iy + 1.0, iz.toDouble()),
            Vec3(ix.toDouble(), iy.toDouble(), iz + 1.0), Vec3(ix + 1.0, iy.toDouble(), iz + 1.0),
            Vec3(ix.toDouble(), iy + 1.0, iz + 1.0), Vec3(ix + 1.0, iy + 1.0, iz + 1.0),
        ).map { snapshot.structure.cell.toCartesian(it) - center }
            .map { rotate(it, controller.yaw, controller.pitch) }
            .map(project)
        edges.forEach { (a, b) -> drawLine(Color.Gray.copy(alpha = 0.72f), vertices[a], vertices[b], 1.4f, pathEffect = effect) }
    }
}

private fun DrawScope.drawEmptyMessage() {
    drawContext.canvas.nativeCanvas.drawText("No atoms", size.width / 2f - 60f, size.height / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.GRAY; textSize = 36f
    })
}

private fun boundingCenter(points: List<Vec3>): Vec3 = Vec3(
    (points.minOf { it.x } + points.maxOf { it.x }) / 2,
    (points.minOf { it.y } + points.maxOf { it.y }) / 2,
    (points.minOf { it.z } + points.maxOf { it.z }) / 2,
)

private fun rotate(v: Vec3, yawDegrees: Float, pitchDegrees: Float): Vec3 {
    val yaw = yawDegrees / 180.0 * PI
    val pitch = pitchDegrees / 180.0 * PI
    // pitch first so the pitch axis follows the current yaw, giving a more natural orbit feel
    val y = v.y * cos(pitch) - v.z * sin(pitch)
    val zPitch = v.y * sin(pitch) + v.z * cos(pitch)
    val x = v.x * cos(yaw) + zPitch * sin(yaw)
    val finalZ = -v.x * sin(yaw) + zPitch * cos(yaw)
    return Vec3(x, y, finalZ)
}

private fun colorFromArgb(argb: Long) = Color(argb)
