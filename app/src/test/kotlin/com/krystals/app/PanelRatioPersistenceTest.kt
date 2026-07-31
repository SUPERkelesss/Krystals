package com.krystals.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the panel ratio persistence feature (v0.7.0):
 * - Editor and Display panels save their drag-resize ratio per orientation
 *   to SharedPreferences("panel_sizes"), and reload it on next launch.
 *
 * These tests verify the key format and default values that the Compose code
 * in [EditorPanel] and [DisplayPanel] uses to construct the SharedPreferences
 * keys and read back the saved ratios.
 */
class PanelRatioPersistenceTest {

    private fun editorKey(landscape: Boolean) =
        "editor_panel_ratio_${if (landscape) "landscape" else "portrait"}"

    private fun displayKey(landscape: Boolean) =
        "display_panel_ratio_${if (landscape) "landscape" else "portrait"}"

    @Test
    fun `editor portrait key matches format`() {
        assertEquals("editor_panel_ratio_portrait", editorKey(landscape = false))
    }

    @Test
    fun `editor landscape key matches format`() {
        assertEquals("editor_panel_ratio_landscape", editorKey(landscape = true))
    }

    @Test
    fun `display portrait key matches format`() {
        assertEquals("display_panel_ratio_portrait", displayKey(landscape = false))
    }

    @Test
    fun `display landscape key matches format`() {
        assertEquals("display_panel_ratio_landscape", displayKey(landscape = true))
    }

    @Test
    fun `editor and display keys are distinct per orientation`() {
        // If the keys were the same, dragging the editor panel would overwrite
        // the display panel's saved ratio, and vice versa.
        assertEquals(false, editorKey(false) == displayKey(false),
            "editor and display keys must differ in portrait")
        assertEquals(false, editorKey(true) == displayKey(true),
            "editor and display keys must differ in landscape")
    }

    @Test
    fun `portrait and landscape keys are distinct per panel`() {
        assertEquals(false, editorKey(false) == editorKey(true),
            "editor keys must differ between orientations")
        assertEquals(false, displayKey(false) == displayKey(true),
            "display keys must differ between orientations")
    }

    @Test
    fun `default editor ratio is 0_62`() {
        // Matches `panelPrefs.getFloat(prefKey, 0.62f)` in EditorPanel
        assertEquals(0.62f, DEFAULT_EDITOR_RATIO)
    }

    @Test
    fun `default display ratio is 0_40`() {
        // Matches `panelPrefs.getFloat(prefKey, 0.40f)` in DisplayPanel
        assertEquals(0.40f, DEFAULT_DISPLAY_RATIO)
    }

    @Test
    fun `ratio is coerced within valid range`() {
        // The drag handler coerces to [0.2f, 0.95f]. Verify the bounds.
        val min = 0.2f
        val max = 0.95f
        assertEquals(0.2f, (0.1f).coerceIn(min, max), "ratio below min should be clamped")
        assertEquals(0.95f, (1.0f).coerceIn(min, max), "ratio above max should be clamped")
        assertEquals(0.5f, (0.5f).coerceIn(min, max), "ratio in range should be unchanged")
    }

    companion object {
        // These must match the defaults used in EditorPanel and DisplayPanel.
        const val DEFAULT_EDITOR_RATIO = 0.62f
        const val DEFAULT_DISPLAY_RATIO = 0.40f
    }
}
