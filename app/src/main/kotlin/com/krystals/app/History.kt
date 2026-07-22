package com.krystals.app

/** A bounded undo/redo controller. The caller records the state before applying an action. */
class HistoryController<T>(private val limit: Int = 10) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(before: T) {
        if (limit <= 0) return
        if (undoStack.size == limit) undoStack.removeFirst()
        undoStack.addLast(before)
        redoStack.clear()
    }

    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        if (redoStack.size == limit) redoStack.removeFirst()
        redoStack.addLast(current)
        return previous
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        if (undoStack.size == limit) undoStack.removeFirst()
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
