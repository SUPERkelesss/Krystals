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
    fun edges(lattice: Lattice, expansion: Expansion, mode: FrameMode): List<Pair<Vec3, Vec3>> {
        if (mode == FrameMode.NONE) return emptyList()

        val limitX = if (mode == FrameMode.ALL_CELLS) expansion.x else 1
        val limitY = if (mode == FrameMode.ALL_CELLS) expansion.y else 1
        val limitZ = if (mode == FrameMode.ALL_CELLS) expansion.z else 1

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
