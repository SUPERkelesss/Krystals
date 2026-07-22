package com.krystals.renderer.filament

import com.krystals.renderer.core.scene.RenderScene
import com.krystals.interaction.selection.PickResult
import com.krystals.interaction.selection.Picker
import com.krystals.interaction.state.InteractionState
import android.graphics.Bitmap
import android.view.Surface

/** Android boundary for the Filament backend. Filament types stay out of renderer-core. */
interface FilamentSceneRenderer : Picker, AutoCloseable {
    fun attach(surface: Surface)
    fun detach()
    fun submit(scene: RenderScene)
    fun updateInteraction(state: InteractionState)
    suspend fun renderToBitmap(width: Int, height: Int): Bitmap?
    fun clear()
    override fun close()
}
