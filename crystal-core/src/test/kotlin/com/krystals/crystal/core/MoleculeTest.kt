package com.krystals.crystal.core

import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.model.Molecule
import com.krystals.crystal.core.model.MoleculeAtom
import com.krystals.crystal.core.model.MoleculeBond
import com.krystals.crystal.core.model.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoleculeTest {

    private fun atom(id: Int, symbol: String, x: Double, y: Double, z: Double = 0.0) =
        MoleculeAtom(id, "$symbol$id", Species(symbol), CartesianCoordinate(x, y, z))

    /** 水分子:O 在原点,两个 H 呈 ~104.5° 键角,键长 ~0.96 Å。 */
    private fun water(): Molecule {
        val o = atom(0, "O", 0.0, 0.0, 0.0)
        val h1 = atom(1, "H", 0.757, 0.586, 0.0)
        val h2 = atom(2, "H", -0.757, 0.586, 0.0)
        return Molecule(
            "water",
            listOf(o, h1, h2),
            listOf(MoleculeBond(0, 1), MoleculeBond(0, 2)),
        )
    }

    /** 苯环:C6 平面六边形,环键。 */
    private fun benzene(): Molecule {
        val radius = 1.39
        val atoms = (0..5).map { i ->
            val angle = Math.toRadians(60.0 * i)
            atom(i, "C", radius * Math.cos(angle), radius * Math.sin(angle))
        }
        val bonds = (0..5).map { i -> MoleculeBond(i, (i + 1) % 6, 1.5) }
        return Molecule("benzene", atoms, bonds)
    }

    @Test fun finiteMoleculeCarriesAtomsAndBonds() {
        val m = water()
        assertEquals("water", m.name)
        assertEquals(3, m.atomCount)
        assertEquals(2, m.bondCount)
        assertEquals("O", m.atoms[0].species.symbol)
        assertEquals(Species("O"), m.atom(0)?.species)
        assertNull(m.atom(99))
    }

    @Test fun bondsAndNeighborsResolveByAtomId() {
        val m = water()
        // O 的两根键,otherEnd 分别指向两个 H
        assertEquals(listOf(1, 2), m.bondsOf(0).mapNotNull { it.otherEnd(0) })
        assertEquals(listOf(0), m.neighborsOf(1))
        assertEquals(listOf(0), m.neighborsOf(2))
        assertTrue(m.bondsOf(99).isEmpty())
        assertTrue(m.neighborsOf(99).isEmpty())
    }

    @Test fun bondHelpersReportEndsAndOrder() {
        val bond = MoleculeBond(0, 1, 2.0)
        assertEquals(2.0, bond.order)
        assertEquals(1, bond.otherEnd(0))
        assertEquals(0, bond.otherEnd(1))
        assertNull(bond.otherEnd(2))
        assertTrue(bond.connects(0))
        assertTrue(bond.connects(1))
    }

    @Test fun ringTopologyGivesEachAtomTwoNeighbors() {
        val m = benzene()
        assertEquals(6, m.atomCount)
        assertEquals(6, m.bondCount)
        for (i in 0..5) {
            assertEquals(setOf((i + 5) % 6, (i + 1) % 6), m.neighborsOf(i).toSet())
            assertEquals(1.5, m.bondsOf(i).first().order)
        }
    }

    @Test fun rejectsBondToUnknownAtom() {
        assertFailsWith<IllegalArgumentException> {
            Molecule("bad", listOf(atom(0, "H", 0.0, 0.0)), listOf(MoleculeBond(0, 7)))
        }
    }

    @Test fun rejectsDuplicateAtomIds() {
        assertFailsWith<IllegalArgumentException> {
            Molecule("dup", listOf(atom(0, "H", 0.0, 0.0), atom(0, "O", 1.0, 0.0)))
        }
    }

    @Test fun rejectsSelfLoopBond() {
        assertFailsWith<IllegalArgumentException> {
            Molecule("loop", listOf(atom(0, "H", 0.0, 0.0)), listOf(MoleculeBond(0, 0)))
        }
    }

    @Test fun moleculeIsImmutableValueWithCopy() {
        val m = water()
        val renamed = m.copy(name = "heavy-water")
        assertEquals("water", m.name)
        assertEquals("heavy-water", renamed.name)
        assertEquals(m.atoms, renamed.atoms)
        assertEquals(m.bonds, renamed.bonds)
        // 空键分子合法
        assertEquals(0, Molecule("empty", listOf(atom(0, "He", 0.0, 0.0))).bondCount)
    }
}
