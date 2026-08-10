package com.krystals.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withRotation
import com.krystals.app.ui.AppPalette
import com.krystals.crystal.core.math.Vec3
import com.krystals.interaction.measure.AngleTool
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.DistanceTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.measure.MeasurementSelection
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.scene.GatheredAtomGrouper
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.SceneProjection
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.SelectionColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per v0.7.0: shared native-Canvas drawing for the viewer-style overlay (axes + measurement
 * labels). Used by the on-screen overlay (ViewerBackendHost) and the export-image overlay
 * (ExportOverlay) so both stay pixel-identical. Pure android.graphics — no Compose dependency.
 *
 * Behaviour notes (unified from the two former copies): axis labels carry no text shadow, arrows
 * start at the centre-sphere surface (v0.7.0 on-screen behaviour), dihedral planes use the
 * native gradient stops, and the measurement panel colours come from [AppPalette].
 */
object OverlayDraw {

    /** One measured-selection hit region, in canvas coordinates. */
    data class MeasurementHit(val bounds: RectF, val selection: MeasurementSelection?, val locked: Boolean)

    // ── axes ──

    fun drawAxes(
        canvas: Canvas,
        w: Int,
        h: Int,
        scene: RenderScene,
        state: InteractionState,
        projection: SceneProjection,
    ) {
        val matrix = scene.structure.lattice.matrix
        val directions = when (scene.environment.axes.mode) {
            AxisMode.ABC -> listOf(matrix.a, matrix.b, matrix.c)
            AxisMode.XYZ -> listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
        }
        val labels = if (scene.environment.axes.mode == AxisMode.ABC) listOf("a", "b", "c") else listOf("X", "Y", "Z")
        val colors = listOf(AppPalette.AXIS_X.toInt(), AppPalette.AXIS_Y.toInt(), AppPalette.AXIS_Z.toInt())
        val originX = w * scene.environment.axes.offsetX + 28f
        val originY = h * scene.environment.axes.offsetY + 40f - 75f
        val arrowLength = 75f
        val headLengthBase = 14f
        val light = scene.environment.worldLight
        val theta = light.azimuthDegrees / 180f * PI.toFloat()
        val phi = light.elevationDegrees / 180f * PI.toFloat()
        val lightOffsetX = cos(theta) * cos(phi)
        val lightOffsetY = -sin(theta) * cos(phi)
        val camera = state.session.camera
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            // v0.7.0: no text shadow on axis labels (a/b/c, X/Y/Z).
            clearShadowLayer()
        }
        val rotatedDirs = directions.mapIndexed { index, axis ->
            Triple(index, axis, (camera.rotation * axis).normalized())
        }
        fun drawArrow(index: Int, direction: Vec3) {
            val dx = direction.x.toFloat()
            val dy = -direction.y.toFloat()
            val projectedLength = sqrt(dx * dx + dy * dy)
            val visibleLength = arrowLength * projectedLength
            val ux = if (projectedLength > 0.0001f) dx / projectedLength else 0f
            val uy = if (projectedLength > 0.0001f) dy / projectedLength else 0f
            // v0.7.0: arrow starts at the centre-sphere surface (hub radius 12f).
            val startX = originX + ux * 12f
            val startY = originY + uy * 12f
            val endX = startX + ux * visibleLength
            val endY = startY + uy * visibleLength
            val headLength = headLengthBase
            val color = colors[index]
            val perpX = -uy
            val perpY = ux
            val halfWidth = 4f
            val shaftAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val shaftLen = (visibleLength - headLength).coerceAtLeast(0f)
            val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    startX, startY - halfWidth, startX, startY + halfWidth,
                    intArrayOf(withAlpha(color, 0.4f), color, lighten(color, 0.4f), color, withAlpha(color, 0.4f)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.withRotation(shaftAngle, startX, startY) {
                canvas.drawRect(startX, startY - halfWidth, startX + shaftLen, startY + halfWidth, shaftPaint)
            }
            // Cone arrowhead.
            val halfHead = headLength * 0.6f
            val baseX = endX - ux * headLength
            val baseY = endY - uy * headLength
            val headPath = Path().apply {
                moveTo(endX, endY)
                lineTo(baseX + perpX * halfHead, baseY + perpY * halfHead)
                lineTo(baseX - perpX * halfHead, baseY - perpY * halfHead)
                close()
            }
            val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    baseX + perpX * halfHead, baseY + perpY * halfHead,
                    baseX - perpX * halfHead, baseY - perpY * halfHead,
                    intArrayOf(withAlpha(color, 0.4f), color, lighten(color, 0.4f), color, withAlpha(color, 0.4f)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(headPath, headPaint)
            axisPaint.color = color
            canvas.drawText(labels[index], endX + 4f, endY - 4f, axisPaint)
        }
        // Draw back arrows (pointing away) first, then the hub, then front arrows.
        rotatedDirs.filter { it.third.z <= 0.0 }.forEach { (index, _, direction) -> drawArrow(index, direction) }
        val hubShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(128, 0, 0, 0) }
        canvas.drawCircle(originX + 1f, originY + 1f, 13f, hubShadow)
        val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                originX + lightOffsetX * 4.5f, originY + lightOffsetY * 4.5f, 12f,
                intArrayOf(AppPalette.HUB_LIGHT.toInt(), AppPalette.HUB_DARK.toInt()), null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(originX, originY, 12f, hubPaint)
        rotatedDirs.filter { it.third.z > 0.0 }.forEach { (index, _, direction) -> drawArrow(index, direction) }
    }

    // ── measurements ──

    fun drawMeasurements(
        canvas: Canvas,
        scene: RenderScene,
        state: InteractionState,
        projection: SceneProjection,
    ): List<MeasurementHit> {
        val atomsById = scene.atoms.asSequence().filter { it.visible }.associateBy { it.atom.id }
        fun point(id: Long): Pair<Float, Float>? = atomsById[id]?.let { atom ->
            val (px, py) = projection.project(atom.atom.cartesianCoordinate.toVec3())
            px.toFloat() to py.toFloat()
        }
        val measurements = mutableListOf<Triple<MeasurementSelection, MeasurementSelection?, Boolean>>()
        measurements += state.document.lockedMeasurements.map { Triple(it, it, true) }
        if (state.document.measurementMode != MeasurementMode.NONE && state.document.selection.selectedAtomIds.isNotEmpty()) {
            measurements += Triple(MeasurementSelection(state.document.selection.selectedAtomIds, state.document.measurementMode), null, false)
        }
        val measurementPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            setShadowLayer(5f, 1f, 1f, Color.BLACK)
        }
        val hits = mutableListOf<MeasurementHit>()
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
            if (selection.mode == MeasurementMode.DIHEDRAL) {
                DihedralTool.planes(coordinates[0], coordinates[1], coordinates[2], coordinates[3]).forEach { plane ->
                    val sv = plane.vertices.map { v ->
                        val (px, py) = projection.project(v)
                        px.toFloat() to py.toFloat()
                    }
                    if (sv.size == 4) {
                        val path = Path().apply {
                            moveTo(sv[0].first, sv[0].second)
                            sv.drop(1).forEach { lineTo(it.first, it.second) }
                            close()
                        }
                        val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(
                                sv[0].first, sv[0].second, sv[3].first, sv[3].second,
                                intArrayOf(0x709F5FCD.toInt(), 0x3848B480.toInt(), 0x00000000),
                                null, Shader.TileMode.CLAMP,
                            )
                        }
                        canvas.drawPath(path, planePaint)
                    }
                }
            }
            val label = when (selection.mode) {
                MeasurementMode.LENGTH -> "%.4f \u00C5".format(DistanceTool.calculate(coordinates[0], coordinates[1]))
                MeasurementMode.ANGLE -> "%.3f\u00B0".format(AngleTool.calculate(coordinates[0], coordinates[1], coordinates[2]))
                MeasurementMode.DIHEDRAL -> "%.3f\u00B0".format(DihedralTool.calculate(coordinates[0], coordinates[1], coordinates[2], coordinates[3]))
                else -> return@forEach
            }
            val anchorX = points.map { it.first }.sum() / points.size
            val anchorY = points.map { it.second }.sum() / points.size
            val nativeBounds = android.graphics.Rect()
            measurementPaint.getTextBounds(label, 0, label.length, nativeBounds)
            val pad = 16f
            val left = anchorX + 12f - pad
            val top = anchorY - 12f - nativeBounds.height() - pad
            val right = anchorX + 12f + nativeBounds.width() + pad
            val bottom = anchorY - 12f + pad
            val panelColor = if (locked) withAlpha(AppPalette.BRAND_MID.toInt(), 0.82f) else 0xA6000000.toInt()
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelColor }
            canvas.drawRoundRect(left, top, right, bottom, 14f, 14f, panelPaint)
            canvas.drawText(label, anchorX + 12f, anchorY - 12f, measurementPaint)
            hits += MeasurementHit(RectF(left, top, right, bottom), lockValue, locked)
        }
        return hits
    }

    // ── inspection panels ──

    /**
     * Per v0.7.0: draws inspection info panels (element, site label, occupancy, bond-valence
     * sum, fractional coordinates) next to inspected atoms, expanding gathered groups like the
     * on-screen overlay (ViewerBackendHost). Canvas port of the former renderer-internal
     * composeOverlay inspection section so exports match the viewer.
     */
    fun drawInspections(
        canvas: Canvas,
        w: Int,
        h: Int,
        scene: RenderScene,
        state: InteractionState,
        projection: SceneProjection,
        bondValenceBySite: Map<String, Double> = emptyMap(),
    ) {
        val atoms = scene.atoms.associateBy { it.atom.id }
        if (atoms.isEmpty()) return
        val inspectionIds = state.document.inspection.lockedInspectedAtomIds +
            listOfNotNull(state.document.inspection.inspectedAtomId)
                .filterNot { it in state.document.inspection.lockedInspectedAtomIds }
        if (inspectionIds.isEmpty()) return
        val scale = (w / 1080f).coerceIn(0.5f, 1.0f)
        fun project(id: Long): Pair<Float, Float>? {
            val atom = atoms[id]?.atom ?: return null
            val (px, py) = projection.project(atom.cartesianCoordinate.toVec3())
            return px.toFloat() to py.toFloat()
        }
        // Gathered group membership (same data path as ViewerBackendHost).
        val atomImages = atoms.values.map { it.atom }
        val colorBySite = atoms.values.associate { it.atom.siteId to it.material.argb }
        val groupByMemberId = GatheredAtomGrouper.groupByAtomId(atomImages, colorBySite)
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f * scale
            setShadowLayer(5f * scale, scale, scale, Color.BLACK)
        }
        inspectionIds.forEach { id ->
            val atom = atoms[id]?.atom ?: return@forEach
            val anchor = project(id) ?: return@forEach
            val locked = id in state.document.inspection.lockedInspectedAtomIds
            val group = groupByMemberId[id]
            val displayIds = group?.memberAtomIds ?: listOf(id)
            val displayMembers = displayIds.take(3).mapNotNull { mid -> atoms[mid]?.atom }
            if (displayMembers.isEmpty()) return@forEach
            val allLines = displayMembers.flatMapIndexed { index, m ->
                val bvs = bondValenceBySite[m.siteId]?.let { "  s = %.2f".format(it) }.orEmpty()
                val f = m.fractionalCoordinate
                val memberLines = listOf(
                    "${m.species.symbol}  ${m.siteLabel}  occ ${m.occupancy}$bvs",
                    "(${f.x.formatFract()}, ${f.y.formatFract()}, ${f.z.formatFract()})",
                )
                if (index > 0) listOf("---") + memberLines else memberLines
            } + if (displayIds.size > 3) listOf("...") else emptyList()
            val lineHeight = infoPaint.fontMetrics.run { descent - ascent }
            val dividerHeight = lineHeight * 0.3f
            val maxWidth = allLines.maxOf(infoPaint::measureText)
            val pad = 16f * scale
            val atomRadius = projection.screenRadius(atoms.getValue(id).radius).toFloat()
            val totalHeight = allLines.size * lineHeight + (displayMembers.size - 1) * dividerHeight
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
    }

    // ── selection rings ──

    /**
     * Per v0.7.0: draws selection/lock/inspection rings around highlighted atoms (Canvas port
     * of the former renderer-internal composeOverlay rings). Ring radius tracks the Filament
     * visual sphere (world radius × screen scale) so rings hug the rendered sphere at any zoom.
     */
    fun drawSelectionRings(
        canvas: Canvas,
        w: Int,
        h: Int,
        scene: RenderScene,
        state: InteractionState,
        projection: SceneProjection,
    ) {
        val atoms = scene.atoms.associateBy { it.atom.id }
        if (atoms.isEmpty()) return
        val lockedIds = state.document.lockedMeasurements.flatMap { it.atomIds }.toSet() +
            state.document.inspection.lockedInspectedAtomIds
        val highlightedIds = state.document.selection.selectedAtomIds.toSet() + lockedIds +
            listOfNotNull(state.document.inspection.inspectedAtomId)
        if (highlightedIds.isEmpty()) return
        val scale = (w / 1080f).coerceIn(0.5f, 1.0f)
        fun project(id: Long): Pair<Float, Float>? {
            val atom = atoms[id]?.atom ?: return null
            val (px, py) = projection.project(atom.cartesianCoordinate.toVec3())
            return px.toFloat() to py.toFloat()
        }
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
    }

    private fun Double.formatFract() = "%.4f".format(this)

    // ── color helpers ──

    private fun withAlpha(argb: Int, alpha: Float): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun lighten(argb: Int, mix: Float): Int {
        val r = (((argb shr 16 and 0xFF) * (1f - mix) + 255 * mix)).toInt().coerceIn(0, 255)
        val g = (((argb shr 8 and 0xFF) * (1f - mix) + 255 * mix)).toInt().coerceIn(0, 255)
        val b = (((argb and 0xFF) * (1f - mix) + 255 * mix)).toInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}
