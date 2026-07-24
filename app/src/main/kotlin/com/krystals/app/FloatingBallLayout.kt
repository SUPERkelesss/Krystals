package com.krystals.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class FloatPoint(val x: Float, val y: Float)

enum class FloatingBallSnap {
    FREE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
}

data class FloatingBallPosition(
    val xFraction: Float = 0.86f,
    val yFraction: Float = 0.78f,
    val snap: FloatingBallSnap = FloatingBallSnap.FREE,
)

object FloatingBallLayout {
    fun snap(center: FloatPoint, width: Float, height: Float, threshold: Float): FloatingBallPosition {
        if (width <= 0f || height <= 0f) return FloatingBallPosition()
        val nearLeft = center.x <= threshold
        val nearRight = width - center.x <= threshold
        val nearTop = center.y <= threshold
        val nearBottom = height - center.y <= threshold
        val snap = when {
            nearLeft && nearTop -> FloatingBallSnap.TOP_LEFT
            nearRight && nearTop -> FloatingBallSnap.TOP_RIGHT
            nearLeft && nearBottom -> FloatingBallSnap.BOTTOM_LEFT
            nearRight && nearBottom -> FloatingBallSnap.BOTTOM_RIGHT
            nearLeft -> FloatingBallSnap.LEFT
            nearRight -> FloatingBallSnap.RIGHT
            nearTop -> FloatingBallSnap.TOP
            nearBottom -> FloatingBallSnap.BOTTOM
            else -> FloatingBallSnap.FREE
        }
        val x = when (snap) {
            FloatingBallSnap.LEFT, FloatingBallSnap.TOP_LEFT, FloatingBallSnap.BOTTOM_LEFT -> 0f
            FloatingBallSnap.RIGHT, FloatingBallSnap.TOP_RIGHT, FloatingBallSnap.BOTTOM_RIGHT -> 1f
            else -> (center.x / width).coerceIn(0f, 1f)
        }
        val y = when (snap) {
            FloatingBallSnap.TOP, FloatingBallSnap.TOP_LEFT, FloatingBallSnap.TOP_RIGHT -> 0f
            FloatingBallSnap.BOTTOM, FloatingBallSnap.BOTTOM_LEFT, FloatingBallSnap.BOTTOM_RIGHT -> 1f
            else -> (center.y / height).coerceIn(0f, 1f)
        }
        return FloatingBallPosition(x, y, snap)
    }

    fun toolOffsets(snap: FloatingBallSnap): List<FloatPoint> = when (snap) {
        FloatingBallSnap.TOP_LEFT -> cornerFan(0.0)
        FloatingBallSnap.TOP_RIGHT -> cornerFan(90.0)
        FloatingBallSnap.BOTTOM_RIGHT -> cornerFan(180.0)
        FloatingBallSnap.BOTTOM_LEFT -> cornerFan(270.0)
        FloatingBallSnap.LEFT -> sideTriangles(1f)
        FloatingBallSnap.RIGHT -> sideTriangles(-1f)
        FloatingBallSnap.TOP -> horizontalTriangles(1f)
        FloatingBallSnap.BOTTOM -> horizontalTriangles(-1f)
        FloatingBallSnap.FREE -> radial()
    }

    private fun cornerFan(startDegrees: Double): List<FloatPoint> = buildList {
        // Tier 1: radius 58, 2 buttons at 15° and 75°
        listOf(15.0, 75.0).forEach { deg -> add(polar(58f, startDegrees + deg)) }
        // Tier 2: radius 98, 4 buttons at 5°, 25°, 65°, 85°
        listOf(5.0, 30.0, 60.0, 85.0).forEach { deg -> add(polar(98f, startDegrees + deg)) }
    }

    private fun sideTriangles(direction: Float) = listOf(
        FloatPoint(62f * direction, 0f), FloatPoint(31f * direction, -54f), FloatPoint(31f * direction, 54f),
        FloatPoint(108f * direction, 0f), FloatPoint(78f * direction, -54f), FloatPoint(78f * direction, 54f),
    )

    private fun horizontalTriangles(direction: Float) = listOf(
        FloatPoint(0f, 62f * direction), FloatPoint(-54f, 31f * direction), FloatPoint(54f, 31f * direction),
        FloatPoint(0f, 108f * direction), FloatPoint(-54f, 78f * direction), FloatPoint(54f, 78f * direction),
    )

    private fun radial() = List(6) { index -> polar(62f, index * 60.0 - 90.0) }

    private fun polar(radius: Float, degrees: Double): FloatPoint {
        val angle = degrees / 180.0 * PI
        return FloatPoint((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
    }
}
