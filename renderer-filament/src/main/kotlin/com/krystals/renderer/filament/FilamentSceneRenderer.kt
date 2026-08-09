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
    /** Renders the current scene to a bitmap offscreen. [msaaSamples] 1 = no MSAA, 4 = MSAA 4x. */
    suspend fun renderToBitmap(width: Int = 0, height: Int = 0, msaaSamples: Int = 1): Bitmap?
    override fun close()
}
