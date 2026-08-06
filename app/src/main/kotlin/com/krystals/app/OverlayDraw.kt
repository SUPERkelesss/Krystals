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
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.SceneProjection
import com.krystals.renderer.core.style.AxisMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per v0.8.38: shared native-Canvas drawing for the viewer-style overlay (axes + measurement
 * labels). Used by the on-screen overlay (ViewerBackendHost) and the export-image overlay
 * (ExportOverlay) so both stay pixel-identical. Pure android.graphics — no Compose dependency.
 *
 * Behaviour notes (unified from the two former copies): axis labels carry no text shadow, arrows
 * start at the centre-sphere surface (v0.8.27 on-screen behaviour), dihedral planes use the
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
            // v0.8.27: no text shadow on axis labels (a/b/c, X/Y/Z).
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
            // v0.8.27: arrow starts at the centre-sphere surface (hub radius 12f).
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
