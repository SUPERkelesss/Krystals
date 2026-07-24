package com.krystals.renderer.core.style

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackgroundColorTest {

    @Test
    fun preservesOriginalArgb() {
        val argb = 0xFF101014L
        val color = backgroundColor(argb)
        assertEquals(argb, color.srgbArgb)
    }

    @Test
    fun blackIsZeroInLinear() {
        val color = backgroundColor(0xFF000000L)
        assertEquals(0f, color.linearRgb[0], 1e-6f)
        assertEquals(0f, color.linearRgb[1], 1e-6f)
        assertEquals(0f, color.linearRgb[2], 1e-6f)
    }

    @Test
    fun midGrayLinearIsBrighterThanSrgbInput() {
        // 0.5 in sRGB => roughly 0.214 in linear
        val color = backgroundColor(0xFF808080L)
        assertEquals(0.21404f, color.linearRgb[0], 1e-4f)
        assertEquals(0.21404f, color.linearRgb[1], 1e-4f)
        assertEquals(0.21404f, color.linearRgb[2], 1e-4f)
    }

    @Test
    fun whiteIsOneInLinear() {
        val color = backgroundColor(0xFFFFFFFFL)
        assertEquals(1f, color.linearRgb[0], 1e-6f)
        assertEquals(1f, color.linearRgb[1], 1e-6f)
        assertEquals(1f, color.linearRgb[2], 1e-6f)
    }
}
