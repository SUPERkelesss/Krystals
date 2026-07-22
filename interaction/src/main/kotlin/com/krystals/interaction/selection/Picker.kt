package com.krystals.interaction.selection

data class PickResult(
    val objectId: String,
    val atomId: Long? = null,
    val siteId: String? = null,
)

fun interface Picker {
    suspend fun pick(x: Float, y: Float): PickResult?
}

class SelectionManager(private val maxSelection: Int = 4) {
    fun append(current: List<Long>, atomId: Long, expected: Int): List<Long> {
        val limit = expected.coerceIn(1, maxSelection)
        return if (current.size >= limit) listOf(atomId) else current + atomId
    }
}
