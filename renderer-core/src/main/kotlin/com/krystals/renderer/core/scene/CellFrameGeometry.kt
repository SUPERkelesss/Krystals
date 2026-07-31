package com.krystals.renderer.core.scene

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.style.FrameMode

/**
 * Generates the world-space edges of the unit-cell frame grid.
 *
 * Both rendering backends consume these edges so the cell borders are drawn from
 * exactly the same geometry. The origin is the fractional (0,0,0) corner of the
 * lattice; [expansion] controls how many cells are shown when [mode] is
 * [FrameMode.ALL_CELLS].
 */
object CellFrameGeometry {
    fun edges(lattice: Lattice, expansion: Expansion, mode: FrameMode, structuralExpansion: Boolean = false): List<Pair<Vec3, Vec3>> {
        if (mode == FrameMode.NONE) return emptyList()

        // Per v0.6.5: when the expansion comes from a 3×3 matrix transformation (structural),
        // SINGLE_CELL should show the frame around the entire supercell, not just one cell.
        val useExpansion = mode == FrameMode.ALL_CELLS || (mode == FrameMode.SINGLE_CELL && structuralExpansion)
        val limitX = if (useExpansion) expansion.x else 1
        val limitY = if (useExpansion) expansion.y else 1
        val limitZ = if (useExpansion) expansion.z else 1

        val a = lattice.matrix.a
        val b = lattice.matrix.b
        val c = lattice.matrix.c

        val segments = linkedSetOf<Pair<Vec3, Vec3>>()
        for (x in 0..limitX) {
            for (y in 0..limitY) {
                val base = a * x.toDouble() + b * y.toDouble()
                segments += base to base + c * limitZ.toDouble()
            }
        }
        for (x in 0..limitX) {
            for (z in 0..limitZ) {
                val base = a * x.toDouble() + c * z.toDouble()
                segments += base to base + b * limitY.toDouble()
            }
        }
        for (y in 0..limitY) {
            for (z in 0..limitZ) {
                val base = b * y.toDouble() + c * z.toDouble()
                segments += base to base + a * limitX.toDouble()
            }
        }
        return segments.toList()
    }
}
