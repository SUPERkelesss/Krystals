package com.krystals.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryTest {
    @Test fun keepsOnlyTenPreviousStates() {
        val history = HistoryController<Int>(10); repeat(12) { history.record(it) }; var current = 12
        repeat(10) { current = history.undo(current)!! }
        assertEquals(2, current); assertNull(history.undo(current))
    }
    @Test fun newActionClearsRedo() {
        val history = HistoryController<Int>(); history.record(1); assertEquals(1, history.undo(2)); assertTrue(history.canRedo)
        history.record(3); assertFalse(history.canRedo)
    }
    @Test fun redoRestoresUndoneState() {
        val history = HistoryController<Int>(); history.record(1); val previous = history.undo(2)!!; assertEquals(2, history.redo(previous))
    }
}
