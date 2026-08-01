package com.krystals.renderer.core.scene

import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatheredAtomGrouperTest {

    private fun atom(id: Long, siteId: String, x: Double, y: Double, z: Double, occ: Double = 1.0) =
        AtomImage(id, siteId, siteId, Species(siteId.takeWhile { it.isLetter() }), FractionalCoordinate.ZERO, CartesianCoordinate(x, y, z), occ, Int3(0, 0, 0))

    @Test
    fun twoAtomsSamePositionWithOverOccupancyProducesNormalizedGroup() {
        // occ 1.0 + 0.5 → Σ=1.5 > 1 → normalized: fractions 0.67, 0.33
        val atoms = listOf(atom(1L, "A1", 0.5, 0.0, 0.0, 1.0), atom(2L, "A2", 0.5, 0.0, 0.0, 0.5))
        val colors = mapOf("A1" to 0xFF0000FF, "A2" to 0xFFFF0000)
        val groups = GatheredAtomGrouper.group(atoms, colors)
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(listOf(1L, 2L), g.memberAtomIds)
        assertTrue(g.wasNormalized)
        assertEquals(0.0, g.remainderFraction, 1e-9)
        assertEquals(2, g.slices.size)
        val a1 = g.slices.first { it.siteId == "A1" }
        val a2 = g.slices.first { it.siteId == "A2" }
        assertEquals(1.0, a1.fraction + a2.fraction, 1e-9)
        assertTrue(a1.fraction > a2.fraction, "higher occ → larger fraction")
    }

    @Test
    fun partialOccupancyProducesRemainder() {
        // occ 0.4 + 0.4 → Σ=0.8 → fractions 0.4/0.4, remainder 0.2
        val atoms = listOf(atom(1L, "A1", 0.5, 0.0, 0.0, 0.4), atom(2L, "A2", 0.5, 0.0, 0.0, 0.4))
        val colors = mapOf("A1" to 0xFFFF0000, "A2" to 0xFF0000FF)
        val groups = GatheredAtomGrouper.group(atoms, colors)
        assertEquals(1, groups.size)
        val g = groups.single()
        assertFalse(g.wasNormalized)
        assertEquals(0.2, g.remainderFraction, 1e-9)
        assertEquals(0.8, g.slices.sumOf { it.fraction }, 1e-9)
    }

    @Test
    fun distinctPositionsProduceNoGroups() {
        val atoms = listOf(atom(1L, "A1", 0.0, 0.0, 0.0), atom(2L, "A2", 2.0, 0.0, 0.0))
        val groups = GatheredAtomGrouper.group(atoms, emptyMap())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun sameSiteAtomsAtSamePositionDoNotGroup() {
        // Two atoms of the SAME site at the same position → not a gathered group
        // (they're symmetry images, not disorder).
        val atoms = listOf(atom(1L, "A1", 0.5, 0.0, 0.0), atom(2L, "A1", 0.5, 0.0, 0.0))
        val groups = GatheredAtomGrouper.group(atoms, emptyMap())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun mixedColorBlendsByFractionWeight() {
        // Two atoms at 50/50 with red and blue → mixed = purple-ish
        val atoms = listOf(atom(1L, "A1", 0.5, 0.0, 0.0, 0.5), atom(2L, "A2", 0.5, 0.0, 0.0, 0.5))
        val colors = mapOf("A1" to 0xFFFF0000, "A2" to 0xFF0000FF)
        val groups = GatheredAtomGrouper.group(atoms, colors)
        val mixed = groups.single().mixedColor
        // Equal blend → non-zero R and B channels
        val r = (mixed shr 16 and 0xFF).toInt()
        val b = (mixed and 0xFF).toInt()
        assertTrue(r in 100..180, "R channel should be ~128, got $r")
        assertTrue(b in 100..180, "B channel should be ~128, got $b")
    }

    @Test
    fun epsilonThresholdGroupsCloseAtoms() {
        // 5e-5 Å apart → within 1e-4 threshold → grouped
        val close = listOf(atom(1L, "A1", 0.5, 0.0, 0.0), atom(2L, "A2", 0.5 + 5e-5, 0.0, 0.0))
        assertEquals(1, GatheredAtomGrouper.group(close, emptyMap()).size)
        // 2e-4 Å apart → beyond threshold → not grouped
        val far = listOf(atom(1L, "A1", 0.5, 0.0, 0.0), atom(2L, "A2", 0.5 + 2e-4, 0.0, 0.0))
        assertTrue(GatheredAtomGrouper.group(far, emptyMap()).isEmpty())
    }

    @Test
    fun groupByAtomIdReturnsNullForUngroupedAtoms() {
        val atoms = listOf(atom(1L, "A1", 0.0, 0.0, 0.0), atom(2L, "A2", 0.0, 0.0, 0.0), atom(3L, "B1", 2.0, 0.0, 0.0))
        val map = GatheredAtomGrouper.groupByAtomId(atoms, emptyMap())
        assertNotNull(map[1L])
        assertNotNull(map[2L])
        assertEquals(null, map[3L])
    }

    @Test
    fun singleAtomAtPositionDoesNotFormGroup() {
        val atoms = listOf(atom(1L, "A1", 0.5, 0.0, 0.0))
        val groups = GatheredAtomGrouper.group(atoms, emptyMap())
        assertTrue(groups.isEmpty())
    }
}
