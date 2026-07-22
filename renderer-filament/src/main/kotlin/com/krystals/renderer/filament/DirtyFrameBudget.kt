package com.krystals.renderer.filament

internal class DirtyFrameBudget {
    var pending: Int = 0
        private set

    val hasPending: Boolean get() = pending > 0

    fun request(count: Int) {
        pending = maxOf(pending, count.coerceAtLeast(0))
    }

    fun rendered() {
        if (pending > 0) pending--
    }

    fun reset() {
        pending = 0
    }
}
