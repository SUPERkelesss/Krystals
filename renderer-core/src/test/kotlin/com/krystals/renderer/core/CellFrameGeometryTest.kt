package com.krystals.renderer.core.scene

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.renderer.core.style.FrameMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CellFrameGeometryTest {

    @Test
    fun noneModeReturnsEmptyEdges() {
        val lattice = Lattice.DEFAULT
        assertEquals(0, CellFrameGeometry.edges(lattice, Expansion(2, 2, 2), FrameMode.NONE).size)
    }

    @Test
    fun singleCellReturnsTwelveEdges() {
        val lattice = Lattice.DEFAULT
        val edges = CellFrameGeometry.edges(lattice, Expansion(2, 2, 2), FrameMode.SINGLE_CELL)
        assertEquals(12, edges.size)
    }

    @Test
    fun allCellsReturnsEdgesForFullGrid() {
        val lattice = Lattice.DEFAULT
        val edges = CellFrameGeometry.edges(lattice, Expansion(2, 1, 1), FrameMode.ALL_CELLS)
        // 2x1x1 grid => 3 cells along x, 2 along y, 2 along z (including boundaries).
        // Edges parallel to x: (2+1) * 2 * 2 = 12
        // Edges parallel to y: (2+1) * 2 * 2 = 12
        // Edges parallel to z: (2+1) * 2 * 2 = 12
        // But shared edges are deduplicated by the linked set.
        assertEquals(36, edges.size)
    }

    @Test
    fun originStartsAtZero() {
        val lattice = Lattice.DEFAULT
        val edges = CellFrameGeometry.edges(lattice, Expansion(1, 1, 1), FrameMode.SINGLE_CELL)
        val origin = edges.first().first
        assertEquals(0.0, origin.x, 1e-9)
        assertEquals(0.0, origin.y, 1e-9)
        assertEquals(0.0, origin.z, 1e-9)
    }
}
