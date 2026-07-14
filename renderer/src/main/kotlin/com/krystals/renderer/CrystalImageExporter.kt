package com.krystals.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import com.krystals.core.BondColorMode
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object CrystalImageExporter {
    private data class Point(val atomId: Long, val element: String, val siteId: String, val x: Float, val y: Float, val z: Double, val radius: Float, val occupancy: Double, val fractional: Vec3, val cartesian: Vec3)

    private sealed interface RenderPrimitive {
        val depth: Double
    }

    private data class AtomPrimitive(val point: Point, val selected: Boolean) : RenderPrimitive {
        override val depth = point.z
    }

    private data class BondPrimitive(val a: Point, val b: Point, val width: Float) : RenderPrimitive {
        override val depth = (a.z + b.z) / 2.0
    }

    private data class PolyhedronPrimitive(val center: Point, val vertices: List<Point>, val adjacency: Map<Long, Set<Long>>) : RenderPrimitive {
        override val depth = center.z
    }

    fun render(
        snapshot: SceneSnapshot,
        appearance: ViewerAppearance,
        controller: ViewerController,
        visibility: ViewerVisibility,
        selectedAtomIds: List<Long>,
        measurementMode: MeasurementMode,
        measurementLocked: Boolean = false,
        inspectedAtomId: Long? = null,
        inspectionLocked: Boolean = false,
    ): Bitmap {
        val width = controller.viewportWidth.coerceIn(512, 4096)
        val height = controller.viewportHeight.coerceIn(512, 4096)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(appearance.backgroundArgb.toInt())
        val visible = snapshot.atoms.filterNot { it.siteId in visibility.hiddenSites }
        if (visible.isEmpty()) return bitmap
        val center = Vec3(
            (snapshot.atoms.minOf { it.cartesian.x } + snapshot.atoms.maxOf { it.cartesian.x }) / 2,
            (snapshot.atoms.minOf { it.cartesian.y } + snapshot.atoms.maxOf { it.cartesian.y }) / 2,
            (snapshot.atoms.minOf { it.cartesian.z } + snapshot.atoms.maxOf { it.cartesian.z }) / 2,
        )
        val allRotated = snapshot.atoms.map { rotate(it.cartesian - center, controller.yaw, controller.pitch) }
        val extentX = allRotated.maxOf { it.x } - allRotated.minOf { it.x }
        val extentY = allRotated.maxOf { it.y } - allRotated.minOf { it.y }
        val scale = min(width / max(1.0, extentX), height / max(1.0, extentY)).toFloat() * 0.72f * controller.zoom
        fun screen(v: Vec3) = Pair(width / 2f + controller.panX + v.x.toFloat() * scale, height / 2f + controller.panY - v.y.toFloat() * scale)

        drawFrames(canvas, snapshot, appearance, center, controller, scale, width, height)
        val points = visible.map { atom ->
            val rotated = rotate(atom.cartesian - center, controller.yaw, controller.pitch)
            val (x, y) = screen(rotated)
            Point(atom.id, atom.element, atom.siteId, x, y, rotated.z, (PeriodicTable.defaultRadius(atom.element).toFloat() * scale).coerceIn(4.5f, 42f), atom.occupancy, atom.fractional, atom.cartesian)
        }
        val byId = points.associateBy { it.atomId }
        val neighbors = mutableMapOf<Long, MutableList<Point>>()
        val bondAdjacency = mutableMapOf<Long, MutableSet<Long>>()
        val renderables = buildList<RenderPrimitive> {
            points.forEach { add(AtomPrimitive(it, it.atomId in selectedAtomIds)) }
            if (visibility.showBonds) {
                snapshot.bonds.forEach { bond ->
                    val a = byId[bond.atomA] ?: return@forEach
                    val b = byId[bond.atomB] ?: return@forEach
                    if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                    neighbors.getOrPut(a.atomId) { mutableListOf() } += b
                    neighbors.getOrPut(b.atomId) { mutableListOf() } += a
                    bondAdjacency.getOrPut(a.atomId) { mutableSetOf() } += b.atomId
                    bondAdjacency.getOrPut(b.atomId) { mutableSetOf() } += a.atomId
                    val width = (appearance.bondRadius * scale * 0.65f).coerceIn(1.5f, 16f)
                    add(BondPrimitive(a, b, width))
                }
            }
            if (appearance.polyhedronEnabled && visibility.polyhedronSites.isNotEmpty()) {
                points.filter { it.siteId in visibility.polyhedronSites }.forEach { center ->
                    val vertices = neighbors[center.atomId].orEmpty()
                    if (vertices.size >= 3) add(PolyhedronPrimitive(center, vertices, bondAdjacency))
                }
            }
        }.sortedBy { it.depth }

        renderables.forEach { primitive ->
            when (primitive) {
                is AtomPrimitive -> drawAtom(canvas, primitive.point, appearance, selectedAtomIds, snapshot.elementArgbOverrides)
                is BondPrimitive -> drawBond(canvas, primitive.a, primitive.b, primitive.width, appearance, snapshot.elementArgbOverrides)
                is PolyhedronPrimitive -> drawPolyhedron(canvas, primitive.center, primitive.vertices, primitive.adjacency, appearance, snapshot.elementArgbOverrides)
            }
        }
        drawMeasurement(canvas, snapshot, points, selectedAtomIds, measurementMode, measurementLocked)
        drawAtomInfo(canvas, points, inspectedAtomId, inspectionLocked)
        return bitmap
    }

    private fun drawAtom(canvas: Canvas, point: Point, appearance: ViewerAppearance, selectedAtomIds: List<Long>, elementArgbOverrides: Map<String, Long>) {
        val base = PeriodicTable.resolveArgb(point.element, elementArgbOverrides).toInt()
        val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (opacity * 255).toInt()
            shader = RadialGradient(
                point.x, point.y, point.radius,
                intArrayOf(base, darken(base, 0.65f)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(point.x, point.y, point.radius, paint)
        if (appearance.reflectionEnabled && opacity > 0.01f) {
            val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
            val elevation = appearance.lightElevation / 180f * PI.toFloat()
            val offset = point.radius * .38f * cos(elevation)
            val highlightAlpha = (appearance.lightIntensity.coerceIn(.05f, 1f) * opacity * 255).toInt()
            val highlight = Color.argb(highlightAlpha, 255, 255, 255)
            paint.shader = RadialGradient(
                point.x - cos(azimuth) * offset,
                point.y - sin(azimuth) * offset,
                point.radius * (1.1f + appearance.diffusion * .45f),
                intArrayOf(highlight, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(point.x, point.y, point.radius, paint)
        }
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = if (point.atomId in selectedAtomIds) 4f else 1f
        paint.color = if (point.atomId in selectedAtomIds) 0xFF9966CC.toInt() else 0x55000000
        canvas.drawCircle(point.x, point.y, point.radius + if (point.atomId in selectedAtomIds) 3f else 0f, paint)
    }

    private fun drawBond(canvas: Canvas, a: Point, b: Point, width: Float, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>) {
        val opacity = appearance.bondOpacity.coerceIn(0f, 1f)
        if (opacity < 0.01f) return
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) return
        val dirX = dx / length
        val dirY = dy / length
        val startX = a.x + dirX * a.radius
        val startY = a.y + dirY * a.radius
        val endX = b.x - dirX * b.radius
        val endY = b.y - dirY * b.radius
        val clippedDx = endX - startX
        val clippedDy = endY - startY
        if (sqrt(clippedDx * clippedDx + clippedDy * clippedDy) < 0.001f) return
        val perpX = -dirY
        val perpY = dirX

        val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
        val elevation = appearance.lightElevation / 180f * PI.toFloat()
        val lightX = cos(azimuth) * cos(elevation)
        val lightY = sin(azimuth) * cos(elevation)
        val lightOnPerp = (lightX * perpX + lightY * perpY).toDouble()

        val start = Point(0L, a.element, a.siteId, startX, startY, a.z, a.radius, a.occupancy, a.fractional, a.cartesian)
        val end = Point(0L, b.element, b.siteId, endX, endY, b.z, b.radius, b.occupancy, b.fractional, b.cartesian)
        if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
            val base = appearance.uniformBondArgb.toInt()
            drawBondCylinder(canvas, start, end, width, perpX, perpY, lightOnPerp, base, opacity, appearance.bondReflectionEnabled)
        } else {
            val mx = (startX + endX) / 2f
            val my = (startY + endY) / 2f
            val midA = Point(0L, a.element, a.siteId, mx, my, (a.z + b.z) / 2.0, 0f, a.occupancy, a.fractional, a.cartesian)
            val midB = Point(0L, b.element, b.siteId, mx, my, (a.z + b.z) / 2.0, 0f, b.occupancy, b.fractional, b.cartesian)
            drawBondCylinder(canvas, start, midA, width, perpX, perpY, lightOnPerp, PeriodicTable.resolveArgb(a.element, elementArgbOverrides).toInt(), opacity, appearance.bondReflectionEnabled)
            drawBondCylinder(canvas, midB, end, width, perpX, perpY, lightOnPerp, PeriodicTable.resolveArgb(b.element, elementArgbOverrides).toInt(), opacity, appearance.bondReflectionEnabled)
        }
    }

    private fun drawBondCylinder(
        canvas: Canvas,
        a: Point,
        b: Point,
        width: Float,
        perpX: Float,
        perpY: Float,
        lightOnPerp: Double,
        baseArgb: Int,
        opacity: Float,
        reflectionEnabled: Boolean,
    ) {
        val halfWidth = width / 2f
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
        val base = Color.argb((opacity * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val shadowA = darken(baseArgb, 0.55f)
        val shadowB = darken(baseArgb, 0.45f)
        val highlight = if (reflectionEnabled) lighten(baseArgb, 0.55f) else base
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = width
            strokeCap = Paint.Cap.BUTT
            shader = LinearGradient(
                mx + perpX * halfWidth,
                my + perpY * halfWidth,
                mx - perpX * halfWidth,
                my - perpY * halfWidth,
                intArrayOf(shadowA, blend(baseArgb, shadowA, 0.5f), base, highlight, base, blend(baseArgb, shadowB, 0.5f), shadowB),
                floatArrayOf(0f, (highlightPos - 0.18f).coerceIn(0.02f, 0.98f), (highlightPos - 0.08f).coerceIn(0.03f, 0.97f), highlightPos, (highlightPos + 0.08f).coerceIn(0.03f, 0.97f), (highlightPos + 0.18f).coerceIn(0.02f, 0.98f), 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }

    private fun drawPolyhedron(canvas: Canvas, center: Point, vertices: List<Point>, adjacency: Map<Long, Set<Long>>, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>) {
        if (vertices.size < 3) return
        val baseArgb = PeriodicTable.resolveArgb(center.element, elementArgbOverrides).toInt()
        val baseColor = Color.argb((appearance.polyhedronOpacity.coerceIn(0f, 1f) * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val vertexIds = vertices.map { it.atomId }
        val vertexIndex = vertexIds.withIndex().associate { it.value to it.index }

        val drawn = mutableSetOf<List<Long>>()
        val faces = mutableListOf<Triple<Point, Point, Point>>()
        vertices.forEachIndexed { i, vi ->
            val neighbors = vertexIds.mapNotNull { id ->
                if (adjacency[vi.atomId]?.contains(id) == true) vertexIndex[id] else null
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
            val normal = (vb.cartesian - va.cartesian).cross(vc.cartesian - va.cartesian)
            val toCenter = center.cartesian - va.cartesian
            val (orderedA, orderedB, orderedC) = if (normal.dot(toCenter) > 0) Triple(vc, vb, va) else Triple(va, vb, vc)
            val path = android.graphics.Path().apply {
                moveTo(orderedA.x, orderedA.y)
                lineTo(orderedB.x, orderedB.y)
                lineTo(orderedC.x, orderedC.y)
                close()
            }
            val fill = if (appearance.polyhedronReflectionEnabled) {
                val faceCenter = (orderedA.cartesian + orderedB.cartesian + orderedC.cartesian) / 3.0
                val outward = (faceCenter - center.cartesian).let { diff ->
                    val len = diff.length()
                    if (len < 1e-12) Vec3.ZERO else diff / len
                }
                val azimuth = appearance.lightAzimuth / 180.0 * PI
                val elevation = appearance.lightElevation / 180.0 * PI
                val light = Vec3(cos(azimuth) * cos(elevation), sin(azimuth) * cos(elevation), sin(elevation))
                val factor = 0.55 + 0.45 * outward.dot(light).coerceIn(-1.0, 1.0)
                Color.argb(
                    Color.alpha(baseColor),
                    (Color.red(baseColor) * factor).toInt().coerceIn(0, 255),
                    (Color.green(baseColor) * factor).toInt().coerceIn(0, 255),
                    (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255),
                )
            } else baseColor
            paint.color = fill
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }

        paint.color = Color.argb((0.35f * 255).toInt(), 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        val drawnEdges = mutableSetOf<List<Long>>()
        vertices.forEach { v ->
            adjacency[v.atomId]?.forEach { neighborId ->
                val neighbor = vertices.firstOrNull { it.atomId == neighborId } ?: return@forEach
                val key = listOf(v.atomId, neighbor.atomId).sorted()
                if (drawnEdges.add(key)) {
                    canvas.drawLine(v.x, v.y, neighbor.x, neighbor.y, paint)
                }
            }
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        return Color.argb(Color.alpha(color), (Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())
    }

    private fun lighten(color: Int, factor: Float): Int {
        return Color.argb(
            Color.alpha(color),
            (Color.red(color) + (255 - Color.red(color)) * factor).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * factor).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt(),
        )
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * ratio).toInt(),
            (Color.red(a) * inv + Color.red(b) * ratio).toInt(),
            (Color.green(a) * inv + Color.green(b) * ratio).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * ratio).toInt(),
        )
    }

    private fun drawFrames(canvas: Canvas, snapshot: SceneSnapshot, appearance: ViewerAppearance, center: Vec3, controller: ViewerController, scale: Float, width: Int, height: Int) {
        if (appearance.frameMode == FrameMode.NONE) return
        val ex = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.x else 1
        val ey = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.y else 1
        val ez = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.z else 1
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = 2f; if (appearance.lineStyle == LineStyle.DASHED) pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
        val edges = listOf(0 to 1, 0 to 2, 0 to 4, 1 to 3, 1 to 5, 2 to 3, 2 to 6, 3 to 7, 4 to 5, 4 to 6, 5 to 7, 6 to 7)
        for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
            val vertices = listOf(
                Vec3(ix.toDouble(), iy.toDouble(), iz.toDouble()), Vec3(ix + 1.0, iy.toDouble(), iz.toDouble()),
                Vec3(ix.toDouble(), iy + 1.0, iz.toDouble()), Vec3(ix + 1.0, iy + 1.0, iz.toDouble()),
                Vec3(ix.toDouble(), iy.toDouble(), iz + 1.0), Vec3(ix + 1.0, iy.toDouble(), iz + 1.0),
                Vec3(ix.toDouble(), iy + 1.0, iz + 1.0), Vec3(ix + 1.0, iy + 1.0, iz + 1.0),
            ).map { rotate(snapshot.structure.cell.toCartesian(it) - center, controller.yaw, controller.pitch) }
                .map { Pair(width / 2f + controller.panX + it.x.toFloat() * scale, height / 2f + controller.panY - it.y.toFloat() * scale) }
            edges.forEach { (a, b) -> canvas.drawLine(vertices[a].first, vertices[a].second, vertices[b].first, vertices[b].second, paint) }
        }
    }

    private fun drawMeasurement(canvas: Canvas, snapshot: SceneSnapshot, points: List<Point>, ids: List<Long>, mode: MeasurementMode, locked: Boolean) {
        val count = when (mode) { MeasurementMode.LENGTH -> 2; MeasurementMode.ANGLE -> 3; MeasurementMode.DIHEDRAL -> 4; else -> 0 }
        if (count == 0 || ids.size < count) return
        val selected = ids.takeLast(count).mapNotNull { id -> points.firstOrNull { it.atomId == id } }
        if (selected.size != count) return
        val atoms = snapshot.atoms.associateBy { it.id }
        val positions = selected.mapNotNull { atoms[it.atomId]?.cartesian }
        val label = when (mode) {
            MeasurementMode.LENGTH -> "%.4f Å".format(distance(positions[0], positions[1]))
            MeasurementMode.ANGLE -> "%.3f°".format(angleDegrees(positions[0], positions[1], positions[2]))
            MeasurementMode.DIHEDRAL -> "%.3f°".format(dihedralDegrees(positions[0], positions[1], positions[2], positions[3]))
            else -> return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; setShadowLayer(4f, 1f, 1f, Color.BLACK) }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(label, 0, label.length, bounds)
        val anchorX = selected.map { it.x }.average().toFloat()
        val anchorY = selected.map { it.y }.average().toFloat()
        val pad = 16f
        val boxLeft = anchorX + 10f - pad
        val boxTop = anchorY - 10f - bounds.height() - pad
        val boxRight = anchorX + 10f + bounds.width() + pad
        val boxBottom = anchorY - 10f + pad
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (locked) Color.argb((0.82f * 255).toInt(), 153, 102, 204) else Color.argb((0.65f * 255).toInt(), 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, 14f, 14f, boxPaint)
        canvas.drawText(label, anchorX + 10f, anchorY - 10f, paint)
    }

    private fun drawAtomInfo(canvas: Canvas, points: List<Point>, inspectedAtomId: Long?, locked: Boolean) {
        val atom = inspectedAtomId?.let { id -> points.firstOrNull { it.atomId == id } } ?: return
        val label = "${atom.element}  occ ${atom.occupancy}\n(${atom.fractional.x.formatFract()}, ${atom.fractional.y.formatFract()}, ${atom.fractional.z.formatFract()})"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; setShadowLayer(4f, 1f, 1f, Color.BLACK) }
        val lines = label.split('\n')
        val widths = lines.map { line -> paint.measureText(line) }
        val maxWidth = widths.maxOrNull() ?: 0f
        val lineHeight = paint.fontMetrics.run { descent - ascent }
        val pad = 16f
        val boxLeft = atom.x + atom.radius + 14f
        val boxTop = atom.y - atom.radius - 14f - lines.size * lineHeight - pad
        val boxRight = boxLeft + maxWidth + pad * 2
        val boxBottom = atom.y - atom.radius - 14f + pad
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (locked) Color.argb((0.82f * 255).toInt(), 153, 102, 204) else Color.argb((0.65f * 255).toInt(), 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, 14f, 14f, boxPaint)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, boxLeft + pad, boxTop + pad + (index + 1) * lineHeight - paint.fontMetrics.descent, paint)
        }
    }

    private fun Double.formatFract() = "%.4f".format(this)

    private fun rotate(v: Vec3, yawDegrees: Float, pitchDegrees: Float): Vec3 {
        val yaw = yawDegrees / 180.0 * PI; val pitch = pitchDegrees / 180.0 * PI
        val y = v.y * cos(pitch) - v.z * sin(pitch)
        val zPitch = v.y * sin(pitch) + v.z * cos(pitch)
        val x = v.x * cos(yaw) + zPitch * sin(yaw)
        val finalZ = -v.x * sin(yaw) + zPitch * cos(yaw)
        return Vec3(x, y, finalZ)
    }
}
