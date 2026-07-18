package com.krystals.renderer

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import com.krystals.core.LineStyle
import com.krystals.core.Mat3
import com.krystals.core.PeriodicTable
import com.krystals.core.SceneSnapshot
import com.krystals.core.Vec3
import com.krystals.core.ViewerAppearance
import com.krystals.core.angleDegrees
import com.krystals.core.dihedralDegrees
import com.krystals.core.distance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object CrystalImageExporter {

    /** Per v0.5.2: depth-of-field factors for a single primitive (mirror of CrystalViewport.DofFactor). */
    private data class DofFactor(val blur: Float, val fog: Float) {
        companion object { val NONE = DofFactor(0f, 0f) }
    }

    private data class Point(val atomId: Long, val element: String, val siteId: String, val siteLabel: String, val x: Float, val y: Float, val z: Double, val radius: Float, val occupancy: Double, val fractional: Vec3, val cartesian: Vec3, val isShell: Boolean, val isBoundaryImage: Boolean = false) {
        val isExternalShell: Boolean get() = isShell && !isBoundaryImage
    }

    private sealed interface RenderPrimitive {
        val depth: Double
    }

    private data class AtomPrimitive(val point: Point) : RenderPrimitive {
        override val depth = point.z
    }

    private data class BondPrimitive(val a: Point, val b: Point, val width: Float) : RenderPrimitive {
        override val depth = (a.z + b.z) / 2.0
    }

    private data class PolyhedronFacePrimitive(
        val center: Point,
        val faceVerts: List<Point>,
        val normal: Vec3,
        val alphaScale: Float,
        // Per v0.3.44: outline-only back faces — draw just the edges at low alpha, no fill.
        val outlineOnly: Boolean = false,
        val outlineAlpha: Float = 0.35f,
        override val depth: Double,
    ) : RenderPrimitive

    private fun drawPolyhedronFacePrimitives(
        center: Point,
        vertices: List<Point>,
        appearance: ViewerAppearance,
        elementArgbOverrides: Map<String, Long>,
        siteArgbOverrides: Map<String, Long>,
        rotation: Mat3,
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
            val camZ = (rotation * normal).z
            if (camZ > 0.0) {
                result += PolyhedronFacePrimitive(center, ordered, normal, 1.0f, outlineOnly = false, outlineAlpha = 0.35f, depth = nearestDepth)
                if (allCoplanar) result += PolyhedronFacePrimitive(center, ordered.reversed(), normal * -1.0, 0.4f, outlineOnly = false, outlineAlpha = 0.35f, depth = nearestDepth)
            } else {
                // Per v0.3.44: back face — outline only, faint.
                result += PolyhedronFacePrimitive(center, ordered, normal, 1.0f, outlineOnly = true, outlineAlpha = 0.18f, depth = nearestDepth)
            }
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
        bondValenceBySite: Map<String, Double> = emptyMap(),
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
        val allRotated = snapshot.atoms.map { controller.rotation * (it.cartesian - center) }
        val extentX = allRotated.maxOf { it.x } - allRotated.minOf { it.x }
        val extentY = allRotated.maxOf { it.y } - allRotated.minOf { it.y }
        val scale = min(width / max(1.0, extentX), height / max(1.0, extentY)).toFloat() * 0.72f * controller.zoom
        fun screen(v: Vec3) = Pair(width / 2f + controller.panX + v.x.toFloat() * scale, height / 2f + controller.panY - v.y.toFloat() * scale)

        drawFrames(canvas, snapshot, appearance, center, controller, scale, width, height)
        if (appearance.showAxes) drawAxes(canvas, snapshot, appearance, controller, width, height)
        // Per v0.3.0: project ALL atoms (so bonds/polyhedra survive hiding an atom); render only
        // visible atoms as AtomPrimitive.
        val points = snapshot.atoms.map { atom ->
            val rotated = controller.rotation * (atom.cartesian - center)
            val (x, y) = screen(rotated)
            Point(atom.id, atom.element, atom.siteId, atom.siteLabel, x, y, rotated.z, (PeriodicTable.defaultRadius(atom.element).toFloat() * scale).coerceIn(4.5f, 42f), atom.occupancy, atom.fractional, atom.cartesian, atom.isShell, atom.isBoundaryImage)
        }
        val visiblePointIds = visible.map { it.id }.toSet()
        val visiblePoints = points.filter { it.atomId in visiblePointIds }
        val byId = points.associateBy { it.atomId }
        val neighbors = mutableMapOf<Long, MutableList<Point>>()
        val renderables = buildList<RenderPrimitive> {
            visiblePoints.forEach { add(AtomPrimitive(it)) }
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
                    // Bond-line rendering: an external-shell bond draws only when its rule opts in via
                    // extendAcrossCell. Boundary-image bonds are drawn by default.
                    if (externalBond && !bond.rule.extendAcrossCell) return@forEach
                    val width = (appearance.bondRadius * scale * 0.65f).coerceIn(1.5f, 16f)
                    add(BondPrimitive(a, b, width))
                    if (externalBond && b.atomId in visibleExternalShellAtomIds) {
                        add(AtomPrimitive(b))
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
                        // vertex depth, instead of one block at the centre depth.
                        drawPolyhedronFacePrimitives(center, vertices, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.rotation).forEach { add(it) }
                    }
                }
            }
        }.sortedBy { it.depth }

        // Per v0.5.2: depth-of-field. Mirror CrystalViewport — compute the visible depth span once,
        // map each primitive's camera-space z to a blur/fog factor. Atoms get a real BlurMaskFilter;
        // all objects fog toward the background colour.
        val depthRange = run {
            val ds = points.map { it.z }
            if (ds.isEmpty()) null else (ds.min() to ds.max())
        }
        val backgroundArgb = appearance.backgroundArgb.toInt()
        fun dofFactor(depth: Double): DofFactor {
            if (!appearance.depthOfFieldEnabled || depthRange == null) return DofFactor.NONE
            val (dMin, dMax) = depthRange
            val dSpan = (dMax - dMin).coerceAtLeast(1e-6)
            val focal = dMin + appearance.dofFocal.toDouble() * dSpan
            val dist = abs(depth - focal) / dSpan
            val halfRange = (appearance.dofRange * 0.5f).toDouble().coerceIn(0.0, 0.999)
            val over = ((dist - halfRange) / (1.0 - halfRange)).coerceIn(0.0, 1.0)
            return DofFactor((appearance.dofBlur * over).toFloat(), (appearance.dofFog * over).toFloat())
        }

        renderables.forEach { primitive ->
            val dof = dofFactor(primitive.depth)
            when (primitive) {
                is AtomPrimitive -> drawAtom(canvas, primitive.point, appearance, selectedAtomIds, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, dof)
                is BondPrimitive -> drawBond(canvas, primitive.a, primitive.b, primitive.width, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, visibility.hiddenSites, dof, backgroundArgb)
                is PolyhedronFacePrimitive -> drawPolyhedronFacePrimitive(canvas, primitive, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.rotation, dof, backgroundArgb)
            }
        }
        // Per v0.3.0: draw locked (persistent) + active measurement/info windows.
        lockedMeasurements.forEach { m -> drawMeasurement(canvas, snapshot, points, m.atomIds, m.mode, true) }
        drawMeasurement(canvas, snapshot, points, selectedAtomIds, measurementMode, false)
        lockedInspectedAtomIds.forEach { id -> drawAtomInfo(canvas, points, id, true, bondValenceBySite) }
        if (inspectedAtomId != null && inspectedAtomId !in lockedInspectedAtomIds) drawAtomInfo(canvas, points, inspectedAtomId, false, bondValenceBySite)
        return bitmap
    }

    private fun drawAtom(canvas: Canvas, point: Point, appearance: ViewerAppearance, selectedAtomIds: List<Long>, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap(), dof: DofFactor = DofFactor.NONE) {
        // Per v0.5.2: fog the base colour toward the background before shading.
        val baseArgb = PeriodicTable.resolveSiteArgb(point.siteId, point.element, siteArgbOverrides, elementArgbOverrides).toInt()
        val base = if (dof.fog > 0f) blend(baseArgb, appearance.backgroundArgb.toInt(), dof.fog) else baseArgb
        val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (opacity * 255).toInt()
            // Per v0.5.2: real Gaussian blur for out-of-focus atoms. Android Paint already exposes
            // maskFilter, so this is a one-liner (no drawIntoCanvas needed, unlike the viewport).
            maskFilter = if (dof.blur > 0f) BlurMaskFilter((dof.blur * point.radius * 0.5f).coerceAtMost(point.radius * 0.5f), BlurMaskFilter.Blur.NORMAL) else null
            shader = RadialGradient(
                point.x, point.y, point.radius,
                intArrayOf(base, darken(base, 0.65f)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(point.x, point.y, point.radius, paint)
        if (appearance.reflectionEnabled && opacity > 0.01f) {
            val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
            val offset = point.radius * .38f * light.z.toFloat()
            val highlightAlpha = (appearance.lightIntensity.coerceIn(.05f, 1f) * opacity * 255).toInt()
            val highlight = Color.argb(highlightAlpha, 255, 255, 255)
            paint.maskFilter = null
            paint.shader = RadialGradient(
                point.x - light.x.toFloat() * offset,
                point.y - light.y.toFloat() * offset,
                point.radius * (0.35f + 0.75f * appearance.diffusion),
                intArrayOf(highlight, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(point.x, point.y, point.radius, paint)
        }
        paint.shader = null; paint.maskFilter = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = if (point.atomId in selectedAtomIds) 4f else 1f
        paint.color = if (point.atomId in selectedAtomIds) 0xFF9966CC.toInt() else 0x55000000
        canvas.drawCircle(point.x, point.y, point.radius + if (point.atomId in selectedAtomIds) 3f else 0f, paint)
    }

    private fun drawBond(canvas: Canvas, a: Point, b: Point, width: Float, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap(), hiddenSites: Set<String> = emptySet(), dof: DofFactor = DofFactor.NONE, backgroundArgb: Int = 0xFF101014.toInt()) {
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

        val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
        val lightOnPerp = (light.x.toFloat() * perpX + light.y.toFloat() * perpY).toDouble()

        val start = Point(0L, a.element, a.siteId, a.siteLabel, startX, startY, a.z, a.radius, a.occupancy, a.fractional, a.cartesian, a.isShell, a.isBoundaryImage)
        val end = Point(0L, b.element, b.siteId, b.siteLabel, endX, endY, b.z, b.radius, b.occupancy, b.fractional, b.cartesian, b.isShell, b.isBoundaryImage)
        if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
            val base = if (dof.fog > 0f) blend(appearance.uniformBondArgb.toInt(), backgroundArgb, dof.fog) else appearance.uniformBondArgb.toInt()
            drawBondCylinder(canvas, start, end, width, perpX, perpY, lightOnPerp, base, opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
        } else {
            val mx = (startX + endX) / 2f
            val my = (startY + endY) / 2f
            val midA = Point(0L, a.element, a.siteId, a.siteLabel, mx, my, (a.z + b.z) / 2.0, 0f, a.occupancy, a.fractional, a.cartesian, a.isShell, a.isBoundaryImage)
            val midB = Point(0L, b.element, b.siteId, b.siteLabel, mx, my, (a.z + b.z) / 2.0, 0f, b.occupancy, b.fractional, b.cartesian, b.isShell, b.isBoundaryImage)
            val baseA = PeriodicTable.resolveSiteArgb(a.siteId, a.element, siteArgbOverrides, elementArgbOverrides).toInt()
            val baseB = PeriodicTable.resolveSiteArgb(b.siteId, b.element, siteArgbOverrides, elementArgbOverrides).toInt()
            val fa = if (dof.fog > 0f) blend(baseA, backgroundArgb, dof.fog) else baseA
            val fb = if (dof.fog > 0f) blend(baseB, backgroundArgb, dof.fog) else baseB
            drawBondCylinder(canvas, start, midA, width, perpX, perpY, lightOnPerp, fa, opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
            drawBondCylinder(canvas, midB, end, width, perpX, perpY, lightOnPerp, fb, opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
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
        // Per v0.5.2: world-light intensity/diffusion drive the bond's highlight brightness, shadow
        // contrast and highlight band width (previously fixed 0.55/0.55/0.45 and ±0.18/0.08).
        lightIntensity: Float,
        diffusion: Float,
    ) {
        val halfWidth = width / 2f
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
        val base = Color.argb((opacity * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val shadowA = darken(baseArgb, 1f - 0.45f * lightIntensity)
        val shadowB = darken(baseArgb, 1f - 0.35f * lightIntensity)
        val highlight = if (reflectionEnabled) lighten(baseArgb, 0.55f * lightIntensity) else base
        val band = 0.08f + 0.18f * diffusion
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = width
            strokeCap = Paint.Cap.BUTT
            shader = LinearGradient(
                mx + perpX * halfWidth,
                my + perpY * halfWidth,
                mx - perpX * halfWidth,
                my - perpY * halfWidth,
                intArrayOf(shadowA, blend(baseArgb, shadowA, 0.5f), base, highlight, base, blend(baseArgb, shadowB, 0.5f), shadowB),
                floatArrayOf(0f, (highlightPos - band).coerceIn(0.02f, 0.98f), (highlightPos - band * 0.45f).coerceIn(0.03f, 0.97f), highlightPos, (highlightPos + band * 0.45f).coerceIn(0.03f, 0.97f), (highlightPos + band).coerceIn(0.02f, 0.98f), 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }

    private fun drawPolyhedronFacePrimitive(
        canvas: Canvas,
        face: PolyhedronFacePrimitive,
        appearance: ViewerAppearance,
        elementArgbOverrides: Map<String, Long>,
        siteArgbOverrides: Map<String, Long>,
        rotation: Mat3,
        // Per v0.5.2: depth-of-field fog (faces are not blurred, only faded toward the background).
        dof: DofFactor = DofFactor.NONE,
        backgroundArgb: Int = 0xFF101014.toInt(),
    ) {
        val verts = face.faceVerts
        if (verts.size < 3) return
        val rawArgb = PeriodicTable.resolveSiteArgb(face.center.siteId, face.center.element, siteArgbOverrides, elementArgbOverrides).toInt()
        // Per v0.5.2: fog the base colour toward the background before lighting.
        val baseArgb = if (dof.fog > 0f) blend(rawArgb, backgroundArgb, dof.fog) else rawArgb
        val baseColor = Color.argb((appearance.polyhedronOpacity.coerceIn(0f, 1f) * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val cam = rotation * face.normal
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Per v0.3.44: outline-only back faces skip the fill and draw edges at low alpha.
        if (!face.outlineOnly) {
            if (cam.z <= 0.0) return // back face culled (filled)
            val factor = if (appearance.polyhedronReflectionEnabled) {
                val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
                val dot = cam.dot(light).coerceIn(0.0, 1.0)
                // Per v0.5.2: ambient floor scales with light intensity — intensity 0 → flat lit,
                // intensity 1 → ambient 0.4 with full Lambert range.
                val ambient = (1f - 0.6f * appearance.lightIntensity).coerceIn(0.4f, 1f)
                (ambient + (1f - ambient) * dot.toFloat()).toDouble()
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
            val path = android.graphics.Path().apply {
                moveTo(verts[0].x, verts[0].y)
                for (i in 1 until verts.size) lineTo(verts[i].x, verts[i].y)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawPath(path, paint)
        }
        paint.color = Color.argb((face.outlineAlpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
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

    /**
     * Per v0.5.2: azimuth/elevation (degrees) → unit light direction in screen space. X right, Y down,
     * +Z toward the viewer. elevation is clamped to 0..90 (light stays at/above the horizon). Mirrors
     * CrystalViewport.lightDirection so both renderers shade consistently.
     */
    private fun lightDirection(azimuthDeg: Float, elevationDeg: Float): Vec3 {
        val a = azimuthDeg / 180.0 * PI
        val e = elevationDeg.coerceIn(0f, 90f) / 180.0 * PI
        return Vec3(cos(a) * cos(e), sin(a) * cos(e), sin(e))
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
        val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
        val lightX = light.x.toFloat()
        val lightY = light.y.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; textSize = 30f; setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        directions.forEachIndexed { index, dir ->
            val rotated = controller.rotation * dir
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
            val shaftA = Point(0L, "", "", "", origin.x, origin.y, 0.0, 0f, 0.0, Vec3.ZERO, Vec3.ZERO, false, false)
            val shaftB = Point(0L, "", "", "", shaftEndX, shaftEndY, 0.0, 0f, 0.0, Vec3.ZERO, Vec3.ZERO, false, false)
            drawBondCylinder(canvas, shaftA, shaftB, halfWidth * 2f, perpX, perpY, lightOnPerp, color, 1f, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
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
            val shadowA = darken(color, 1f - 0.45f * appearance.lightIntensity)
            val shadowB = darken(color, 1f - 0.35f * appearance.lightIntensity)
            val highlight = if (appearance.bondReflectionEnabled) lighten(color, 0.55f * appearance.lightIntensity) else color
            val band = 0.08f + 0.18f * appearance.diffusion
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                baseX + perpX * headHalf, baseY + perpY * headHalf,
                baseX - perpX * headHalf, baseY - perpY * headHalf,
                intArrayOf(shadowA, blend(color, shadowA, 0.5f), color, highlight, color, blend(color, shadowB, 0.5f), shadowB),
                floatArrayOf(0f, (highlightPos - band).coerceIn(0.02f, 0.98f), (highlightPos - band * 0.45f).coerceIn(0.03f, 0.97f), highlightPos, (highlightPos + band * 0.45f).coerceIn(0.03f, 0.97f), (highlightPos + band).coerceIn(0.02f, 0.98f), 1f),
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
            ).map { controller.rotation * (snapshot.structure.cell.toCartesian(it) - center) }
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

    private fun drawAtomInfo(canvas: Canvas, points: List<Point>, inspectedAtomId: Long?, locked: Boolean, bondValenceBySite: Map<String, Double> = emptyMap()) {
        val atom = inspectedAtomId?.let { id -> points.firstOrNull { it.atomId == id } } ?: return
        val bvs = bondValenceBySite[atom.siteId]
        val bvsText = bvs?.let { "  s = %.2f".format(it) } ?: ""
        val label = "${atom.element}  ${atom.siteLabel}  occ ${atom.occupancy}$bvsText\n(${atom.fractional.x.formatFract()}, ${atom.fractional.y.formatFract()}, ${atom.fractional.z.formatFract()})"
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
}
