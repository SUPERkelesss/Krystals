package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoleculeParserTest {

    private val lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0)
    private val dummyStructure = CrystalStructure(
        "test", lattice, SpaceGroupCatalog.resolve("P1", 1),
        listOf(SymmetryOperation.IDENTITY), emptyList(),
    )

    private fun a(
        id: Long,
        siteId: String,
        symbol: String,
        frac: FractionalCoordinate,
        cell: Int3 = Int3(0, 0, 0),
        shell: Boolean = false,
    ) = AtomImage(id, siteId, siteId, Species(symbol), frac, CartesianCoordinate.ZERO, 1.0, cell, shell, false)

    private fun b(aId: Long, bId: Long) =
        Bond(aId, bId, 1.0, BondRule("A", "B", 0.1, 3.0, BondRuleSource.AUTO))

    private fun network(atoms: List<AtomImage>, bonds: List<Bond>): BondNetwork =
        BondNetwork(atoms, bonds, dummyStructure, Expansion())

    // ── 端到端:BondDetector 真实成键 ─────────────────────────────────────

    private fun buildNet(sites: List<Site>, aLength: Double): BondNetwork {
        val structure = CrystalStructure(
            "test", Lattice(aLength, aLength, aLength, 90.0, 90.0, 90.0),
            SpaceGroupCatalog.resolve("P1", 1), listOf(SymmetryOperation.IDENTITY), sites,
        )
        return BondDetector.buildNetwork(structure, BondConfiguration())
    }

    @Test fun waterCrystalParsesToTwoMolecules() {
        // 单胞 2 个独立水分子(a=10,4 条 O-H 键)→ 2 个 Molecule,名称 "H2O"。
        val net = buildNet(
            listOf(
                Site("O", "O1", Species("O"), FractionalCoordinate(0.3, 0.3, 0.3)),
                Site("H", "H1", Species("H"), FractionalCoordinate(0.3, 0.3, 0.396)),
                Site("H", "H2", Species("H"), FractionalCoordinate(0.3, 0.396, 0.3)),
                Site("O", "O2", Species("O"), FractionalCoordinate(0.7, 0.7, 0.7)),
                Site("H", "H3", Species("H"), FractionalCoordinate(0.7, 0.7, 0.796)),
                Site("H", "H4", Species("H"), FractionalCoordinate(0.7, 0.796, 0.7)),
            ),
            10.0,
        )
        val molecules = net.toMolecules()
        assertEquals(2, molecules.size)
        assertTrue(molecules.all { it.name == "H2O" })
        assertTrue(molecules.all { it.atomCount == 3 && it.bondCount == 2 })
    }

    @Test fun whitePhosphorusParsesToOneP4Molecule() {
        // P4 四面体,中心 (0.25,0.25,0.25),P-P 键长 2.21 Å(a=18.5,d=0.0423),
        // 6 条分子内键 → 1 个 Molecule,名 "P4",6 键。
        val d = 0.0423
        val c = 0.25
        val molecules = buildNet(
            listOf(
                Site("P", "P1", Species("P"), FractionalCoordinate(c + d, c + d, c + d)),
                Site("P", "P2", Species("P"), FractionalCoordinate(c - d, c - d, c + d)),
                Site("P", "P3", Species("P"), FractionalCoordinate(c - d, c + d, c - d)),
                Site("P", "P4", Species("P"), FractionalCoordinate(c + d, c - d, c - d)),
            ),
            18.5,
        ).toMolecules()
        assertEquals(1, molecules.size)
        assertEquals("P4", molecules[0].name)
        assertEquals(6, molecules[0].bondCount)
    }

    // ── 算法逻辑:手造原子/键 ──────────────────────────────────────────────

    @Test fun twoWaterMoleculesProduceTwoMolecules() {
        // 两个独立水分子,全部键在单胞内 → 2 个 Molecule,各 3 原子 2 键。
        val o1 = a(1, "O", "O", FractionalCoordinate(0.3, 0.3, 0.3))
        val h1 = a(2, "H", "H", FractionalCoordinate(0.3, 0.3, 0.4))
        val h2 = a(3, "H", "H", FractionalCoordinate(0.3, 0.4, 0.3))
        val o2 = a(4, "O", "O", FractionalCoordinate(0.7, 0.7, 0.7))
        val h3 = a(5, "H", "H", FractionalCoordinate(0.7, 0.7, 0.8))
        val h4 = a(6, "H", "H", FractionalCoordinate(0.7, 0.8, 0.7))
        val molecules = network(
            listOf(o1, h1, h2, o2, h3, h4),
            listOf(b(1, 2), b(1, 3), b(4, 5), b(4, 6)),
        ).toMolecules()
        assertEquals(2, molecules.size)
        assertEquals(3, molecules[0].atomCount)
        assertEquals(2, molecules[0].bondCount)
        assertEquals(setOf("O", "H", "H"), molecules[0].atoms.map { it.species.symbol }.toSet())
        assertEquals(3, molecules[1].atomCount)
        assertEquals(2, molecules[1].bondCount)
    }

    @Test fun benzeneRingIsOneMolecule() {
        // 苯环:6 个 C 成环 → 1 个 Molecule,6 原子 6 键。
        val atoms = (1L..6L).map { a(it, "C", "C", FractionalCoordinate(0.1 * it, 0.1, 0.1)) }
        val bonds = (0..5).map { i -> b(i + 1L, ((i + 1) % 6) + 1L) }
        val molecules = network(atoms, bonds).toMolecules()
        assertEquals(1, molecules.size)
        assertEquals(6, molecules[0].atomCount)
        assertEquals(6, molecules[0].bondCount)
    }

    @Test fun crossCellDimerIsOneMoleculeWithCorrectGeometry() {
        // 有限二聚体横跨晶胞边界:Cl1 与 Cl2 在 (0,0,1) 的映像成键 → 1 个 Molecule
        // 2 原子 1 键;物理坐标展开后距离 = 真实键长,且 z 坐标差 = a(跨晶胞)。
        val d1 = a(1, "Cl", "Cl", FractionalCoordinate(0.1, 0.1, 0.1))
        val d2 = a(2, "Cl", "Cl", FractionalCoordinate(0.2, 0.2, 0.1))
        val d2p = a(3, "Cl", "Cl", FractionalCoordinate(0.2, 0.2, 1.1), cell = Int3(0, 0, 1), shell = true)
        val molecules = network(listOf(d1, d2, d2p), listOf(b(1, 3))).toMolecules()
        assertEquals(1, molecules.size)
        val m = molecules[0]
        assertEquals(2, m.atomCount)
        assertEquals(1, m.bondCount)
        val p1 = m.atom(1)!!.position
        val p2 = m.atom(2)!!.position
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val dz = p2.z - p1.z
        // 跨晶胞展开:z 差 = a = 10,距离 = |(0.1, 0.1, 1.0)| · a = sqrt(102)。
        assertEquals(1.0, dx, 1e-9)
        assertEquals(1.0, dy, 1e-9)
        assertEquals(10.0, dz, 1e-9)
        assertEquals(sqrt(102.0), sqrt(dx * dx + dy * dy + dz * dz), 1e-9)
    }

    @Test fun isolatedAtomIsSingleAtomMolecule() {
        // 无键原子 → 单原子 Molecule。
        val lone = a(1, "Ar", "Ar", FractionalCoordinate(0.5, 0.5, 0.5))
        val molecules = network(listOf(lone), emptyList()).toMolecules()
        assertEquals(1, molecules.size)
        assertEquals(1, molecules[0].atomCount)
        assertEquals(0, molecules[0].bondCount)
    }

    @Test fun hydrogenBondsDoNotSplitMolecules() {
        // 两个水分子 + 两条跨晶胞氢键(若计入会把两分子连成一个跨晶胞分量)。
        // 氢键是分子间弱作用,不参与分子解析 → 仍为 2 个独立 Molecule。
        val o1 = a(1, "O", "O", FractionalCoordinate(0.1, 0.1, 0.1))
        val h1 = a(2, "H", "H", FractionalCoordinate(0.1, 0.1, 0.2))
        val o2 = a(3, "O", "O", FractionalCoordinate(0.5, 0.5, 0.5))
        val h2 = a(4, "H", "H", FractionalCoordinate(0.5, 0.5, 0.6))
        val h2p = a(5, "H", "H", FractionalCoordinate(0.5, 0.5, 1.6), cell = Int3(0, 0, 1), shell = true)
        val h1p = a(6, "H", "H", FractionalCoordinate(0.1, 0.1, 1.2), cell = Int3(0, 0, 1), shell = true)
        val covalent = BondRule("O", "H", 0.5, 1.5, BondRuleSource.AUTO)
        val hbond = BondRule("O", "H", 1.5, 2.6, BondRuleSource.AUTO, isHBond = true)
        val molecules = network(
            listOf(o1, h1, o2, h2, h2p, h1p),
            listOf(
                Bond(1, 2, 1.0, covalent),
                Bond(3, 4, 1.0, covalent),
                Bond(1, 5, 2.4, hbond),
                Bond(3, 6, 2.4, hbond),
            ),
        ).toMolecules()
        assertEquals(2, molecules.size)
        assertTrue(molecules.all { it.atomCount == 2 && it.bondCount == 1 })
    }

    @Test fun emptyNetworkYieldsEmptyList() {
        assertEquals(0, network(emptyList(), emptyList()).toMolecules().size)
    }

    @Test fun moleculeNamesUseCompositionFormula() {
        // 水 → "H2O"。
        val water = network(
            listOf(
                a(1, "O", "O", FractionalCoordinate(0.3, 0.3, 0.3)),
                a(2, "H", "H", FractionalCoordinate(0.3, 0.3, 0.4)),
                a(3, "H", "H", FractionalCoordinate(0.3, 0.4, 0.3)),
            ),
            listOf(b(1, 2), b(1, 3)),
        ).toMolecules()
        assertEquals(1, water.size)
        assertEquals("H2O", water[0].name)

        // 苯:6 C 环 + 6 H → "C6H6"。
        val cAtoms = (1L..6L).map { a(it, "C", "C", FractionalCoordinate(0.1 * it, 0.1, 0.1)) }
        val hAtoms = (7L..12L).map { a(it, "H", "H", FractionalCoordinate(0.1 * (it - 6), 0.15, 0.1)) }
        val ringBonds = (0..5).map { i -> b(i + 1L, ((i + 1) % 6) + 1L) }
        val hBonds = (0..5).map { i -> b(i + 1L, i + 7L) }
        val benzene = network(cAtoms + hAtoms, ringBonds + hBonds).toMolecules()
        assertEquals(1, benzene.size)
        assertEquals("C6H6", benzene[0].name)

        // P4 四面体 → "P4"。
        val pAtoms = (1L..4L).map { a(it, "P", "P", FractionalCoordinate(0.1 * it, 0.1, 0.1)) }
        val pBonds = listOf(b(1, 2), b(1, 3), b(1, 4), b(2, 3), b(2, 4), b(3, 4))
        val p4 = network(pAtoms, pBonds).toMolecules()
        assertEquals(1, p4.size)
        assertEquals("P4", p4[0].name)

        // 孤立 Ar → "Ar"。
        val ar = network(listOf(a(1, "Ar", "Ar", FractionalCoordinate(0.5, 0.5, 0.5))), emptyList()).toMolecules()
        assertEquals(1, ar.size)
        assertEquals("Ar", ar[0].name)
    }
}
