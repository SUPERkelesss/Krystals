package com.krystals.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RendererBackendTest {
    @Test
    fun newAndInvalidPreferencesDefaultToFilament() {
        assertEquals(RendererBackend.FILAMENT, RendererBackendStore.fromStored(null))
        assertEquals(RendererBackend.FILAMENT, RendererBackendStore.fromStored("UNKNOWN"))
    }

    @Test
    fun sessionFailureFallsBackWithoutChangingPreference() {
        val preferred = RendererBackend.FILAMENT
        assertEquals(RendererBackend.CANVAS_LEGACY, effectiveBackend(preferred, filamentSessionFailed = true))
        assertEquals(RendererBackend.FILAMENT, preferred)
    }

    @Test
    fun explicitCanvasSelectionRemainsCanvas() {
        assertEquals(RendererBackend.CANVAS_LEGACY, effectiveBackend(RendererBackend.CANVAS_LEGACY, filamentSessionFailed = false))
    }
}
