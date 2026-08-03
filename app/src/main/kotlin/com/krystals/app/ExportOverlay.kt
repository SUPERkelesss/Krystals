package com.krystals.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.krystals.interaction.measure.AngleTool
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.DistanceTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.measure.MeasurementSelection
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.sceneProjection
import com.krystals.renderer.core.style.AxisMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per v0.8.26: draws the viewer-style overlay (axes + measurement labels) onto an exported
 * bitmap. Uses the same [sceneProjection] as the on-screen overlay so the exported image
 * matches what the viewer shows. Pure native Canvas drawing — no Compose dependency.
 */
object ExportOverlay {

    fun apply(
        bitmap: Bitmap,
        scene: RenderScene,
        state: InteractionState,
        includeAxes: Boolean,
        includeMeasurements: Boolean,
    ) {
        if (!includeAxes && !includeMeasurements) return
        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        val projection = scene.sceneProjection(state.session.camera, w, h)
        if (includeAxes && scene.environment.axes.visible) drawAxes(canvas, w, h, scene, state, projection)
        if (includeMeasurements) drawMeasurements(canvas, scene, state, projection)
    }

    // ---------- axes ----------

    private fun drawAxes(
        canvas: Canvas,
        w: Int,
        h: Int,
        scene: RenderScene,
        state: InteractionState,
        projection: com.krystals.renderer.core.scene.SceneProjection,
    ) {
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
        val colors = listOf(0xFFE57373.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt())
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
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        val rotatedDirs = directions.mapIndexed { index, axis ->
            Triple(index, axis, (camera.rotation * axis).normalized())
        }
        fun drawArrow(index: Int, direction: com.krystals.crystal.core.math.Vec3) {
            val dx = direction.x.toFloat()
            val dy = -direction.y.toFloat()
            val projectedLength = sqrt(dx * dx + dy * dy)
            val visibleLength = arrowLength * projectedLength
            val ux = if (projectedLength > 0.0001f) dx / projectedLength else 0f
            val uy = if (projectedLength > 0.0001f) dy / projectedLength else 0f
            val endX = originX + ux * visibleLength
            val endY = originY + uy * visibleLength
            val headLength = headLengthBase
            val shaftEndX = endX - ux * headLength
            val shaftEndY = endY - uy * headLength
            val color = colors[index]
            val perpX = -uy
            val perpY = ux
            val halfWidth = 4f
            val shaftAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val shaftLen = (visibleLength - headLength).coerceAtLeast(0f)
            val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    originX, originY - halfWidth, originX, originY + halfWidth,
                    intArrayOf(withAlpha(color, 0.4f), color, lighten(color, 0.4f), color, withAlpha(color, 0.4f)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.save()
            canvas.rotate(shaftAngle, originX, originY)
            canvas.drawRect(originX, originY - halfWidth, originX + shaftLen, originY + halfWidth, shaftPaint)
            canvas.restore()
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
                intArrayOf(0xFFE0E0E0.toInt(), 0xFF68686F.toInt()), null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(originX, originY, 12f, hubPaint)
        rotatedDirs.filter { it.third.z > 0.0 }.forEach { (index, _, direction) -> drawArrow(index, direction) }
    }

    // ---------- measurements ----------

    private fun drawMeasurements(
        canvas: Canvas,
        scene: RenderScene,
        state: InteractionState,
        projection: com.krystals.renderer.core.scene.SceneProjection,
    ) {
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
        measurements.forEach { (selection, _, locked) ->
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
            val panelColor = if (locked) 0xD29966CC.toInt() else 0xA6000000.toInt()
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelColor }
            canvas.drawRoundRect(left, top, right, bottom, 14f, 14f, panelPaint)
            canvas.drawText(label, anchorX + 12f, anchorY - 12f, measurementPaint)
        }
    }

    // ---------- color helpers ----------

    private fun withAlpha(argb: Int, alpha: Float): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun lighten(argb: Int, mix: Float): Int {
        val r = (((argb shr 16 and 0xFF) * (1f - mix) + 255 * mix)).toInt().coerceIn(0, 255)
        val g = (((argb shr 8 and 0xFF) * (1f - mix) + 255 * mix)).toInt().coerceIn(0, 255)
        val b = (((argb and 0xFF) * (1f - mix) + 255 * mix)).toInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}
