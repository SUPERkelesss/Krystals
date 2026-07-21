package com.krystals.crystal.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.coordination.CoordinationAnalyzer
import com.krystals.crystal.analysis.model.AxisMode
import com.krystals.crystal.analysis.model.BondColorMode
import com.krystals.crystal.analysis.model.FrameMode
import com.krystals.crystal.analysis.model.LineStyle
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.analysis.model.ViewerAppearance
import com.krystals.crystal.analysis.polyhedron.PolyhedronHull
import com.krystals.crystal.core.Mat3
import com.krystals.crystal.core.Vec3
import com.krystals.crystal.core.angleDegrees
import com.krystals.crystal.core.dihedralDegrees
import com.krystals.crystal.core.distance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object CrystalImageExporter {

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
        // Per v0.5.3a: sort by the bond's GEOMETRIC CENTRE depth (average endpoint z). +Z toward
        // viewer (larger z = closer). v0.5.2a's nearest-endpoint (maxOf) sorted mostly-far bonds
        // as fully near and let them occlude closer atoms.
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
        val hullFaces = PolyhedronHull.faces(center.cartesian, vertices.map { it.cartesian })
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
            // Per v0.5.3a: sort by the face's GEOMETRIC CENTRE depth (average vertex z). +Z toward
            // viewer (larger z = closer). v0.5.2a's nearest-vertex (maxOf) sorted large faces as if
            // at their near edge, occluding atoms in front.
            val faceDepth = ordered.map { it.z }.average()
            val camZ = (rotation * normal).z
            if (camZ > 0.0) {
                result += PolyhedronFacePrimitive(center, ordered, normal, 1.0f, outlineOnly = false, outlineAlpha = 0.35f, depth = faceDepth)
            } else {
                // Per v0.3.44: back face — outline only, faint.
                result += PolyhedronFacePrimitive(center, ordered, normal, 1.0f, outlineOnly = true, outlineAlpha = 0.18f, depth = faceDepth)
            }
            // Per v0.3.41: for flat coordinations, also emit the reversed face so the polygon is
            // visible from the centre-atom side. Only emit when the REVERSED normal faces the camera
            // (mirrors CrystalViewport: original back-facing → reversed front-facing). The previous
            // code emitted it when the original was front-facing, which made the reversed face's
            // camera-z negative → always culled in drawPolyhedronFacePrimitive (dead code).
            if (allCoplanar && (rotation * (normal * -1.0)).z > 0.0) {
                result += PolyhedronFacePrimitive(center, ordered.reversed(), normal * -1.0, 0.4f, outlineOnly = false, outlineAlpha = 0.35f, depth = faceDepth)
            }
        }
        return result
    }

    fun render(
        snapshot: BondNetwork,
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
        val coordination = CoordinationAnalyzer.neighbors(snapshot, visibility.showBonds, visibility.hiddenBondPairs)
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
                    val vertices = coordination[center.atomId].orEmpty().mapNotNull { byId[it.id] }
                    if (vertices.size >= 3) {
                        // Per v0.3.43: draw each face as its own primitive sorted by the face's nearest
                        // vertex depth, instead of one block at the centre depth.
                        drawPolyhedronFacePrimitives(center, vertices, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.rotation).forEach { add(it) }
                    }
                }
            }
        }.sortedBy { it.depth }

        // Per v0.5.4: depth cueing fades COLOUR toward the background (not alpha) — mirror
        // CrystalViewport. Convention 近=正/远=负: nearest atom ≈ +3, farthest ≈ -3; near>=far.
        val depthRange = run {
            val ds = visiblePoints.map { it.z }   // 与 viewport 一致: 仅可见原子
            if (ds.isEmpty()) null else (ds.min() to ds.max())
        }
        val bgArgb = appearance.backgroundArgb.toInt()
        fun dofFog(depth: Double): Float {
            if (!appearance.depthOfFieldEnabled || depthRange == null) return 0f
            val (dMin, dMax) = depthRange
            val dSpan = (dMax - dMin).coerceAtLeast(1e-6)
            val centre = (dMin + dMax) / 2.0
            // d: 近(大z)=正、远(小z)=负;最近≈+3,最远≈-3。
            val d = ((depth - centre) / dSpan * 6.0).toFloat()
            val near = appearance.dofNear   // 正,近端不淡化阈值
            val far = appearance.dofFar     // 负,远端全淡化阈值
            if (near <= far) return if (d >= near) 0f else 1f
            if (d >= near) return 0f        // 近端不淡化
            if (d <= far) return 1f         // 远端全淡化
            return ((near - d) / (near - far)).coerceIn(0f, 1f)
        }

        // Per v0.5.4: contact shadows removed (user request).

        renderables.forEach { primitive ->
            when (primitive) {
                // Per v0.5.3a: each object's colour blends toward the background by its fog amount;
                // opacity is unchanged. Bonds split at the midpoint (each half by its endpoint's fog).
                is AtomPrimitive -> drawAtom(canvas, primitive.point, appearance, selectedAtomIds, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, dofFog(primitive.depth), bgArgb)
                is BondPrimitive -> drawBond(canvas, primitive.a, primitive.b, primitive.width, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, visibility.hiddenSites, ::dofFog, bgArgb)
                is PolyhedronFacePrimitive -> drawPolyhedronFacePrimitive(canvas, primitive, appearance, snapshot.elementArgbOverrides, snapshot.structure.siteArgbOverrides, controller.rotation, dofFog(primitive.depth), bgArgb)
            }
        }
        // Per v0.3.0: draw locked (persistent) + active measurement/info windows.
        lockedMeasurements.forEach { m -> drawMeasurement(canvas, snapshot, points, m.atomIds, m.mode, true) }
        drawMeasurement(canvas, snapshot, points, selectedAtomIds, measurementMode, false)
        lockedInspectedAtomIds.forEach { id -> drawAtomInfo(canvas, points, id, true, bondValenceBySite) }
        if (inspectedAtomId != null && inspectedAtomId !in lockedInspectedAtomIds) drawAtomInfo(canvas, points, inspectedAtomId, false, bondValenceBySite)
        return bitmap
    }

    private fun drawAtom(canvas: Canvas, point: Point, appearance: ViewerAppearance, selectedAtomIds: List<Long>, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap(), fog: Float = 0f, bgArgb: Int = 0xFF101014.toInt()) {
        // Per v0.5.3a: depth cueing fades COLOUR toward the background by [fog]; opacity is unchanged.
        val rawArgb = PeriodicTable.resolveSiteArgb(point.siteId, point.element, siteArgbOverrides, elementArgbOverrides).toInt()
        val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
        if (opacity < 0.01f) {
            if (point.atomId in selectedAtomIds) {
                val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFF9966CC.toInt() }
                canvas.drawCircle(point.x, point.y, point.radius + 3f, sp)
            }
            return
        }
        val baseArgb = blend(rawArgb, bgArgb, fog)
        val darkArgb = blend(darken(rawArgb, 0.65f), bgArgb, fog)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (opacity * 255).toInt()
            shader = RadialGradient(
                point.x, point.y, point.radius,
                intArrayOf(baseArgb, darkArgb),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        // Per v0.5.2b: partial-occupancy atoms render as a pie chart — occ fraction solid, the rest
        // at low alpha, as screen-space wedges from the top (12 o'clock) clockwise. The RadialGradient
        // centre is the atom's absolute point, so it spans both wedges seamlessly.
        val occ = point.occupancy.coerceIn(0.0, 1.0)
        if (occ >= 0.999) {
            canvas.drawCircle(point.x, point.y, point.radius, paint)
        } else {
            val r = point.radius
            val rect = RectF(point.x - r, point.y - r, point.x + r, point.y + r)
            val occSweep = (occ * 360.0).toFloat()
            fun wedge(start: Float, sweep: Float) = Path().apply {
                moveTo(point.x, point.y)
                arcTo(rect, start, sweep, false)
                lineTo(point.x, point.y)
                close()
            }
            canvas.drawPath(wedge(-90f, occSweep), paint)
            paint.alpha = ((opacity * 0.15f) * 255).toInt().coerceIn(0, 255)
            canvas.drawPath(wedge(-90f + occSweep, 360f - occSweep), paint)
            paint.alpha = (opacity * 255).toInt()
        }
        if (appearance.reflectionEnabled) {
            val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
            // Per v0.5.4: highlight沿光在屏幕平面的方向偏移(light.xy 已含 cos(elevation)),量=r*0.38。
            // 掠射→偏移最大;正射→居中。与 viewport 一致。
            val offsetX = light.x.toFloat() * point.radius * .38f
            val offsetY = light.y.toFloat() * point.radius * .38f
            // Per v0.5.4: highlight alpha 含 opacity + lightIntensity + (1-fog);paint.alpha 置 255 让
            // shader 自带 alpha 唯一生效,避免与 sphere 的 paint.alpha 叠乘成 opacity²(viewport 不叠乘)。
            val highlightAlpha = (appearance.lightIntensity.coerceIn(.05f, 1f) * opacity * (1f - fog) * 255).toInt().coerceIn(0, 255)
            val highlight = Color.argb(highlightAlpha, 255, 255, 255)
            paint.alpha = 255
            paint.shader = RadialGradient(
                point.x - offsetX,
                point.y - offsetY,
                point.radius * (0.35f + 0.75f * appearance.diffusion),
                intArrayOf(highlight, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            // Highlight only on the solid wedge for partial-occ atoms; full circle otherwise.
            if (occ >= 0.999) {
                canvas.drawCircle(point.x, point.y, point.radius, paint)
            } else {
                val r = point.radius
                val rect = RectF(point.x - r, point.y - r, point.x + r, point.y + r)
                val occSweep = (occ * 360.0).toFloat()
                val solidWedge = Path().apply {
                    moveTo(point.x, point.y)
                    arcTo(rect, -90f, occSweep, false)
                    lineTo(point.x, point.y)
                    close()
                }
                canvas.drawPath(solidWedge, paint)
            }
        }
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = if (point.atomId in selectedAtomIds) 4f else 1f
        paint.color = if (point.atomId in selectedAtomIds) 0xFF9966CC.toInt() else 0x55000000
        canvas.drawCircle(point.x, point.y, point.radius + if (point.atomId in selectedAtomIds) 3f else 0f, paint)
    }

    private fun drawBond(canvas: Canvas, a: Point, b: Point, width: Float, appearance: ViewerAppearance, elementArgbOverrides: Map<String, Long>, siteArgbOverrides: Map<String, Long> = emptyMap(), hiddenSites: Set<String> = emptySet(), dofFog: (Double) -> Float, bgArgb: Int = 0xFF101014.toInt()) {
        // Per v0.5.3a: depth cueing fades each half's COLOUR toward the background by its endpoint's
        // fog; opacity is unchanged (bondOpacity only). Split at midpoint → continuous fade.
        val opacity = appearance.bondOpacity.coerceIn(0f, 1f)
        if (opacity < 0.01f) return
        val fogA = dofFog(a.z).coerceIn(0f, 1f)
        val fogB = dofFog(b.z).coerceIn(0f, 1f)
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
        val mx = (startX + endX) / 2f
        val my = (startY + endY) / 2f
        val midA = Point(0L, a.element, a.siteId, a.siteLabel, mx, my, (a.z + b.z) / 2.0, 0f, a.occupancy, a.fractional, a.cartesian, a.isShell, a.isBoundaryImage)
        val midB = Point(0L, b.element, b.siteId, b.siteLabel, mx, my, (a.z + b.z) / 2.0, 0f, b.occupancy, a.fractional, b.cartesian, b.isShell, a.isBoundaryImage)
        // Per v0.5.3a: pack opacity into baseArgb; fog is applied to the RGB via blend beforehand.
        fun pack(argb: Int, op: Float) = (argb and 0x00FFFFFF) or ((op * 255).toInt().coerceIn(0, 255) shl 24)
        if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
            val u = appearance.uniformBondArgb.toInt()
            drawBondCylinder(canvas, start, midA, width, perpX, perpY, lightOnPerp, pack(blend(u, bgArgb, fogA), opacity), opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
            drawBondCylinder(canvas, midB, end, width, perpX, perpY, lightOnPerp, pack(blend(u, bgArgb, fogB), opacity), opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
        } else {
            val baseA = PeriodicTable.resolveSiteArgb(a.siteId, a.element, siteArgbOverrides, elementArgbOverrides).toInt()
            val baseB = PeriodicTable.resolveSiteArgb(b.siteId, b.element, siteArgbOverrides, elementArgbOverrides).toInt()
            drawBondCylinder(canvas, start, midA, width, perpX, perpY, lightOnPerp, pack(blend(baseA, bgArgb, fogA), opacity), opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
            drawBondCylinder(canvas, midB, end, width, perpX, perpY, lightOnPerp, pack(blend(baseB, bgArgb, fogB), opacity), opacity, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
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
        @Suppress("UNUSED_PARAMETER") val op = opacity // alpha is already packed into baseArgb by the caller
        val halfWidth = width / 2f
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
        // baseArgb already carries the bond opacity (packed by the caller); reuse its alpha directly.
        val base = baseArgb
        val shadowA = darken(baseArgb, 1f - 0.45f * lightIntensity)
        val shadowB = darken(baseArgb, 1f - 0.35f * lightIntensity)
        val highlight = if (reflectionEnabled) lighten(baseArgb, 0.55f * lightIntensity) else base
        val band = 0.06f + 0.20f * diffusion  // Per v0.5.4: 对齐 viewport(原 0.08+0.18d)
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
        // Per v0.5.3a: depth cueing fades COLOUR toward the background by [fog]; alpha unchanged.
        fog: Float = 0f,
        bgArgb: Int = 0xFF101014.toInt(),
    ) {
        val verts = face.faceVerts
        if (verts.size < 3) return
        val rawArgb = PeriodicTable.resolveSiteArgb(face.center.siteId, face.center.element, siteArgbOverrides, elementArgbOverrides).toInt()
        // Per v0.5.3a: fog the RGB toward the background; alpha = polyhedron opacity only.
        val baseArgb = blend(rawArgb, bgArgb, fog)
        val alpha = appearance.polyhedronOpacity.coerceIn(0f, 1f)
        val baseColor = Color.argb((alpha * 255).toInt(), Color.red(baseArgb), Color.green(baseArgb), Color.blue(baseArgb))
        val cam = rotation * face.normal
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Per v0.3.44: outline-only back faces skip the fill and draw edges at low alpha.
        if (!face.outlineOnly) {
            if (cam.z <= 0.0) return // back face culled (filled)
            // Per v0.5.4: Blinn-Phong plastic shading — Lambert diffuse + white specular via the half
            // vector (light+view, view=(0,0,1) since +Z is toward the viewer). The whole face shares
            // one normal so the specular is uniform (flat reflective film / plastic). Shininess≈48.
            var fill = if (appearance.polyhedronReflectionEnabled) {
                val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
                val view = Vec3(0.0, 0.0, 1.0)
                val half = (light + view).normalized()
                val diff = cam.dot(light).coerceIn(0.0, 1.0)
                val spec = Math.pow(cam.dot(half).coerceIn(0.0, 1.0), 48.0)
                val ambient = (1f - 0.6f * appearance.lightIntensity).coerceIn(0.4f, 1f)
                val diffFactor = (ambient + (1f - ambient) * diff.toFloat()).coerceIn(0f, 1f)
                val specAmount = (spec.toFloat() * appearance.lightIntensity * 0.6f).coerceIn(0f, 1f)
                Color.argb(
                    Color.alpha(baseColor),
                    (Color.red(baseColor) * diffFactor + specAmount * 255f).toInt().coerceIn(0, 255),
                    (Color.green(baseColor) * diffFactor + specAmount * 255f).toInt().coerceIn(0, 255),
                    (Color.blue(baseColor) * diffFactor + specAmount * 255f).toInt().coerceIn(0, 255),
                )
            } else baseColor
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

    // Per v0.5.4: contact shadows removed (drawContactShadows deleted — user requested removal of the
    // simulated ground shadow; only atom/bond/polyhedron shading remains).

    private fun drawAxes(canvas: Canvas, snapshot: BondNetwork, appearance: ViewerAppearance, controller: ViewerController, width: Int, height: Int) {
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
            strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; textSize = 30f; setShadowLayer(5f, 1f, 1f, Color.BLACK)
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
            val band = 0.06f + 0.20f * appearance.diffusion  // Per v0.5.4: 对齐 viewport
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

    private fun drawFrames(canvas: Canvas, snapshot: BondNetwork, appearance: ViewerAppearance, center: Vec3, controller: ViewerController, scale: Float, width: Int, height: Int) {
        if (appearance.frameMode == FrameMode.NONE) return
        val ex = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.x else 1
        val ey = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.y else 1
        val ez = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.z else 1
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((0.72f * 255).toInt(), 128, 128, 128); strokeWidth = 1.4f; if (appearance.lineStyle == LineStyle.DASHED) pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
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

    private fun drawMeasurement(canvas: Canvas, snapshot: BondNetwork, points: List<Point>, ids: List<Long>, mode: MeasurementMode, locked: Boolean) {
        val count = when (mode) { MeasurementMode.LENGTH -> 2; MeasurementMode.ANGLE -> 3; MeasurementMode.DIHEDRAL -> 4; else -> 0 }
        if (count == 0 || ids.size < count) return
        val selected = ids.takeLast(count).mapNotNull { id -> points.firstOrNull { it.atomId == id } }
        if (selected.size != count) return
        // Per v0.5.4: draw the measurement polyline connecting the atoms (mirrors CrystalViewport —
        // previously the exporter drew only the label box, no connecting line).
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE1C6FF.toInt(); strokeWidth = 2.5f }
        for (index in 0 until selected.lastIndex) {
            canvas.drawLine(selected[index].x, selected[index].y, selected[index + 1].x, selected[index + 1].y, linePaint)
        }
        val atoms = snapshot.atoms.associateBy { it.id }
        val positions = selected.mapNotNull { atoms[it.atomId]?.cartesian }
        val label = when (mode) {
            MeasurementMode.LENGTH -> "%.4f Å".format(distance(positions[0], positions[1]))
            MeasurementMode.ANGLE -> "%.3f°".format(angleDegrees(positions[0], positions[1], positions[2]))
            MeasurementMode.DIHEDRAL -> "%.3f°".format(dihedralDegrees(positions[0], positions[1], positions[2], positions[3]))
            else -> return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; setShadowLayer(5f, 1f, 1f, Color.BLACK) }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(label, 0, label.length, bounds)
        val anchorX = selected.map { it.x }.average().toFloat()
        val anchorY = selected.map { it.y }.average().toFloat()
        val pad = 16f
        val boxLeft = anchorX + 12f - pad
        val boxTop = anchorY - 12f - bounds.height() - pad
        val boxRight = anchorX + 12f + bounds.width() + pad
        val boxBottom = anchorY - 12f + pad
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (locked) Color.argb((0.82f * 255).toInt(), 153, 102, 204) else Color.argb((0.65f * 255).toInt(), 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, 14f, 14f, boxPaint)
        canvas.drawText(label, anchorX + 12f, anchorY - 12f, paint)
    }

    private fun drawAtomInfo(canvas: Canvas, points: List<Point>, inspectedAtomId: Long?, locked: Boolean, bondValenceBySite: Map<String, Double> = emptyMap()) {
        val atom = inspectedAtomId?.let { id -> points.firstOrNull { it.atomId == id } } ?: return
        val bvs = bondValenceBySite[atom.siteId]
        val bvsText = bvs?.let { "  s = %.2f".format(it) } ?: ""
        val label = "${atom.element}  ${atom.siteLabel}  occ ${atom.occupancy}$bvsText\n(${atom.fractional.x.formatFract()}, ${atom.fractional.y.formatFract()}, ${atom.fractional.z.formatFract()})"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; setShadowLayer(5f, 1f, 1f, Color.BLACK) }
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
