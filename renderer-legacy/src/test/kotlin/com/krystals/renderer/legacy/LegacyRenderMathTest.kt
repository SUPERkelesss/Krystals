package com.krystals.renderer.legacy

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.rotX
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LegacyRenderMathTest {
    @Test fun positiveCameraDepthIsNearAndDrawnAfterNegativeDepth() {
        assertEquals(2.0, legacyCameraDepth(Vec3(0.0, 0.0, 2.0)))
        assertEquals(-2.0, legacyCameraDepth(Vec3(0.0, 0.0, -2.0)))

        val drawOrder = listOf(2.0, -2.0, 0.0).legacyBackToFront { it }
        assertEquals(listOf(-2.0, 0.0, 2.0), drawOrder)
    }

    @Test fun highlightMovesFromRimToCenterWithElevation() {
        val grazing = legacyHighlightOffset(10.0, 0f, 0f)
        assertEquals(9.5, grazing.x, 1e-9)
        assertEquals(0.0, grazing.y, 1e-9)

        val quarterTurn = legacyHighlightOffset(10.0, 90f, 0f)
        assertEquals(0.0, quarterTurn.x, 1e-9)
        assertEquals(9.5, quarterTurn.y, 1e-9)

        val overhead = legacyHighlightOffset(10.0, 35f, 90f)
        assertEquals(0.0, sqrt(overhead.x * overhead.x + overhead.y * overhead.y), 1e-9)
    }

    @Test fun expandedCellBoundsUseAllEightSupercellCorners() {
        val lattice = Lattice(2.0, 4.0, 6.0, 90.0, 90.0, 90.0)
        val range = expandedCellDepthRange(
            lattice,
            Expansion(2, 3, 4),
            Vec3(2.0, 6.0, 12.0),
            Mat3.IDENTITY,
        )
        assertNotNull(range)
        assertEquals(-12.0, range.min, 1e-9)
        assertEquals(12.0, range.max, 1e-9)
        assertEquals(-3f, range.normalizedDepth(-12.0))
        assertEquals(0f, range.normalizedDepth(0.0))
        assertEquals(3f, range.normalizedDepth(12.0))
    }

    @Test fun rotatedBoundsFollowCellGeometry() {
        val lattice = Lattice(2.0, 4.0, 6.0, 90.0, 90.0, 90.0)
        val range = expandedCellDepthRange(
            lattice,
            Expansion(),
            Vec3(1.0, 2.0, 3.0),
            rotX(90.0),
        )
        assertNotNull(range)
        assertEquals(-2.0, range.min, 1e-9)
        assertEquals(2.0, range.max, 1e-9)
    }

    @Test fun depthCueMixIsConstantThenLinearFromOneToZero() {
        assertEquals(1f, legacyDepthCueFogValue(-5f, near = 2f, far = -2f))
        assertEquals(1f, legacyDepthCueFogValue(-2f, near = 2f, far = -2f))
        assertEquals(0.5f, legacyDepthCueFogValue(0f, near = 2f, far = -2f))
        assertEquals(0f, legacyDepthCueFogValue(2f, near = 2f, far = -2f))
        assertEquals(0f, legacyDepthCueFogValue(5f, near = 2f, far = -2f))
    }

    @Test fun depthCueHandlesDegenerateThresholdAndMissingRange() {
        assertEquals(1f, legacyDepthCueFogValue(0f, near = 1f, far = 1f))
        assertEquals(0f, legacyDepthCueFogValue(1f, near = 1f, far = 1f))
        assertEquals(0f, legacyDepthCueFog(1.0, range = null, near = 2f, far = -2f))
        assertNull(LegacyDepthRange(1.0, 1.0).normalizedDepth(1.0))
    }

    @Test fun depthCueArgbMixPreservesSourceAlpha() {
        val source = 0x40102030
        val background = 0xFF90A0B0.toInt()

        val halfway = legacyBlendArgbPreservingAlpha(source, background, 0.5f)
        val backgroundRgb = legacyBlendArgbPreservingAlpha(source, background, 1f)

        assertEquals(0x40, halfway ushr 24)
        assertEquals(0x40506070, halfway)
        assertEquals(0x4090A0B0, backgroundRgb)
    }

    @Test fun reflectionEndpointsExposeStrongerFullScaleValues() {
        val off = legacyReflectionParameters(intensity = 0f, diffusion = 0f)
        assertEquals(0f, off.highlightAlpha)
        assertEquals(0f, off.highlightMiddleAlpha)
        assertEquals(0f, off.bondHighlightFactor)
        assertEquals(0f, off.polyhedronSpecularFactor)
        assertEquals(0.35f, off.radialRadiusMultiplier)
        assertEquals(0.06f, off.bondHighlightBand)

        val full = legacyReflectionParameters(intensity = 1f, diffusion = 1f)
        assertEquals(1f, full.highlightAlpha)
        assertEquals(0.4f, full.highlightMiddleAlpha)
        assertEquals(1.5f, full.radialRadiusMultiplier)
        assertEquals(0.75f, full.bondHighlightFactor)
        assertEquals(0.32f, full.bondHighlightBand)
        assertEquals(0.85f, full.polyhedronSpecularFactor)
    }
}
