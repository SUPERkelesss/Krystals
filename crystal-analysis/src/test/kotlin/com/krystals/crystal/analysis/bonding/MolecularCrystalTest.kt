package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MolecularCrystalTest {

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

    // ── 算法逻辑:手造原子/键 ──────────────────────────────────────────────

    @Test fun independentMoleculesAreMolecular() {
        // 两个独立水分子,全部键在单胞内。
        val o1 = a(1, "O", "O", FractionalCoordinate(0.3, 0.3, 0.3))
        val h1 = a(2, "H", "H", FractionalCoordinate(0.3, 0.3, 0.4))
        val h2 = a(3, "H", "H", FractionalCoordinate(0.3, 0.4, 0.3))
        val o2 = a(4, "O", "O", FractionalCoordinate(0.7, 0.7, 0.7))
        val h3 = a(5, "H", "H", FractionalCoordinate(0.7, 0.7, 0.8))
        val h4 = a(6, "H", "H", FractionalCoordinate(0.7, 0.8, 0.7))
        val net = network(
            listOf(o1, h1, h2, o2, h3, h4),
            listOf(b(1, 2), b(1, 3), b(4, 5), b(4, 6)),
        )
        assertTrue(net.isMolecularCrystal())
    }

    @Test fun intramolecularRingWithZeroOffsetIsMolecular() {
        // 苯环:6 个 C 成环,环偏移和为零(分子内环)。
        val atoms = (1L..6L).map { a(it, "C", "C", FractionalCoordinate(0.1 * it, 0.1, 0.1)) }
        val bonds = (0..5).map { i -> b(i + 1L, ((i + 1) % 6) + 1L) }
        assertTrue(network(atoms, bonds).isMolecularCrystal())
    }

    @Test fun polymerChainAcrossCellBoundaryIsNotMolecular() {
        // 单胞内链段 A0-A1-A2,跨晶胞键 A2-A0'(A0 在 (0,0,1) 的映像)闭合回 A0,
        // 构成偏移 (0,0,1) 的基本环 —— 无限聚合物,不是分子晶体。
        val a0 = a(1, "A", "C", FractionalCoordinate(0.1, 0.1, 0.1))
        val a1 = a(2, "A", "C", FractionalCoordinate(0.2, 0.1, 0.1))
        val a2 = a(3, "A", "C", FractionalCoordinate(0.3, 0.1, 0.1))
        val a0p = a(4, "A", "C", FractionalCoordinate(0.1, 0.1, 1.1), cell = Int3(0, 0, 1), shell = true)
        val net = network(listOf(a0, a1, a2, a0p), listOf(b(1, 2), b(2, 3), b(3, 4)))
        assertFalse(net.isMolecularCrystal())
    }

    @Test fun atomBondingToOwnTranslationImageIsNotMolecular() {
        // 金属式周期自连接:原子与其 (0,0,1) 平移映像成键。
        val m = a(1, "M", "Fe", FractionalCoordinate(0.5, 0.5, 0.5))
        val mp = a(2, "M", "Fe", FractionalCoordinate(0.5, 0.5, 1.5), cell = Int3(0, 0, 1), shell = true)
        assertFalse(network(listOf(m, mp), listOf(b(1, 2))).isMolecularCrystal())
    }

    @Test fun parallelBondsWithDifferentOffsetsAreNotMolecular() {
        // X-Y 单胞内键 + X'-Y 跨晶胞键(偏移 (-1,0,0)):平行边,基本环偏移非零。
        val x = a(1, "X", "C", FractionalCoordinate(0.1, 0.1, 0.1))
        val y = a(2, "Y", "C", FractionalCoordinate(0.2, 0.2, 0.2))
        val xp = a(3, "X", "C", FractionalCoordinate(1.1, 0.1, 0.1), cell = Int3(1, 0, 0), shell = true)
        val net = network(listOf(x, y, xp), listOf(b(1, 2), b(3, 2)))
        assertFalse(net.isMolecularCrystal())
    }

    @Test fun finiteDimerAcrossCellBoundaryIsMolecular() {
        // 有限二聚体横跨晶胞边界:Cl1 与 Cl2 在 (0,0,1) 的映像成键,单条跨晶胞键,
        // 无环 —— 仍是有限分子。
        val d1 = a(1, "Cl", "Cl", FractionalCoordinate(0.1, 0.1, 0.1))
        val d2 = a(2, "Cl", "Cl", FractionalCoordinate(0.2, 0.2, 0.2))
        val d2p = a(3, "Cl", "Cl", FractionalCoordinate(0.2, 0.2, 1.2), cell = Int3(0, 0, 1), shell = true)
        assertTrue(network(listOf(d1, d2, d2p), listOf(b(1, 3))).isMolecularCrystal())
    }

    @Test fun isolatedAtomsAndEmptyNetworksAreMolecular() {
        val lone = a(1, "Ar", "Ar", FractionalCoordinate(0.5, 0.5, 0.5))
        assertTrue(network(listOf(lone), emptyList()).isMolecularCrystal())
        assertTrue(network(emptyList(), emptyList()).isMolecularCrystal())
    }

    @Test fun resultIsIndependentOfExpansion() {
        // 2×2×2 的分子拷贝:每拷贝一个水分子,拷贝间无键。
        val atoms = mutableListOf<AtomImage>()
        val bonds = mutableListOf<Bond>()
        var id = 1L
        for (ix in 0..1) for (iy in 0..1) for (iz in 0..1) {
            val cell = Int3(ix, iy, iz)
            val o = a(id++, "O", "O", FractionalCoordinate(0.3 + ix, 0.3 + iy, 0.3 + iz), cell)
            val h1 = a(id++, "H", "H", FractionalCoordinate(0.3 + ix, 0.3 + iy, 0.4 + iz), cell)
            val h2 = a(id++, "H", "H", FractionalCoordinate(0.3 + ix, 0.4 + iy, 0.3 + iz), cell)
            atoms += listOf(o, h1, h2)
            bonds += listOf(b(o.id, h1.id), b(o.id, h2.id))
        }
        assertTrue(network(atoms, bonds).isMolecularCrystal())
    }

    // ── 端到端:BondDetector 真实成键 ─────────────────────────────────────

    private fun buildNet(sites: List<Site>, aLength: Double): BondNetwork {
        val structure = CrystalStructure(
            "test", Lattice(aLength, aLength, aLength, 90.0, 90.0, 90.0),
            SpaceGroupCatalog.resolve("P1", 1), listOf(SymmetryOperation.IDENTITY), sites,
        )
        return BondDetector.buildNetwork(structure, BondConfiguration())
    }

    @Test fun csClIonicNetworkIsNotMolecular() {
        val net = buildNet(
            listOf(
                Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate.ZERO),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
            4.0,
        )
        assertFalse(net.isMolecularCrystal())
    }

    @Test fun diamondCovalentNetworkIsNotMolecular() {
        val diamondSites = listOf(
            Site("C", "C1", Species("C"), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site("C", "C2", Species("C"), FractionalCoordinate(0.0, 0.5, 0.5)),
            Site("C", "C3", Species("C"), FractionalCoordinate(0.5, 0.0, 0.5)),
            Site("C", "C4", Species("C"), FractionalCoordinate(0.5, 0.5, 0.0)),
            Site("C", "C5", Species("C"), FractionalCoordinate(0.25, 0.25, 0.25)),
            Site("C", "C6", Species("C"), FractionalCoordinate(0.75, 0.75, 0.25)),
            Site("C", "C7", Species("C"), FractionalCoordinate(0.75, 0.25, 0.75)),
            Site("C", "C8", Species("C"), FractionalCoordinate(0.25, 0.75, 0.75)),
        )
        assertFalse(buildNet(diamondSites, 3.57).isMolecularCrystal())
    }

    @Test fun bodyCentredMetalIsNotMolecular() {
        // bcc Fe:a=2.87,近邻 √3/2·a = 2.48 Å < 共价窗口 1.16+1.16+0.45,成键网络跨晶胞。
        val net = buildNet(
            listOf(
                Site("Fe", "Fe1", Species("Fe"), FractionalCoordinate.ZERO),
                Site("Fe", "Fe2", Species("Fe"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
            2.87,
        )
        assertTrue(net.bonds.size >= 8, "expected bcc Fe bond network, got ${net.bonds.size} bonds")
        assertFalse(net.isMolecularCrystal())
    }

    @Test fun waterMoleculesInCellAreMolecular() {
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
        // 4 条 O-H 键、两个独立水分子。
        assertTrue(net.bonds.size == 4, "expected 4 O-H bonds, got ${net.bonds.size}")
        assertTrue(net.isMolecularCrystal())
    }

    @Test fun whitePhosphorusP4IsMolecular() {
        // P4 四面体,中心 (0.25,0.25,0.25),P-P 键长 2.21 Å(a=18.5)。
        // 中心到顶点 1.354 Å → 分数 √3·d = 1.354/18.5 → d = 0.0423。
        val d = 0.0423
        val c = 0.25
        val p4Sites = listOf(
            Site("P", "P1", Species("P"), FractionalCoordinate(c + d, c + d, c + d)),
            Site("P", "P2", Species("P"), FractionalCoordinate(c - d, c - d, c + d)),
            Site("P", "P3", Species("P"), FractionalCoordinate(c - d, c + d, c - d)),
            Site("P", "P4", Species("P"), FractionalCoordinate(c + d, c - d, c - d)),
        )
        val net = buildNet(p4Sites, 18.5)
        assertTrue(net.bonds.size == 6, "expected 6 intra-molecular P-P bonds, got ${net.bonds.size}")
        assertTrue(net.isMolecularCrystal())
    }
}
