package com.krystals.renderer.filament

import android.graphics.Bitmap
import android.view.Surface
import com.krystals.interaction.selection.PickResult
import com.krystals.interaction.selection.Picker
import com.krystals.interaction.state.InteractionState
import com.krystals.renderer.core.SceneRenderer
import com.krystals.renderer.core.scene.RenderScene

/** Android boundary for the Filament backend. Filament types stay out of renderer-core. */
interface FilamentSceneRenderer : SceneRenderer, Picker {
    fun attach(surface: Surface)
    fun detach()
    suspend fun renderToBitmap(width: Int = 0, height: Int = 0, useMsaa: Boolean = false): Bitmap?
    override fun close()
}
