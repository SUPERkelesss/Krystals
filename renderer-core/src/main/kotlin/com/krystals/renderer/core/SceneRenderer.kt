package com.krystals.renderer.core

import com.krystals.renderer.core.state.InteractionState
import com.krystals.renderer.core.scene.RenderScene

/**
 * Backend-neutral SPI for a Krystals scene renderer.
 *
 * The core owns this interface so that the application can treat Filament and
 * Canvas-Legacy as interchangeable backends. UI-specific lifecycle methods
 * (Surface attachment, bitmap export) live in backend-specific sub-interfaces;
 * this interface only covers scene submission and interaction state updates.
 *
 * Picking is deliberately not part of this SPI because the [Picker] abstraction
 * lives in the interaction module, which depends on renderer-core. Each backend
 * implements [Picker] independently.
 */
interface SceneRenderer : AutoCloseable {
    /** Replace the currently displayed scene with [scene]. */
    fun submit(scene: RenderScene)

    /** Update the renderer to reflect a new [InteractionState]. */
    fun updateInteraction(state: InteractionState)

    /** Clear the current scene and release any per-scene resources. */
    fun clear()
}
