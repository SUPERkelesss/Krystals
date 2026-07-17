package com.krystals.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import com.krystals.core.AxisMode
import com.krystals.core.BondColorMode
import com.krystals.core.FrameMode
import com.krystals.core.Int3
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
import kotlin.math.sqrt

object CrystalImageExporter {
    private data class Point(val atomId: Long, val element: String, val siteId: String, val siteLabel: String, val x: Float, val y: Float, val z: Double, val radius: Float, val occupancy: Double, val fractional: Vec3, val cartesian: Vec3, val cellOffset: Int3, val isShell: Boolean, val isBoundaryImage: Boolean = false) {
        val isExternalShell: Boolean get() = isShell && !isBoundaryImage
    }

    private sealed interface RenderPrimitive {
        val depth: Double
    }

    private data class AtomPrimitive(val point: Point, val selected: Boolean) : RenderPrimitive {
        override val depth = point.z
    }

    private data class BondPrimitive(val a: Point, val b: Point, val width: Float) : RenderPrimitive {
        override val depth = (a.z + b.z) / 2.0
    }

    private data class PolyhedronPrimitive(val center: Point, val vertices: List<Point>, val adjacency: Map<Long, Set<Long>>, val depthOverride: Double? = null) : RenderPrimitive {
        override val depth = depthOverride ?: center.z
    }

    private data class PolyhedronFacePrimitive(
        val center: Point,
        val faceVerts: List<Point>,
        val normal: Vec3,
        val alphaScale: Float,
        override val depth: Double,
    ) : RenderPrimitive

    private fun drawPolyhedronFacePrimitives(
        center: Point,
        vertices: List<Point>,
        adjacency: Map<Long, Set<Long>>,
        appearance: ViewerAppearance,
        elementArgbOverrides: Map<String, Long>,
        siteArgbOverrides: Map<String, Long>,
        yaw: Float,
        pitch: Float,
    ): List<PolyhedronFacePrimitive> {
        if (vertices.size < 3) return emptyList()
        val hullFaces = convexHullFaces(center.cartesian, vertices.map { it.cartesian })
        val faces = hullFaces.map { poly -> poly.map { p -> vertices.first { it.cartesian == p } } }
        val allCoplanar = vertices.size >= 3 && run {
            val v0 = vertices[0].cartesian
            val n = (vertices[1].cartesian - v0).cross(vertices[2].cartesian - v0)
            if (n.lengthSquared() < 1e-12) return@run false
            vertices.drop(3).all { abs(n.dot(it.cartesian - v0)) < 1e-6 }
        }
        val result = mutableListOf<PolyhedronFacePrimitive>()
        faces.forEach { faceVerts ->
            if (faceVerts.size < 3) return@forEach
            val va = faceVerts[0]; val vb = faceVerts[1]; val vc = faceVerts[2]
            val cross = (vb.cartesian - va.cartesian).cross(vc.cartesian - va.cartesian)
            val toCenter = center.cartesian - va.cartesian
            var outward = cross
            if (outward.dot(toCenter) > 0) outward = outward * -1.0
            val len = outward.length()
            if (len < 1e-12) return@forEach
            val normal = outward / len
            val ordered = if (cross.dot(normal) < 0) faceVerts.reversed() else faceVerts
            val nearestDepth = ordered.minOf { it.z }
            result += PolyhedronFacePrimitive(center, ordered, normal, 1.0f, nearestDepth)
            if (allCoplanar) result += PolyhedronFacePrimitive(center, ordered.reversed(), normal * -1.0, 0.4f, nearestDepth)
        }
        return result
    }

    fun render(
        snapshot: SceneSnapshot,
        appearance: ViewerAppearance,
        controller: ViewerController,
        visibility: ViewerVisibility,
        selectedAtomIds: List<Long>,
        measurementMode: MeasurementMode,
        inspectedAtomId: Long? = null,
        lockedMeasurements: List<LockedMeasurement> = emptyList(),
        lockedInspectedAtomIds: List<Long> = emptyList(),
    ): Bitmap {
        val width = controller.viewportWidth.coerceIn(512, 4096)
        val height = controller.viewportHeight.coerceIn(512, 4096)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(appearance.backgroundArgb.toInt())
        if (snapshot.atoms.isEmpty()) return bitmap
        // Per v0.3.41: boundary images are displayed by default; only external shell atoms remain
        // hidden unless a bond rule opts in to "extend across cell".
        val visibleExternalShellAtomIds = mutableSetOf<Long>()
        if (visibility.showBonds) {
            snapshot.bonds.forEach { bond ->
                if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                val b = snapshot.atoms.firstOrNull { it.id == bond.atomB } ?: return@forEach
                if (b.isExternalShell && bond.rule.extendAcrossCell && b.siteId !in visibility.hiddenSites) {
                    visibleExternalShellAtomIds += b.id
                }
            }
        }
        val visible = snapshot.atoms.filter {
            (!it.isShell && it.siteId !in visibility.hiddenSites) ||
                (it.isBoundaryImage && it.siteId !in visibility.hiddenSites) ||
                (it.isExternalShell && it.id in visibleExternalShellAtomIds)
        }
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
        if (appearance.showAxes) drawAxes(canvas, snapshot, appearance, controller, width, height)
        // Per v0.3.0: project ALL atoms (so bonds/polyhedra survive hiding an atom); render only
        // visible atoms as AtomPrimitive.
        val points = snapshot.atoms.map { atom ->
            val rotated = rotate(atom.cartesian - center, controller.yaw, controller.pitch)
            val (x, y) = screen(rotated)
            Point(atom.id, atom.element, atom.siteId, atom.siteLabel, x, y, rotated.z, (PeriodicTable.defaultRadius(atom.element).toFloat() * scale).coerceIn(4.5f, 42f), atom.occupancy, atom.fractional, atom.cartesian, atom.cellOffset, atom.isShell, atom.isBoundaryImage)
        }
        val visiblePointIds = visible.map { it.id }.toSet()
        val visiblePoints = points.filter { it.atomId in visiblePointIds }
        val byId = points.associateBy { it.atomId }
        val neighbors = mutableMapOf<Long, MutableList<Point>>()
        val bondAdjacency = mutableMapOf<Long, MutableSet<Long>>()
        val renderables = buildList<RenderPrimitive> {
            visiblePoints.forEach { add(AtomPrimitive(it, it.atomId in selectedAtomIds)) }
            if (visibility.showBonds) {
                snapshot.bonds.forEach { bond ->
                    val a = byId[bond.atomA] ?: return@forEach
                    val b = byId[bond.atomB] ?: return@forEach
                    if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                    // Per v0.3.43: a bond to a boundary image draws by default; only a bond to a
                    // genuine external shell atom is gated on extendAcrossCell.
                    val externalBond = b.isExternalShell
                    // Polyhedron vertices: every bond registers its vertices (in-cell and cross-cell)
                    // so polyhedra keep full coordination; cross-cell vertices extend outside the cell.
                    neighbors.getOrPut(a.atomId) { mutableListOf() } += b
                    neighbors.getOrPut(b.atomId) { mutableListOf() } += a
                    bondAdjacency.getOrPut(a.atomId) { mutableSetOf() } += b.atomId
                    bondAdjacency.getOrPut(b.atomId) { mutableSetOf() } += a.atomId
                    // Bond-line rendering: an external-shell bond draws only when its rule opts in via
                    // extendAcrossCell. Boundary-image bonds are drawn by default.
                    if (externalBond && !bond.rule.extendAcrossCell) return@forEach
                    val width = (appearance.bondRadius * scale * 0.65f).coerceIn(1.5f, 16f)
                    add(BondPrimitive(a, b, width))
                    if (externalBond && b.atomId in visibleExternalShellAtomIds) {
                        add(AtomPrimitive(b, b.atomId in selectedAtomIds))
                    }
                }
            }
            if (appearance.polyhedronEnabled && visibility.polyhedronSites.isNotEmpty()) {
                points.filter {
                    (!it.isShell || it.isBoundaryImage) && it.siteId in visibility.polyhedronSites
                }.forEach { center ->
                    val vertices = neighbors[center.atomId].orEmpty()
                    if (vertices.size >= 3) {
                        // Per v0.3.43: draw each face as its own primitive sorted by the face's nearest
                        // vertex depth (see drawPolyhedron), instead of one block at the centre depth.
                        drawPolyhedronFacePrimitives(center, vertices, bondAdjacency, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.yaw, controller.pitch).forEach { add(it) }
                    }
                }
            }
        }.sortedBy { it.depth }

        renderables.forEach { primitive ->
            when (primitive) {
                is AtomPrimitive -> drawAtom(canvas, primitive.point, appearance, selectedAtomIds, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides)
                is BondPrimitive -> drawBond(canvas, primitive.a, primitive.b, primitive.width, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, visibility.hiddenSites)
                is PolyhedronPrimitive -> drawPolyhedron(canvas, primitive.center, primitive.vertices, primitive.adjacency, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.yaw, controller.pitch)
                is PolyhedronFacePrimitive -> drawPolyhedronFacePrimitive(canvas, primitive, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.yaw, controller.pitch)
            }
        }
        // Per v0.3.0: draw locked (persistent) + active measurement/info windows.
        lockedMeasurements.forEach { m -> drawMeasurement(canvas, snapshot, points, m.atomIds, m.mode, true) }
        drawMeasurement(canvas, snapshot, points, selectedAtomIds, measurementMode, false)
        lockedInspectedAtomIds.forEach { id -> drawAtomInfo(canvas, points, id, true) }
        if (inspectedAtomId != null && inspectedAtomId !in lockedInspectedAtomIds) drawAtomInfo(canvas, points, inspectedAtomId, false)
        return bitmap
    }

    private fun drawAtom(canvas: Canvas, point: Point, appearance: ViewerAppearance, selectedAtomIds: List<Long>, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap()) {
        val base = PeriodicTable.resolveSiteArgb(point.siteId, point.element, siteArgbOverrides, elementArgbOverrides).toInt()
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

    private fun drawBond(canvas: Canvas, a: Point, b: Point, width: Float, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap(), hiddenSites: Set<String> = emptySet()) {
        val opacity = appearance.bondOpacity.coerceIn(0f, 1f)
        if (opacity < 0.01f) return
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) return
        val dirX = dx / length
        val dirY = dy / length
        // Per v0.3.2: extend the bond to a hidden atom's center (its ball isn't drawn, so starting
        // at its surface would leave a gap); visible atoms keep the bond starting at their surface.
        val aHidden = a.siteId in hiddenSites
        val bHidden = b.siteId in hiddenSites
        val startX = a.x + dirX * if (aHidden) 0f else a.radius
        val startY = a.y + dirY * if (aHidden) 0f else a.radius
        val endX = b.x - dirX * if (bHidden) 0f else b.radius
        val endY = b.y - dirY * if (bHidden) 0f else b.radius
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

        val start = Point(0L, a.element, a.siteId, a.siteLabel, startX, startY, a.z, a.radius, a.occupancy, a.fractional, a.cartesian, a.cellOffset, a.isShell, a.isBoundaryImage)
        val end = Point(0L, b.element, b.siteId, b.siteLabel, endX, endY, b.z, b.radius, b.occupancy, b.fractional, b.cartesian, b.cellOffset, b.isShell, b.isBoundaryImage)
        if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
            val base = appearance.uniformBondArgb.toInt()
            drawBondCylinder(canvas, start, end, width, perpX, perpY, lightOnPerp, base, opacity, appearance.bondReflectionEnabled)
        } else {
            val mx = (startX + endX) / 2f
            val my = (startY + endY) / 2f
            val midA = Point(0L, a.element, a.siteId, a.siteLabel, mx, my, (a.z + b.z) / 2.0, 0f, a.occupancy, a.fractional, a.cartesian, a.cellOffset, a.isShell, a.isBoundaryImage)
            val midB = Point(0L, b.element, b.siteId, b.siteLabel, mx, my, (a.z + b.z) / 2.0, 0f, b.occupancy, b.fractional, b.cartesian, b.cellOffset, b.isShell, b.isBoundaryImage)
            drawBondCylinder(canvas, start, midA, width, perpX, perpY, lightOnPerp, PeriodicTable.resolveSiteArgb(a.siteId, a.element, siteArgbOverrides, elementArgbOverrides).toInt(), opacity, appearance.bondReflectionEnabled)
            drawBondCylinder(canvas, midB, end, width, perpX, perpY, lightOnPerp, PeriodicTable.resolveSiteArgb(b.siteId, b.element, siteArgbOverrides, elementArgbOverrides).toInt(), opacity, appearance.bondReflectionEnabled)
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

    private fun drawPolyhedron(canvas: Canvas, center: Point, vertices: List<Point>, adjacency: Map<Long, Set<Long>>, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap(), yaw: Float, pitch: Float) {
        if (vertices.size < 3) return
        val baseArgb = PeriodicTable.resolveSiteArgb(center.siteId, center.element, siteArgbOverrides, elementArgbOverrides).toInt()
        val baseColor = Color.argb((appearance.polyhedronOpacity.coerceIn(0f, 1f) * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Per v0.3.0: lighting is screen-space — rotate the world outward normal into camera space
        // (same rotate() as atoms) so polyhedron shading tracks the view like atom highlights.
        // Per v0.3.2: back faces are always culled (not just nearly-opaque) so translucent back
        // faces don't paint over front atoms.
        fun faceShade(outwardWorld: Vec3): Double {
            val cam = rotate(outwardWorld, yaw, pitch)
            if (cam.z <= 0.0) return -1.0 // signal: cull back face
            val azimuth = appearance.lightAzimuth / 180.0 * PI
            val elevation = appearance.lightElevation / 180.0 * PI
            val light = Vec3(cos(azimuth) * cos(elevation), sin(azimuth) * cos(elevation), sin(elevation))
            return 0.5 + 0.5 * cam.dot(light).coerceIn(0.0, 1.0)
        }
        fun shade(base: Int, factor: Double) = Color.argb(
            Color.alpha(base),
            (Color.red(base) * factor).toInt().coerceIn(0, 255),
            (Color.green(base) * factor).toInt().coerceIn(0, 255),
            (Color.blue(base) * factor).toInt().coerceIn(0, 255),
        )

        // Per v0.3.41: always use convexHullFaces so coplanar ligands produce a single correctly-
        // ordered polygon (triangle, quad, etc.) instead of the allMutuallyAdjacent shortcut.
        val hullFaces = convexHullFaces(center.cartesian, vertices.map { it.cartesian })
        val faces = hullFaces.map { poly -> poly.map { p -> vertices.first { it.cartesian == p } } }

        // For a flat coordination (e.g. trigonal-planar CO3 or square-planar) the polyhedron is a
        // single polygon. Back-face culling would hide it when viewed from the centre-atom side, so
        // emit each face twice — once with the outward normal and once with the reversed normal.
        val allCoplanar = vertices.size >= 3 && run {
            val v0 = vertices[0].cartesian
            val n = (vertices[1].cartesian - v0).cross(vertices[2].cartesian - v0)
            if (n.lengthSquared() < 1e-12) return@run false
            vertices.drop(3).all { abs(n.dot(it.cartesian - v0)) < 1e-6 }
        }

        val drawn = mutableSetOf<List<Long>>()
        paint.style = Paint.Style.FILL
        faces.forEach { faceVerts ->
            if (faceVerts.size < 3) return@forEach
            val va = faceVerts[0]; val vb = faceVerts[1]; val vc = faceVerts[2]
            val cross = (vb.cartesian - va.cartesian).cross(vc.cartesian - va.cartesian)
            val toCenter = center.cartesian - va.cartesian
            // Per v0.3.42: toCenter points toward the centre atom, so outward (away from centre)
            // has a NEGATIVE dot with toCenter. Flip only when dot > 0 (was inverted before).
            var outward = cross
            if (outward.dot(toCenter) > 0) outward = outward * -1.0
            val len = outward.length()
            if (len < 1e-12) return@forEach
            val normal = outward / len
            // convexHullFaces orders vertices CCW around the outward normal; if the cross product
            // disagrees, reverse the order so the emitted face matches the outward normal.
            val ordered = if (cross.dot(normal) < 0) faceVerts.reversed() else faceVerts

            fun emit(verts: List<Point>, dir: Vec3, alphaScale: Float = 1.0f) {
                val factor = if (appearance.polyhedronReflectionEnabled) faceShade(dir) else {
                    val cam = rotate(dir, yaw, pitch)
                    if (cam.z <= 0.0) -1.0 else 1.0
                }
                if (factor < 0) return
                val srcFill = if (appearance.polyhedronReflectionEnabled) shade(baseColor, factor) else baseColor
                val fill = if (alphaScale < 1.0f) Color.argb(
                    (Color.alpha(srcFill) * alphaScale).toInt().coerceIn(0, 255),
                    Color.red(srcFill), Color.green(srcFill), Color.blue(srcFill),
                ) else srcFill
                val path = android.graphics.Path().apply {
                    moveTo(verts[0].x, verts[0].y)
                    for (i in 1 until verts.size) lineTo(verts[i].x, verts[i].y)
                    close()
                }
                paint.style = Paint.Style.FILL
                paint.color = fill
                canvas.drawPath(path, paint)
                paint.color = Color.argb((0.35f * 255).toInt(), 255, 255, 255)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                for (i in verts.indices) {
                    val a = verts[i]
                    val b = verts[(i + 1) % verts.size]
                    val key = listOf(a.atomId, b.atomId).sorted()
                    if (drawn.add(key)) canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                }
                paint.style = Paint.Style.FILL
            }

            emit(ordered, normal)
            // Per v0.3.41: for flat coordinations, also emit the reversed face so the polygon is
            // visible from the centre-atom side, drawn translucent (back side).
            if (allCoplanar) emit(ordered.reversed(), normal * -1.0, alphaScale = 0.4f)
        }
    }

    private fun drawPolyhedronFacePrimitive(
        canvas: Canvas,
        face: PolyhedronFacePrimitive,
        appearance: ViewerAppearance,
        elementArgbOverrides: Map<String, Long>,
        siteArgbOverrides: Map<String, Long>,
        yaw: Float,
        pitch: Float,
    ) {
        val verts = face.faceVerts
        if (verts.size < 3) return
        val baseArgb = PeriodicTable.resolveSiteArgb(face.center.siteId, face.center.element, siteArgbOverrides, elementArgbOverrides).toInt()
        val baseColor = Color.argb((appearance.polyhedronOpacity.coerceIn(0f, 1f) * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val cam = rotate(face.normal, yaw, pitch)
        if (cam.z <= 0.0) return // back face culled
        val factor = if (appearance.polyhedronReflectionEnabled) {
            val azimuth = appearance.lightAzimuth / 180.0 * PI
            val elevation = appearance.lightElevation / 180.0 * PI
            val light = Vec3(cos(azimuth) * cos(elevation), sin(azimuth) * cos(elevation), sin(elevation))
            0.5 + 0.5 * cam.dot(light).coerceIn(0.0, 1.0)
        } else 1.0
        var fill = if (appearance.polyhedronReflectionEnabled) Color.argb(
            Color.alpha(baseColor),
            (Color.red(baseColor) * factor).toInt().coerceIn(0, 255),
            (Color.green(baseColor) * factor).toInt().coerceIn(0, 255),
            (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255),
        ) else baseColor
        if (face.alphaScale < 1.0f) {
            fill = Color.argb((Color.alpha(fill) * face.alphaScale).toInt().coerceIn(0, 255), Color.red(fill), Color.green(fill), Color.blue(fill))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = android.graphics.Path().apply {
            moveTo(verts[0].x, verts[0].y)
            for (i in 1 until verts.size) lineTo(verts[i].x, verts[i].y)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        paint.color = Color.argb((0.35f * 255).toInt(), 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        val drawn = mutableSetOf<List<Long>>()
        for (i in verts.indices) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            if (drawn.add(listOf(a.atomId, b.atomId).sorted())) canvas.drawLine(a.x, a.y, b.x, b.y, paint)
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

    private fun drawAxes(canvas: Canvas, snapshot: SceneSnapshot, appearance: ViewerAppearance, controller: ViewerController, width: Int, height: Int) {
        val directions: List<Vec3> = when (appearance.axisMode) {
            AxisMode.ABC -> listOf(snapshot.structure.cell.matrix.a, snapshot.structure.cell.matrix.b, snapshot.structure.cell.matrix.c)
            AxisMode.XYZ -> listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        }
        val labels = when (appearance.axisMode) {
            AxisMode.ABC -> listOf("a", "b", "c")
            AxisMode.XYZ -> listOf("X", "Y", "Z")
        }
        val colors = listOf(0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt())
        val origin = PointF(width * 0.08f + 28f, height * 0.08f + 40f)
        val arrowLen = 56f
        val halfWidth = 3f
        val headLen = 16f
        val azimuth = appearance.lightAzimuth / 180f * PI.toFloat()
        val elevation = appearance.lightElevation / 180f * PI.toFloat()
        val lightX = cos(azimuth) * cos(elevation)
        val lightY = sin(azimuth) * cos(elevation)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; textSize = 30f; setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        directions.forEachIndexed { index, dir ->
            val rotated = rotate(dir, controller.yaw, controller.pitch)
            val projected = PointF(rotated.x.toFloat(), -rotated.y.toFloat())
            val len = sqrt(projected.x * projected.x + projected.y * projected.y)
            val unitX = if (len > 0.0001f) projected.x / len else 0f
            val unitY = if (len > 0.0001f) projected.y / len else 0f
            val tipX = origin.x + unitX * arrowLen
            val tipY = origin.y + unitY * arrowLen
            val color = colors[index]
            val perpX = -unitY
            val perpY = unitX
            val lightOnPerp = (lightX * perpX + lightY * perpY).toDouble()
            // 3D cylinder shaft, lit the same way bonds are.
            val shaftEndX = tipX - unitX * headLen
            val shaftEndY = tipY - unitY * headLen
            val shaftA = Point(0L, "", "", "", origin.x, origin.y, 0.0, 0f, 0.0, Vec3.ZERO, Vec3.ZERO, Int3(0, 0, 0), false, false)
            val shaftB = Point(0L, "", "", "", shaftEndX, shaftEndY, 0.0, 0f, 0.0, Vec3.ZERO, Vec3.ZERO, Int3(0, 0, 0), false, false)
            drawBondCylinder(canvas, shaftA, shaftB, halfWidth * 2f, perpX, perpY, lightOnPerp, color, 1f, appearance.bondReflectionEnabled)
            // Conical arrowhead with the same lit gradient.
            val baseX = shaftEndX
            val baseY = shaftEndY
            val headHalf = headLen * 0.6f
            val path = android.graphics.Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseX + perpX * headHalf, baseY + perpY * headHalf)
                lineTo(baseX - perpX * headHalf, baseY - perpY * headHalf)
                close()
            }
            val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
            val shadowA = darken(color, 0.55f)
            val shadowB = darken(color, 0.45f)
            val highlight = if (appearance.bondReflectionEnabled) lighten(color, 0.55f) else color
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                baseX + perpX * headHalf, baseY + perpY * headHalf,
                baseX - perpX * headHalf, baseY - perpY * headHalf,
                intArrayOf(shadowA, blend(color, shadowA, 0.5f), color, highlight, color, blend(color, shadowB, 0.5f), shadowB),
                floatArrayOf(0f, (highlightPos - 0.18f).coerceIn(0.02f, 0.98f), (highlightPos - 0.08f).coerceIn(0.03f, 0.97f), highlightPos, (highlightPos + 0.08f).coerceIn(0.03f, 0.97f), (highlightPos + 0.18f).coerceIn(0.02f, 0.98f), 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.color = Color.WHITE
            canvas.drawText(labels[index], tipX + unitX * 8f - 6f, tipY + unitY * 8f + 10f, paint)
        }
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
        val label = "${atom.element}  ${atom.siteLabel}  occ ${atom.occupancy}\n(${atom.fractional.x.formatFract()}, ${atom.fractional.y.formatFract()}, ${atom.fractional.z.formatFract()})"
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
        val yaw = yawDegrees / 180.0 * PI
        val pitch = pitchDegrees / 180.0 * PI
        val y = v.y * cos(pitch) - v.z * sin(pitch)
        val zPitch = v.y * sin(pitch) + v.z * cos(pitch)
        val x = v.x * cos(yaw) + zPitch * sin(yaw)
        val finalZ = -v.x * sin(yaw) + zPitch * cos(yaw)
        return Vec3(x, y, finalZ)
    }
}
