package com.krystals.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.atan2

class FloatingBallLayoutTest {
    @Test fun snapsNearTopLeftCorner() {
        val position = FloatingBallLayout.snap(FloatPoint(12f, 18f), 1000f, 800f, 24f)
        assertEquals(FloatingBallSnap.TOP_LEFT, position.snap); assertEquals(0f, position.xFraction); assertEquals(0f, position.yFraction)
    }
    @Test fun staysFreeAwayFromEdges() { assertEquals(FloatingBallSnap.FREE, FloatingBallLayout.snap(FloatPoint(500f, 400f), 1000f, 800f, 24f).snap) }
    @Test fun everySnapProducesSixDistinctOffsets() { FloatingBallSnap.entries.forEach { assertEquals(6, FloatingBallLayout.toolOffsets(it).distinct().size) } }
    @Test fun cornerFanUsesMostOfOpenQuadrant() {
        // The corner fan spans 5°..85° across all six points; assert it covers most of the 90°
        // quadrant. (Previously took the first three points as the "inner ring" and compared the
        // first/last angle, but the third point is an outer-ring 5° point, making the sequence
        // non-monotonic and the assertion fail.)
        val angles = FloatingBallLayout.toolOffsets(FloatingBallSnap.TOP_LEFT).map { Math.toDegrees(atan2(it.y, it.x).toDouble()) }
        assertTrue(angles.max() - angles.min() >= 80.0)
    }
}
