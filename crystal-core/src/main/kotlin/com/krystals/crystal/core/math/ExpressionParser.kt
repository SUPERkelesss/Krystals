package com.krystals.crystal.core.math

class ExpressionParser(private val source: String) {
    private var position = 0

    fun evaluate(): Double {
        val result = expression()
        skipWhitespace()
        require(position == source.length) { "Unexpected token at position $position" }
        require(result.isFinite()) { "Result is not finite" }
        return result
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            skipWhitespace()
            value = when (peek()) {
                '+' -> { position++; value + term() }
                '-' -> { position++; value - term() }
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = factor()
        while (true) {
            skipWhitespace()
            value = when (peek()) {
                '*' -> { position++; value * factor() }
                '/' -> {
                    position++
                    val divisor = factor()
                    require(kotlin.math.abs(divisor) > 1e-15) { "Division by zero" }
                    value / divisor
                }
                else -> return value
            }
        }
    }

    private fun factor(): Double {
        skipWhitespace()
        return when (peek()) {
            '+' -> { position++; factor() }
            '-' -> { position++; -factor() }
            '(' -> {
                position++
                val value = expression()
                skipWhitespace()
                require(peek() == ')') { "Missing ')'" }
                position++
                value
            }
            else -> number()
        }
    }

    private fun number(): Double {
        val start = position
        while (
            position < source.length &&
            (source[position].isDigit() || source[position] == '.' || source[position] == 'e' || source[position] == 'E' ||
                ((source[position] == '+' || source[position] == '-') && position > start &&
                    (source[position - 1] == 'e' || source[position - 1] == 'E')))
        ) position++
        require(position > start) { "Number expected at position $position" }
        return source.substring(start, position).toDouble()
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position].isWhitespace()) position++
    }

    private fun peek(): Char? = source.getOrNull(position)
}
