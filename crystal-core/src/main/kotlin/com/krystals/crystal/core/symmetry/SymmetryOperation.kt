package com.krystals.crystal.core.symmetry

import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.periodic.PeriodicBoundary

data class SymmetryOperation(
    val rotation: Mat3,
    val translation: Vec3,
    val source: String,
) {
    fun apply(value: FractionalCoordinate): FractionalCoordinate = PeriodicBoundary.wrap(
        FractionalCoordinate.fromVec3(rotation * value.toVec3() + translation),
    )

    companion object {
        val IDENTITY = SymmetryOperation(Mat3.IDENTITY, Vec3.ZERO, "x,y,z")

        fun parse(source: String): SymmetryOperation {
            val clean = source.trim().trim('\'', '"')
            val expressions = clean.split(',')
            require(expressions.size == 3) { "Invalid symmetry operation: $source" }
            val parsed = expressions.map(::parseLinearExpression)
            val rotation = Mat3(
                Vec3(parsed[0].first.x, parsed[1].first.x, parsed[2].first.x),
                Vec3(parsed[0].first.y, parsed[1].first.y, parsed[2].first.y),
                Vec3(parsed[0].first.z, parsed[1].first.z, parsed[2].first.z),
            )
            return SymmetryOperation(
                rotation,
                Vec3(parsed[0].second, parsed[1].second, parsed[2].second),
                clean,
            )
        }

        private fun parseLinearExpression(expression: String): Pair<Vec3, Double> {
            val normalized = expression.replace(" ", "").replace("-", "+-")
            var coefficients = Vec3.ZERO
            var offset = 0.0
            normalized.split('+').filter { it.isNotBlank() }.forEach { raw ->
                val term = raw.replace("*", "")
                val variable = term.lastOrNull()?.lowercaseChar()
                if (variable == 'x' || variable == 'y' || variable == 'z') {
                    val prefix = term.dropLast(1)
                    val coefficient = when (prefix) {
                        "", "+" -> 1.0
                        "-" -> -1.0
                        else -> parseFraction(prefix)
                    }
                    coefficients = when (variable) {
                        'x' -> coefficients.copy(x = coefficients.x + coefficient)
                        'y' -> coefficients.copy(y = coefficients.y + coefficient)
                        else -> coefficients.copy(z = coefficients.z + coefficient)
                    }
                } else {
                    offset += parseFraction(term)
                }
            }
            return coefficients to offset
        }
    }
}

fun parseFraction(value: String): Double {
    val clean = value.trim().trim('\'', '"')
    if ('/' !in clean) return clean.toDouble()
    val parts = clean.split('/', limit = 2)
    return parts[0].toDouble() / parts[1].toDouble()
}
