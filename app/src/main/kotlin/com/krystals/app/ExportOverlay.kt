package com.krystals.app

import android.graphics.Bitmap
import android.graphics.Canvas
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.sceneProjection

/**
 * Per v0.8.26: draws the viewer-style overlay (axes + measurement labels) onto an exported
 * bitmap. Uses the same [sceneProjection] as the on-screen overlay so the exported image
 * matches what the viewer shows. All drawing is delegated to the shared [OverlayDraw]
 * implementation (same code path as the on-screen overlay). Pure native Canvas — no Compose.
 */
object ExportOverlay {

    fun apply(
        bitmap: Bitmap,
        scene: RenderScene,
        state: InteractionState,
        includeAxes: Boolean,
        includeMeasurements: Boolean,
        bondValenceBySite: Map<String, Double> = emptyMap(),
    ) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        val projection = scene.sceneProjection(state.session.camera, w, h)
        if (includeAxes && scene.environment.axes.visible) {
            OverlayDraw.drawAxes(canvas, w, h, scene, state, projection)
        }
        if (includeMeasurements) {
            OverlayDraw.drawMeasurements(canvas, scene, state, projection)
        }
    }
}
