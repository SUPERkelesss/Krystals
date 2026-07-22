package com.krystals.renderer.filament

import com.krystals.renderer.core.scene.RenderScene

/** Backend boundary for the next phase. No Filament types leak into renderer-core. */
interface FilamentSceneRenderer : AutoCloseable {
    fun submit(scene: RenderScene)
    fun clear()
    override fun close()
}
