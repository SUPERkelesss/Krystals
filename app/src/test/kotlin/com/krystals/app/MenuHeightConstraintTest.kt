package com.krystals.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the landscape menu height constraint (v0.7.0):
 * In landscape orientation, the DropdownMenu's height is constrained to
 * `screenHeightDp - 80.dp` to prevent it from covering the menu button.
 */
class MenuHeightConstraintTest {

    /**
     * Replicates the menu max-height calculation from the Compose code:
     * `val menuMaxHeight = LocalConfiguration.current.screenHeightDp.dp - 80.dp`
     */
    private fun menuMaxHeight(screenHeightDp: Int): Int = screenHeightDp - 80

    @Test
    fun `portrait screen has ample menu height`() {
        // Typical portrait phone: 800dp height
        val max = menuMaxHeight(800)
        assertEquals(720, max)
        assertTrue(max > 600, "portrait menu should have ample height (600+)")
    }

    @Test
    fun `landscape screen has constrained menu height`() {
        // Typical landscape phone: 360dp height
        val max = menuMaxHeight(360)
        assertEquals(280, max)
        // The key invariant: the menu height must be less than the screen height
        // so the popup doesn't grow taller than the available space and get
        // repositioned upward over the menu button.
        assertTrue(max < 360, "landscape menu must be shorter than screen height")
    }

    @Test
    fun `very short landscape screen still has positive menu height`() {
        // Extreme case: very short landscape (e.g., split-screen): 100dp
        val max = menuMaxHeight(100)
        assertEquals(20, max)
        assertTrue(max > 0, "menu height should still be positive")
    }

    @Test
    fun `menu height is always less than screen height`() {
        // The invariant that prevents the menu from covering the button:
        // the menu's max height must be strictly less than the screen height.
        listOf(240, 320, 360, 400, 480, 600, 800, 1000, 1200).forEach { screen ->
            val max = menuMaxHeight(screen)
            assertTrue(max < screen,
                "menu max height ($max) must be < screen height ($screen)")
        }
    }
}
