package com.krystals.app

import com.krystals.renderer.core.style.ViewerAppearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AppearanceStoreTest {
    @Test
    fun appearanceJsonDoesNotExposeAmbientOcclusion() {
        val json = AppearanceStore.run { ViewerAppearance().toJson() }
        assertFalse(json.contains("ambientOcclusion", ignoreCase = true))
    }

    @Test
    fun legacyDefaultElevationMigratesCloserToAtomEdge() {
        val restored = AppearanceStore.fromJson("{\"lightElevation\":60}")
        assertNotNull(restored)
        assertEquals(30f, restored.lightElevation, 0.0001f)
    }
}
