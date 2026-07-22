package com.krystals.app

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorPanelsFormattingTest {
    @Test
    fun bondRadiusUsesTwoDecimalPlaces() {
        assertEquals("0.20", formatSliderValue(0.2f, decimals = 2))
        assertEquals("0.02", formatSliderValue(0.02f, decimals = 2))
    }
}
