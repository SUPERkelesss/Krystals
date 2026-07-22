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
        val innerRing = FloatingBallLayout.toolOffsets(FloatingBallSnap.TOP_LEFT).take(3)
        val angles = innerRing.map { Math.toDegrees(atan2(it.y, it.x).toDouble()) }
        assertTrue(angles.last() - angles.first() >= 80.0)
    }
}
