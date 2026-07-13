package com.krystals.renderer

import android.graphics.Paint
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.krystals.core.Vec3
import com.krystals.core.ViewerAppearance
import com.krystals.core.angleDegrees
import com.krystals.core.dihedralDegrees
import com.krystals.core.distance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class MeasurementMode { NONE, LENGTH, ANGLE, DIHEDRAL }

data class ViewerVisibility(
    val hiddenSites: Set<String> = emptySet(),
    val hiddenBondPairs: Set<String> = emptySet(),
    val polyhedronSites: Set<String> = emptySet(),
    val showBonds: Boolean = true,
)

internal data class ProjectedAtom(val atom: ExpandedAtom, val point: Offset, val depth: Double, val radius: Float)

@Stable
class ViewerController {
    var yaw by mutableFloatStateOf(-28f)
    var pitch by mutableFloatStateOf(22f)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var locked by mutableStateOf(false)
    internal var projectedAtoms: List<ProjectedAtom> = emptyList()

    fun align(axis: Char) {
        when (axis.lowercaseChar()) {
            'x' -> { yaw = -90f; pitch = 0f }
            'y' -> { yaw = 0f; pitch = 90f }
            else -> { yaw = 0f; pitch = 0f }
        }
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

@Composable
fun CrystalViewport(
    snapshot: SceneSnapshot,
    appearance: ViewerAppearance,
    modifier: Modifier = Modifier,
    controller: ViewerController = rememberViewerController(),
    visibility: ViewerVisibility = ViewerVisibility(),
    selectedAtomIds: List<Long> = emptyList(),
    measurementMode: MeasurementMode = MeasurementMode.NONE,
    onAtomTap: (ExpandedAtom) -> Unit = {},
    onViewMoved: () -> Unit = {},
) {
    val background = colorFromArgb(appearance.backgroundArgb)
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(snapshot, controller.locked) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val down = first.position
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val movement = event.changes.sumOf { it.position.minus(it.previousPosition).getDistance().toDouble() }
                        if (movement > 2.0) moved = true
                        if (!controller.locked) {
                            if (pressed.size == 1) {
                                val delta = pressed.first().position - pressed.first().previousPosition
                                if (delta.getDistance() > 0f) {
                                    controller.yaw += delta.x * 0.32f
                                    controller.pitch = (controller.pitch + delta.y * 0.32f).coerceIn(-89f, 89f)
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
                            if (!moved) controller.pick(down)?.let(onAtomTap)
                            break
                        }
                    }
                }
            },
    ) {
        drawRect(background)
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
        if (visibility.showBonds) {
            snapshot.bonds.forEach { bond ->
                val a = byId[bond.atomA] ?: return@forEach
                val b = byId[bond.atomB] ?: return@forEach
                if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                val width = (appearance.bondRadius * scale * 0.65f).coerceIn(1.5f, 16f)
                drawBond(a, b, width, appearance)
            }
        }
        drawPolyhedra(snapshot, projected, visibility)
        projected.sortedBy { it.depth }.forEach { atom -> drawAtom(atom, atom.atom.id in selectedAtomIds, appearance) }
        drawMeasurement(projected, selectedAtomIds, measurementMode)
        controller.projectedAtoms = projected
    }
}

private fun DrawScope.drawAtom(atom: ProjectedAtom, selected: Boolean, appearance: ViewerAppearance) {
    val base = colorFromArgb(PeriodicTable.vestaArgb(atom.atom.element))
    val brush = if (appearance.reflectionEnabled) Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = appearance.lightIntensity.coerceIn(0.15f, 0.9f)), base),
        center = atom.point - Offset(atom.radius * 0.32f, atom.radius * 0.34f),
        radius = atom.radius * (1.1f + appearance.diffusion * 0.45f),
    ) else Brush.radialGradient(listOf(base, base), atom.point, atom.radius)
    drawCircle(brush, atom.radius, atom.point)
    drawCircle(Color.Black.copy(alpha = 0.28f), atom.radius, atom.point, style = Stroke(max(0.8f, atom.radius * 0.045f)))
    if (selected) drawCircle(Color(0xFF9966CC), atom.radius + 4f, atom.point, style = Stroke(3f))
}

private fun DrawScope.drawBond(a: ProjectedAtom, b: ProjectedAtom, width: Float, appearance: ViewerAppearance) {
    if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
        drawLine(colorFromArgb(appearance.uniformBondArgb), a.point, b.point, width)
    } else {
        val midpoint = Offset((a.point.x + b.point.x) / 2f, (a.point.y + b.point.y) / 2f)
        drawLine(colorFromArgb(PeriodicTable.vestaArgb(a.atom.element)), a.point, midpoint, width)
        drawLine(colorFromArgb(PeriodicTable.vestaArgb(b.atom.element)), midpoint, b.point, width)
    }
}

private fun DrawScope.drawPolyhedra(snapshot: SceneSnapshot, projected: List<ProjectedAtom>, visibility: ViewerVisibility) {
    if (visibility.polyhedronSites.isEmpty()) return
    val byId = projected.associateBy { it.atom.id }
    val neighbors = mutableMapOf<Long, MutableList<ProjectedAtom>>()
    snapshot.bonds.forEach { bond ->
        val a = byId[bond.atomA] ?: return@forEach
        val b = byId[bond.atomB] ?: return@forEach
        neighbors.getOrPut(a.atom.id) { mutableListOf() } += b
        neighbors.getOrPut(b.atom.id) { mutableListOf() } += a
    }
    projected.filter { it.atom.siteId in visibility.polyhedronSites }.forEach { center ->
        val vertices = neighbors[center.atom.id].orEmpty()
        if (vertices.size < 4) return@forEach
        val sorted = vertices.sortedBy { atan2((it.point.y - center.point.y).toDouble(), (it.point.x - center.point.x).toDouble()) }
        val path = Path().apply {
            moveTo(sorted.first().point.x, sorted.first().point.y)
            sorted.drop(1).forEach { lineTo(it.point.x, it.point.y) }
            close()
        }
        drawPath(path, colorFromArgb(PeriodicTable.vestaArgb(center.atom.element)).copy(alpha = 0.22f))
        drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(1.2f))
    }
}

private fun DrawScope.drawMeasurement(projected: List<ProjectedAtom>, ids: List<Long>, mode: MeasurementMode) {
    val expected = when (mode) { MeasurementMode.LENGTH -> 2; MeasurementMode.ANGLE -> 3; MeasurementMode.DIHEDRAL -> 4; else -> 0 }
    if (expected == 0 || ids.size < expected) return
    val points = ids.takeLast(expected).mapNotNull { id -> projected.firstOrNull { it.atom.id == id } }
    if (points.size != expected) return
    for (index in 0 until points.lastIndex) drawLine(Color(0xFFE1C6FF), points[index].point, points[index + 1].point, 2.5f)
    val label = when (mode) {
        MeasurementMode.LENGTH -> "%.4f Å".format(distance(points[0].atom.cartesian, points[1].atom.cartesian))
        MeasurementMode.ANGLE -> "%.3f°".format(angleDegrees(points[0].atom.cartesian, points[1].atom.cartesian, points[2].atom.cartesian))
        MeasurementMode.DIHEDRAL -> "%.3f°".format(dihedralDegrees(points[0].atom.cartesian, points[1].atom.cartesian, points[2].atom.cartesian, points[3].atom.cartesian))
        else -> return
    }
    val anchor = points.map { it.point }.reduce { a, b -> a + b } / points.size.toFloat()
    drawContext.canvas.nativeCanvas.drawText(label, anchor.x + 12f, anchor.y - 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
    })
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
    val x = v.x * cos(yaw) + v.z * sin(yaw)
    val z = -v.x * sin(yaw) + v.z * cos(yaw)
    val y = v.y * cos(pitch) - z * sin(pitch)
    val finalZ = v.y * sin(pitch) + z * cos(pitch)
    return Vec3(x, y, finalZ)
}

private fun colorFromArgb(argb: Long) = Color(argb)
