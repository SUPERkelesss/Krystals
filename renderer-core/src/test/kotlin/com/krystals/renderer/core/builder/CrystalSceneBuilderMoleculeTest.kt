package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.Bond
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.toHydrogenBond
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.HydrogenBond
import com.krystals.crystal.core.model.Molecule
import com.krystals.crystal.core.model.MoleculeAtom
import com.krystals.crystal.core.model.MoleculeBond
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.RenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 分子展开(molecule-extend)场景构建:两独立水分子 + 跨晶胞键的氯分子。
 * 测试直接构造 [BondNetwork](不经过 BondDetector),精确控制原胞原子与外部壳层映像。
 *
 * - 非分子展开(moleculeExtend=false):外部壳层原子不可见(现状,不回归)。
 * - 分子展开:分子原子(含外部壳层映像)可见、跨胞键可见;游离(非分子)原子的外部映像不可见。
 * - hiddenSiteIds 联动:隐藏分子内原胞原子 → 该原子映像隐藏;全隐藏 → 整分子(含跨胞键)隐藏。
 */
class CrystalSceneBuilderMoleculeTest {

    private val structure = CrystalStructure(
        blockName = "molecules",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(
            Site("O1", "O1", Species("O"), FractionalCoordinate(0.2, 0.2, 0.2)),
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.3, 0.2, 0.2)),
            Site("H2", "H2", Species("H"), FractionalCoordinate(0.2, 0.3, 0.2)),
            Site("O2", "O2", Species("O"), FractionalCoordinate(0.7, 0.7, 0.7)),
            Site("H3", "H3", Species("H"), FractionalCoordinate(0.8, 0.7, 0.7)),
            Site("H4", "H4", Species("H"), FractionalCoordinate(0.7, 0.8, 0.7)),
            Site("Cl1", "Cl1", Species("Cl"), FractionalCoordinate(0.05, 0.5, 0.5)),
            Site("Cl2", "Cl2", Species("Cl"), FractionalCoordinate(0.95, 0.5, 0.5)),
            Site("X1", "X1", Species("C"), FractionalCoordinate(0.4, 0.4, 0.4)),
        ),
    )

    private fun atom(
        id: Long,
        siteId: String,
        symbol: String,
        fx: Double, fy: Double, fz: Double,
        offset: Int3 = Int3(0, 0, 0),
        shell: Boolean = false,
        boundary: Boolean = false,
    ) = AtomImage(
        id = id,
        siteId = siteId,
        siteLabel = siteId,
        species = Species(symbol),
        fractionalCoordinate = FractionalCoordinate(fx, fy, fz),
        cartesianCoordinate = CartesianCoordinate(fx * 4.0, fy * 4.0, fz * 4.0),
        occupancy = 1.0,
        cellOffset = offset,
        isShell = shell,
        isBoundaryImage = boundary,
    )

    private val atoms = listOf(
        atom(1, "O1", "O", 0.2, 0.2, 0.2),      // 水分子 1
        atom(2, "H1", "H", 0.3, 0.2, 0.2),
        atom(3, "H2", "H", 0.2, 0.3, 0.2),
        atom(4, "O2", "O", 0.7, 0.7, 0.7),      // 水分子 2
        atom(5, "H3", "H", 0.8, 0.7, 0.7),
        atom(6, "H4", "H", 0.7, 0.8, 0.7),
        atom(7, "Cl1", "Cl", 0.05, 0.5, 0.5),   // 氯分子(跨晶胞)
        atom(8, "Cl2", "Cl", 0.95, 0.5, 0.5),
        atom(9, "O1", "O", 0.2, 1.2, 0.2, offset = Int3(0, 1, 0), shell = true),     // 水1 的 +y 外部映像
        atom(10, "Cl2", "Cl", 1.95, 0.5, 0.5, offset = Int3(1, 0, 0), shell = true), // Cl2 的 +x 外部映像
        atom(11, "X1", "C", 0.4, 0.4, 0.4),                                          // 游离原子(非分子)
        atom(12, "X1", "C", 0.4, 1.4, 0.4, offset = Int3(0, 1, 0), shell = true),    // 游离原子外部映像
        atom(13, "O1", "O", 0.2, 0.2, 1.2, offset = Int3(0, 0, 1), shell = true, boundary = true), // 边界映像
    )

    private fun bond(a: Long, b: Long, siteA: String, siteB: String, offsetB: Int3 = Int3(0, 0, 0)) = Bond(
        atomA = a,
        atomB = b,
        distance = 1.0,
        // extend 默认 false:"扩展到晶胞外"未开启,跨胞键仅靠分子展开显示。
        rule = BondRule(siteA, siteB, 0.1, 4.0),
        offsetB = offsetB,
    )

    private val bonds = listOf(
        bond(1, 2, "O1", "H1"),
        bond(1, 3, "O1", "H2"),
        bond(4, 5, "O2", "H3"),
        bond(4, 6, "O2", "H4"),
        // 氯分子横跨晶胞:Cl1(胞内)与 Cl2 在 (1,0,0) 层的映像(10)成键。
        bond(7, 10, "Cl1", "Cl2", offsetB = Int3(1, 0, 0)),
        // 同原子周期自像键:Cl2 与其 +x 外部映像 —— 非分子内键,分子展开下不显示。
        bond(8, 10, "Cl2", "Cl2", offsetB = Int3(1, 0, 0)),
    )

    private val molecules = listOf(
        Molecule(
            "H2O",
            listOf(
                MoleculeAtom(1, "O1", Species("O"), CartesianCoordinate(0.8, 0.8, 0.8)),
                MoleculeAtom(2, "H1", Species("H"), CartesianCoordinate(1.2, 0.8, 0.8)),
                MoleculeAtom(3, "H2", Species("H"), CartesianCoordinate(0.8, 1.2, 0.8)),
            ),
            listOf(MoleculeBond(1, 2), MoleculeBond(1, 3)),
        ),
        Molecule(
            "H2O",
            listOf(
                MoleculeAtom(4, "O2", Species("O"), CartesianCoordinate(2.8, 2.8, 2.8)),
                MoleculeAtom(5, "H3", Species("H"), CartesianCoordinate(3.2, 2.8, 2.8)),
                MoleculeAtom(6, "H4", Species("H"), CartesianCoordinate(2.8, 3.2, 2.8)),
            ),
            listOf(MoleculeBond(4, 5), MoleculeBond(4, 6)),
        ),
        Molecule(
            "Cl2",
            listOf(
                MoleculeAtom(7, "Cl1", Species("Cl"), CartesianCoordinate(0.2, 2.0, 2.0)),
                // 跨胞:Cl2 在 (1,0,0) 层(与场景原子 10 位置一致)。
                MoleculeAtom(8, "Cl2", Species("Cl"), CartesianCoordinate(7.8, 2.0, 2.0)),
            ),
            listOf(MoleculeBond(7, 8)),
        ),
    )

    private fun build(
        options: SceneBuildOptions = SceneBuildOptions(),
        bondList: List<Bond> = bonds,
        atomList: List<AtomImage> = atoms,
        hbondList: List<HydrogenBond> = emptyList(),
    ): RenderScene =
        CrystalSceneBuilder().build(
            structure,
            BondNetwork(atomList, bondList, hbondList, structure = structure, expansion = Expansion()),
            options,
        )

    private fun RenderScene.atomInstance(id: Long) = atoms.single { it.atom.id == id }
    private fun RenderScene.bondBetween(a: Long, b: Long) = bonds.single {
        (it.bond.atomA == a && it.bond.atomB == b) || (it.bond.atomA == b && it.bond.atomB == a)
    }

    @Test
    fun withoutMoleculeExtendExternalShellAtomsStayHidden() {
        val scene = build()
        // 原胞原子可见(现状)。
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 11L).forEach {
            assertTrue(scene.atomInstance(it).visible, "primary atom $it should be visible")
        }
        // 外部壳层原子不可见(现状:无 extend 规则指向)。
        listOf(9L, 10L, 12L).forEach {
            assertFalse(scene.atomInstance(it).visible, "external shell atom $it should stay hidden")
        }
        // 边界映像按原逻辑可见。
        assertTrue(scene.atomInstance(13).visible)
        // 跨胞自像键被 isSameAtomPeriodicImage 挡住。
        assertFalse(scene.bondBetween(8, 10).visible)
    }

    @Test
    fun moleculeExtendShowsWholeMoleculesAcrossCell() {
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = molecules))
        // 单胞内分子原子(原胞 + 边界)可见;氯分子跨胞部分(10,Cl2 在 (1,0,0) 层)可见。
        // 8(Cl2 的包裹原胞代表 frac 0.95)是跨胞正侧原子的包裹副本 —— 物理位置在
        // x=1.95(正侧跨界),由壳层 10 承载,不渲染孤立球(避免分子被劈开)。
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 11L, 13L).forEach {
            assertTrue(scene.atomInstance(it).visible, "molecule atom $it should be visible")
        }
        // 相邻分子的外部映像不显示(9 = O1 的 +y 映像,水分子不跨胞;12 = 游离 X1 的
        // +y 映像,单原子不跨胞)。10 = Cl2 的物理位置(frac 1.95,分子跨 x=1 的正侧
        // 延伸)→ 补全显示,氯分子完整跨胞呈现。8 = Cl2 包裹 primary,其位置 (3.8,2,2)
        // 恰是 Cl2 分子 t=(-1,0,0) 映像的原子位置(全方向规则)→ 挂在映像上显示。
        listOf(9L, 12L).forEach {
            assertFalse(scene.atomInstance(it).visible, "foreign image $it should stay hidden")
        }
        listOf(8L, 10L).forEach {
            assertTrue(scene.atomInstance(it).visible, "molecule atom $it should be visible")
        }
        assertTrue(scene.atomInstance(10).visible, "Cl2's cross-cell part at frac 1.95 completes the molecule")
        // 跨胞氯分子键 7-10 可见;同原子自像键 8-10 隐藏。
        assertTrue(scene.bondBetween(7, 10).visible, "cross-cell Cl2 molecule bond should be visible")
        assertFalse(scene.bondBetween(8, 10).visible, "same-atom self-image bond is not a molecule bond")
    }

    @Test
    fun hidingOneCellAtomHidesItsImagesButKeepsTheRestOfTheMolecule() {
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = molecules, hiddenSiteIds = setOf("O1")))
        // O1 及其外部映像隐藏;分子未全隐藏 → H1/H2 仍显示。
        assertFalse(scene.atomInstance(1).visible)
        assertFalse(scene.atomInstance(9).visible)
        assertTrue(scene.atomInstance(2).visible)
        assertTrue(scene.atomInstance(3).visible)
    }

    @Test
    fun hidingAllCellAtomsOfAMoleculeHidesItsExternalImages() {
        val scene = build(
            SceneBuildOptions(
                moleculeExtend = true,
                molecules = molecules,
                hiddenSiteIds = setOf("O1", "H1", "H2"),
            ),
        )
        // 水分子 1 整分子隐藏(原胞原子 + 外部映像)。
        listOf(1L, 2L, 3L, 9L).forEach {
            assertFalse(scene.atomInstance(it).visible, "hidden water-1 atom $it")
        }
        // 水分子 2 与氯分子不受影响(氯分子包裹 primary 8 的球挂在 t=(-1,0,0) 映像上)。
        listOf(4L, 5L, 6L).forEach { assertTrue(scene.atomInstance(it).visible, "water-2 atom $it") }
        listOf(7L, 8L, 10L).forEach { assertTrue(scene.atomInstance(it).visible, "Cl2 atom $it") }
    }

    @Test
    fun hidingAllCellAtomsOfAMoleculeHidesItsCrossCellBond() {
        val scene = build(
            SceneBuildOptions(
                moleculeExtend = true,
                molecules = molecules,
                hiddenSiteIds = setOf("Cl1", "Cl2"),
            ),
        )
        // 氯分子整分子隐藏:原胞原子、外部映像、跨胞键一并隐藏(联动)。
        listOf(7L, 8L, 10L).forEach {
            assertFalse(scene.atomInstance(it).visible, "hidden Cl2 atom $it")
        }
        assertFalse(scene.bondBetween(8, 10).visible, "cross-cell bond of a fully-hidden molecule")
        assertFalse(scene.bondBetween(7, 10).visible)
    }

    @Test
    fun moleculeExtendCompletesShellShellBonds() {
        // 模拟白磷 P4 横跨晶胞:P1/P4 在原胞,P2'/P3' 在 (0,0,1)(外部壳层)。
        // BondDetector 只从 primary/boundary 中心生成键,两端都是 shell 的 P2'-P3' 键
        // 不会生成 —— 分子展开时须由 MoleculeBond 补齐,每个显示的 P 保持完整配位。
        val pAtoms = listOf(
            atom(21, "P1", "P", 0.3, 0.5, 0.5),
            atom(22, "P2", "P", 0.3, 0.6, 0.5),
            atom(23, "P3", "P", 0.4, 0.6, 0.5),
            atom(24, "P4", "P", 0.4, 0.5, 0.5),
            atom(25, "P2", "P", 0.3, 0.6, 1.5, offset = Int3(0, 0, 1), shell = true),
            atom(26, "P3", "P", 0.4, 0.6, 1.5, offset = Int3(0, 0, 1), shell = true),
        )
        val pBonds = listOf(
            bond(21, 25, "P1", "P2"),
            bond(21, 26, "P1", "P3"),
            bond(24, 25, "P4", "P2"),
            bond(24, 26, "P4", "P3"),
            bond(21, 24, "P1", "P4"),
            bond(22, 23, "P2", "P3"),
        )
        // 缺口:P2'-P3'(25-26),两端都是外部壳层。
        val p4 = Molecule(
            "P4",
            listOf(
                MoleculeAtom(21, "P1", Species("P"), CartesianCoordinate(1.2, 2.0, 2.0)),
                MoleculeAtom(22, "P2", Species("P"), CartesianCoordinate(1.2, 2.4, 6.0)),
                MoleculeAtom(23, "P3", Species("P"), CartesianCoordinate(1.6, 2.4, 6.0)),
                MoleculeAtom(24, "P4", Species("P"), CartesianCoordinate(1.6, 2.0, 2.0)),
            ),
            listOf(
                MoleculeBond(21, 22), MoleculeBond(21, 23), MoleculeBond(21, 24),
                MoleculeBond(22, 23), MoleculeBond(22, 24), MoleculeBond(23, 24),
            ),
        )
        // 非分子展开:shell-shell 键不存在。
        val plain = build(bondList = pBonds, atomList = pAtoms)
        assertTrue(plain.bonds.none { it.bond.atomA == 25L && it.bond.atomB == 26L })
        // 分子展开:补齐的 shell-shell 键存在且可见。
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(p4)), pBonds, pAtoms)
        val completed = scene.bonds.firstOrNull {
            (it.bond.atomA == 25L && it.bond.atomB == 26L) || (it.bond.atomA == 26L && it.bond.atomB == 25L)
        }
        assertNotNull(completed, "shell-shell bond 25-26 should be completed by molecule-extend")
        assertTrue(completed.visible)
        // 25-26 只补齐一次(不重复);P2' 总配位 = 3 根键(21-25、24-25、25-26)。
        assertEquals(1, scene.bonds.count {
            (it.bond.atomA == 25L && it.bond.atomB == 26L) || (it.bond.atomA == 26L && it.bond.atomB == 25L)
        })
        assertEquals(3, scene.bonds.count { it.bond.atomA == 25L || it.bond.atomB == 25L })
    }

    @Test
    fun moleculeExtendCompletesShellShellBondsForEveryImage() {
        // 白磷 P4 分子在 +z 与 -z 两个方向的映像都有外部壳层原子:BondDetector 只生成
        // primary 相关的键,P2'-P3'(+z)与 P2''-P3''(-z)都需由分子展开补齐(任意映像)。
        val pAtoms = listOf(
            atom(21, "P1", "P", 0.3, 0.5, 0.5),
            atom(22, "P2", "P", 0.3, 0.6, 0.5),
            atom(23, "P3", "P", 0.4, 0.6, 0.5),
            atom(24, "P4", "P", 0.4, 0.5, 0.5),
            atom(25, "P2", "P", 0.3, 0.6, 1.5, offset = Int3(0, 0, 1), shell = true),
            atom(26, "P3", "P", 0.4, 0.6, 1.5, offset = Int3(0, 0, 1), shell = true),
            atom(27, "P2", "P", 0.3, 0.6, -0.5, offset = Int3(0, 0, -1), shell = true),
            atom(28, "P3", "P", 0.4, 0.6, -0.5, offset = Int3(0, 0, -1), shell = true),
        )
        val pBonds = listOf(
            bond(21, 25, "P1", "P2"), bond(21, 26, "P1", "P3"),
            bond(24, 25, "P4", "P2"), bond(24, 26, "P4", "P3"),
            bond(21, 27, "P1", "P2"), bond(21, 28, "P1", "P3"),
            bond(24, 27, "P4", "P2"), bond(24, 28, "P4", "P3"),
            bond(21, 24, "P1", "P4"), bond(22, 23, "P2", "P3"),
        )
        val p4 = Molecule(
            "P4",
            listOf(
                MoleculeAtom(21, "P1", Species("P"), CartesianCoordinate(1.2, 2.0, 2.0)),
                MoleculeAtom(22, "P2", Species("P"), CartesianCoordinate(1.2, 2.4, 6.0)),
                MoleculeAtom(23, "P3", Species("P"), CartesianCoordinate(1.6, 2.4, 6.0)),
                MoleculeAtom(24, "P4", Species("P"), CartesianCoordinate(1.6, 2.0, 2.0)),
            ),
            listOf(
                MoleculeBond(21, 22), MoleculeBond(21, 23), MoleculeBond(21, 24),
                MoleculeBond(22, 23), MoleculeBond(22, 24), MoleculeBond(23, 24),
            ),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(p4)), pBonds, pAtoms)
        // +z 映像:P2'(25)、P3'(26)是分子 P4 的物理位置 → 显示;25-26 键补齐。
        assertNotNull(
            scene.bonds.firstOrNull {
                (it.bond.atomA == 25L && it.bond.atomB == 26L) || (it.bond.atomA == 26L && it.bond.atomB == 25L)
            },
            "+z shell-shell bond 25-26 should be completed",
        )
        // -z 映像:P2''(27)、P3''(28)是相邻分子的映像(位置 ≠ 分子原子物理坐标)→ 不显示,
        // 也没有可见键(其 BondDetector 键 21-27/24-27 因端点不可见而隐藏)。
        assertFalse(scene.atomInstance(27).visible, "-z image 27 belongs to a neighbouring molecule")
        assertFalse(scene.atomInstance(28).visible, "-z image 28 belongs to a neighbouring molecule")
        assertTrue(scene.bonds.none { (it.bond.atomA == 27L || it.bond.atomB == 27L) && it.visible })
        // 显示的 P2'(25)配位完整:3 根键(21、24、26)。
        assertEquals(3, scene.bonds.count { it.bond.atomA == 25L || it.bond.atomB == 25L })
    }

    @Test
    fun moleculeExtendHbondFollowsEndpointVisibility() {
        // 分子间氢键 O1-H3:隐藏 H3 所在分子(水分子 2 全部原子)→ 氢键跟随端点隐藏。
        val hbond = Bond(1, 5, 2.4, BondRule("O1", "H3", 1.5, 3.0, BondRuleSource.AUTO, isHBond = true))
            .toHydrogenBond(atoms.associateBy { it.id })
        val scene = build(
            SceneBuildOptions(
                moleculeExtend = true,
                molecules = molecules,
                hiddenSiteIds = setOf("O2", "H3", "H4"),
            ),
            bonds,
            hbondList = listOf(hbond),
        )
        val h = scene.hbonds.single()
        assertFalse(h.visible, "hbond to a fully-hidden molecule should hide with its endpoint")
    }

    @Test
    fun moleculeExtendDoesNotExpandHbondConnectedNeighbour() {
        // 需求 2 守卫:分子展开只沿共价拓扑(toMolecules 不含氢键),氢键相连的分子
        // 不被展开。夹具 = 两个仅由氢键相连的分子:分子 A 在胞内(41、42),分子 B
        // 全部原子在显示区外(51、52,frac 1.6/1.7 壳层)。B 不是分子条目(氢键不进
        // 共价拓扑 → toMolecules 不产出 B),其原子按非分子壳层处理 → 显示区外隐藏;
        // 该氢键端点 B 无球 → 不可见。
        val bAtoms = listOf(
            atom(41, "A1", "A", 0.2, 0.2, 0.2),              // (0.8,0.8,0.8) 分子 A 原胞原子
            atom(42, "A2", "A", 0.3, 0.2, 0.2),              // (1.2,0.8,0.8)
            atom(51, "B1", "B", 1.6, 0.2, 0.2, shell = true), // (6.4,0.8,0.8) 显示区外壳层
            atom(52, "B2", "B", 1.7, 0.2, 0.2, shell = true), // (6.8,0.8,0.8)
        )
        val hbond = Bond(41, 51, 2.4, BondRule("A1", "B1", 1.5, 3.0, BondRuleSource.AUTO, isHBond = true))
            .toHydrogenBond(bAtoms.associateBy { it.id })
        val molA = Molecule(
            "A2",
            listOf(
                MoleculeAtom(41, "A1", Species("A"), CartesianCoordinate(0.8, 0.8, 0.8), siteId = "A1"),
                MoleculeAtom(42, "A2", Species("A"), CartesianCoordinate(1.2, 0.8, 0.8), siteId = "A2"),
            ),
            listOf(MoleculeBond(41, 42)),
        )
        val scene = build(
            SceneBuildOptions(moleculeExtend = true, molecules = listOf(molA)),
            listOf(bond(41, 42, "A1", "A2")),
            bAtoms,
            hbondList = listOf(hbond),
        )
        // B 的原子无可见球(非分子 + 显示区外)。
        listOf(51L, 52L).forEach {
            assertFalse(scene.atomInstance(it).visible, "non-molecule atom $it outside the cell stays hidden")
        }
        // A 的映像全被场景承载 → 无动态原子(B 未被展开)。
        assertTrue(scene.atoms.none { it.id.startsWith("molatom:") }, "hbond must not trigger molecule expansion")
        // 氢键端点 51 无球 → 不可见。
        val h = scene.hbonds.single()
        assertFalse(h.visible, "hbond with an endpoint without visible sphere stays hidden")
    }

    @Test
    fun moleculeExtendShowsHbondWhenBothEndpointsVisible() {
        // 分子间氢键(O1 与另一分子的 H3):两端分子都显示 → 两端球可见 → 氢键渲染
        // (决策 2:两端球都可见才显示;分子展开不触发也不切断分子间氢键)。
        val hbond = Bond(1, 5, 2.4, BondRule("O1", "H3", 1.5, 3.0, BondRuleSource.AUTO, isHBond = true))
            .toHydrogenBond(atoms.associateBy { it.id })
        val scene = build(
            SceneBuildOptions(moleculeExtend = true, molecules = molecules),
            bonds,
            hbondList = listOf(hbond),
        )
        val h = scene.hbonds.single()
        assertTrue(h.visible, "hbond between two visible molecules should be visible")
        // 共价键断言不变:水分子内部键照常显示。
        assertTrue(scene.bondBetween(1, 2).visible)
    }

    @Test
    fun moleculeExtendKeepsCrossMoleculeCovalentBondsHidden() {
        // 普通(非氢键)跨分子键:两端属不同分子 → 分子展开下不显示。
        val crossBond = bond(1, 5, "O1", "H3")
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = molecules), bonds + crossBond)
        assertFalse(scene.bondBetween(1, 5).visible, "cross-molecule covalent bond should stay hidden")
    }

    @Test
    fun moleculeExtendShowsAllIntersectingImagesBothSides() {
        // 分子链 A(0.9)→B(1.1)→C(1.3)→D(1.5) 跨 x 边界后继续延伸(尿素式),键长 0.8、
        // lattice a=4(拓扑 A@3.6、B@4.4、C@5.2、D@6.0)。包裹 primary 必须与拓扑自洽:
        // A@frac0.9(3.6)、B@frac0.1(0.4)、C@frac0.3(1.2)、D@frac0.5(2.0)。
        // 显示范围 = 单胞 [0,1]³:新规则下任一方向与显示区相交的映像都完整显示 ——
        // M+0([3.6,6.0])与 M+(-1,0,0)([-0.4,2.0])两个映像都完整(全方向,含负侧)。
        val atoms = listOf(
            atom(31, "A", "A", 0.9, 0.5, 0.5),   // (3.6,2,2) A 包裹 primary
            atom(32, "B", "B", 0.1, 0.5, 0.5),   // (0.4,2,2) B 包裹 primary
            atom(33, "C", "C", 0.3, 0.5, 0.5),   // (1.2,2,2) C 包裹 primary
            atom(34, "D", "D", 0.5, 0.5, 0.5),   // (2.0,2,2) D 包裹 primary
        )
        // 场景内分子键:M+(-1) 映像的 B-C(0.4-1.2)、C-D(1.2-2.0);其余链键由动态原子
        // 补齐通道发射(molbond)。
        val pBonds = listOf(bond(32, 33, "B", "C"), bond(33, 34, "C", "D"))
        val chain = Molecule(
            "ABCD",
            listOf(
                MoleculeAtom(31, "A", Species("A"), CartesianCoordinate(3.6, 2.0, 2.0), siteId = "A"),
                MoleculeAtom(32, "B", Species("B"), CartesianCoordinate(4.4, 2.0, 2.0), siteId = "B"),
                MoleculeAtom(33, "C", Species("C"), CartesianCoordinate(5.2, 2.0, 2.0), siteId = "C"),
                MoleculeAtom(34, "D", Species("D"), CartesianCoordinate(6.0, 2.0, 2.0), siteId = "D"),
            ),
            listOf(MoleculeBond(31, 32), MoleculeBond(32, 33), MoleculeBond(33, 34)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(chain)), pBonds, atoms)
        // 可见球位置:M+0(3.6、4.4、5.2、6.0)与 M+(-1,0,0)(-0.4、0.4、1.2、2.0)。
        val visibleX = scene.atoms.filter { it.visible }.map { it.atom.cartesianCoordinate.x }.sorted()
        visibleX.zip(listOf(-0.4, 0.4, 1.2, 2.0, 3.6, 4.4, 5.2, 6.0)).forEach { (actual, expected) ->
            assertEquals(expected, actual, 1e-9)
        }
        // 全部 4 个包裹 primary 可见:各自位置都被某相交映像覆盖(不再隐藏包裹副本)。
        listOf(31L, 32L, 33L, 34L).forEach {
            assertTrue(scene.atomInstance(it).visible, "wrapped primary $it should be visible")
        }
        // 动态原子恰好补 4.4、5.2、6.0、-0.4 四处(场景无原子的映像位置,显示带内)。
        val dynX = scene.atoms.filter { it.id.startsWith("molatom:") }.map { it.atom.cartesianCoordinate.x }.sorted()
        dynX.zip(listOf(-0.4, 4.4, 5.2, 6.0)).forEach { (actual, expected) ->
            assertEquals(expected, actual, 1e-9)
        }
        // 两映像各自 3 根键可见,键长均 0.8(M+0:31-动态B、动态B-动态C、动态C-动态D;
        // M+(-1):动态A-32、32-33、33-34)。
        val visibleBonds = scene.bonds.filter { it.visible }
        assertEquals(6, visibleBonds.size)
        assertTrue(visibleBonds.all { kotlin.math.abs(distance(it.start, it.end) - 0.8) < 1e-9 })
    }

    @Test
    fun moleculeExtendCompletesImagesInSupercell() {
        // 显示用超胞(2×1×1):primary 带 cellOffset=(1,0,0)(物理位置 frac 含偏移,
        // displayEx 推断为 (2,1,1))。分子链 A-B-C 跨 x 边界(拓扑 frac -0.2/0.5/1.2,
        // 键长 2.8,lattice a=4):与 [0,2]×[0,1]×[0,1] 相交的全部映像 t ∈ {-1,0,1,2}
        // (t 含 2 与负值)都必须完整 —— 显示带 [-1,3](frac)内的映像原子位置全有可见球。
        val atoms = listOf(
            atom(40, "A", "A", 0.8, 0.5, 0.5),                            // (3.2,2,2) A 的 rep primary
            atom(41, "A", "A", 1.8, 0.5, 0.5, offset = Int3(1, 0, 0)),    // (7.2,2,2) A 的第 2 晶胞 primary
            atom(42, "B", "B", 0.5, 0.5, 0.5),                            // (2.0,2,2) B 的 rep primary
            atom(43, "B", "B", 1.5, 0.5, 0.5, offset = Int3(1, 0, 0)),    // (6.0,2,2)
            atom(44, "C", "C", 0.2, 0.5, 0.5),                            // (0.8,2,2) C 的 rep primary
            atom(45, "C", "C", 1.2, 0.5, 0.5, offset = Int3(1, 0, 0)),    // (4.8,2,2)
        )
        val chain = Molecule(
            "ABC",
            listOf(
                MoleculeAtom(40, "A", Species("A"), CartesianCoordinate(-0.8, 2.0, 2.0), siteId = "A"),
                MoleculeAtom(42, "B", Species("B"), CartesianCoordinate(2.0, 2.0, 2.0), siteId = "B"),
                MoleculeAtom(44, "C", Species("C"), CartesianCoordinate(4.8, 2.0, 2.0), siteId = "C"),
            ),
            listOf(MoleculeBond(40, 42), MoleculeBond(42, 44)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(chain)), emptyList(), atoms)
        // 6 个场景 primary 全可见。
        listOf(40L, 41L, 42L, 43L, 44L, 45L).forEach {
            assertTrue(scene.atomInstance(it).visible, "supercell primary $it should be visible")
        }
        // 全部 4 个相交映像(含负值 t=-1 与 t=2)的带内原子位置都有可见球。
        val imagePositions = listOf(
            listOf(-4.8, -2.0, 0.8),    // t=-1
            listOf(-0.8, 2.0, 4.8),     // t=0
            listOf(3.2, 6.0, 8.8),      // t=1
            listOf(7.2, 10.0, 12.8),    // t=2
        )
        val visibleX = scene.atoms.filter { it.visible }.map { it.atom.cartesianCoordinate.x }
        for (x in imagePositions.flatten()) {
            val f = x / 4.0
            if (f < -1.0 - 1e-6 || f > 3.0 + 1e-6) continue // 显示带 [-1,3](frac)外,已知限制
            assertTrue(visibleX.any { kotlin.math.abs(it - x) < 0.05 }, "image atom at x=$x should have a visible sphere")
        }
        // 动态原子恰好补 4 个带内无场景位置(-2.0、-0.8、8.8、10.0;带外位置也发射
        // 动态原子但不渲染球)。
        val dynX = scene.atoms.filter { it.id.startsWith("molatom:") && it.visible }
            .map { it.atom.cartesianCoordinate.x }.sorted()
        dynX.zip(listOf(-2.0, -0.8, 8.8, 10.0)).forEach { (actual, expected) ->
            assertEquals(expected, actual, 1e-9)
        }
    }

    @Test
    fun moleculeExtendShowsAtomsOnCellBoundaryFaces() {
        // [0,1] 闭区间:分子原子物理位置恰在 z=1 面(frac 1.0)的映像(场景边界原子 32)
        // 必须显示——边界属于单胞显示范围,不是 [0,1)。分子 X-W:X 原胞代表 frac 0.0
        // (31),键跨 x=1 边界使 X 的物理位置 frac 1.0;W frac 0.75(34)。
        val atoms = listOf(
            atom(31, "X", "X", 0.5, 0.5, 0.0),                            // (2,2,0) 原胞代表
            atom(32, "X", "X", 0.5, 0.5, 1.0, offset = Int3(0, 0, 1), shell = true, boundary = true),  // (2,2,4) 边界
            atom(34, "W", "W", 0.5, 0.5, 0.75),                           // (2,2,3)
        )
        val pBonds = listOf(bond(32, 34, "X", "W", offsetB = Int3(0, 0, 1)))
        val mol = Molecule(
            "XW",
            listOf(
                MoleculeAtom(31, "X", Species("X"), CartesianCoordinate(2.0, 2.0, 4.0), siteId = "X"),
                MoleculeAtom(34, "W", Species("W"), CartesianCoordinate(2.0, 2.0, 3.0), siteId = "W"),
            ),
            listOf(MoleculeBond(31, 34)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(mol)), pBonds, atoms)
        // 31(X@frac 0.0)是原胞 primary → 无条件显示;32(frac 1.0 边界)是分子 X 的
        // 物理位置(frac z=1.0)→ 可见(闭区间 [0,1] 含 z=1)。两者是同一原子的两个
        // 映像(0.0 与 1.0),分子以原胞 + 边界映像完整呈现。
        assertTrue(scene.atomInstance(31).visible)
        assertTrue(scene.atomInstance(32).visible, "atom on z=1 face (frac 1.0) must be shown (closed [0,1])")
        assertTrue(scene.atomInstance(34).visible)
        assertTrue(scene.bondBetween(32, 34).visible, "in-molecule bond crossing the z=1 face")
        // 无动态原子:X@(2,2,4) 由场景边界原子 32 承载。
        assertTrue(scene.atoms.none { it.id.startsWith("molatom:") })
    }

    @Test
    fun moleculeExtendClosedIntervalShowsTopFaceAtoms() {
        // [0,1] 闭区间:单胞顶面(z=1,frac 1.0)的原子属于展开范围,即使不属于任何分子
        // (siteId Q/R 不在分子列表);frac=1.5(超出 [0,1])的非分子原子不显示。
        val atoms = listOf(
            atom(31, "X", "X", 0.5, 0.5, 0.25),                            // (2,2,1)
            atom(32, "Y", "Y", 0.5, 0.5, 0.75),                            // (2,2,3)
            atom(35, "Q", "Q", 0.5, 0.5, 1.0, offset = Int3(0, 0, 1), shell = true),  // (2,2,4) 顶面 frac=1.0
            atom(36, "R", "R", 0.5, 0.5, 1.5, offset = Int3(0, 0, 1), shell = true),  // (2,2,6) frac=1.5
        )
        val pBonds = listOf(bond(31, 32, "X", "Y"))
        val mol = Molecule(
            "XY",
            listOf(
                MoleculeAtom(31, "X", Species("X"), CartesianCoordinate(2.0, 2.0, 1.0), siteId = "X"),
                MoleculeAtom(32, "Y", Species("Y"), CartesianCoordinate(2.0, 2.0, 3.0), siteId = "Y"),
            ),
            listOf(MoleculeBond(31, 32)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(mol)), pBonds, atoms)
        assertTrue(scene.atomInstance(31).visible)
        assertTrue(scene.atomInstance(32).visible)
        assertTrue(scene.atomInstance(35).visible, "atom on z=1 top face (frac 1.0) is inside the closed [0,1] range")
        assertFalse(scene.atomInstance(36).visible, "frac 1.5 lies outside [0,1] and is not a molecule atom")
    }

    @Test
    fun moleculeExtendSupercellClosedInterval() {
        // 显示用超胞(2×1×1):显示范围 [0,2] 闭区间。primary 覆盖 [0,2)³(第 2 晶胞的
        // X 是 primary,isShell=false → 无条件显示);超胞顶面 frac=2.0 的边界原子属于
        // 闭区间 [0,2] → 显示(displayEx 从 primary cellOffset 推断为 (2,1,1))。
        val atoms = listOf(
            atom(31, "X", "X", 0.5, 0.5, 0.25),                                  // (2,2,1) 第 1 晶胞
            atom(32, "Y", "Y", 0.5, 0.5, 0.75),                                  // (2,2,3)
            atom(33, "X", "X", 1.5, 0.5, 0.25, offset = Int3(1, 0, 0)),           // (6,2,1) 第 2 晶胞 primary
            atom(34, "Q", "Q", 2.0, 0.5, 1.0, offset = Int3(2, 0, 1), shell = true, boundary = true),  // (8,2,4) 超胞顶面 frac x=2.0
            atom(35, "R", "R", 2.5, 0.5, 0.5, offset = Int3(2, 0, 0), shell = true),  // (10,2,2) frac 2.5 超出
        )
        val pBonds = listOf(bond(31, 32, "X", "Y"))
        val mol = Molecule(
            "XY",
            listOf(
                MoleculeAtom(31, "X", Species("X"), CartesianCoordinate(2.0, 2.0, 1.0), siteId = "X"),
                MoleculeAtom(32, "Y", Species("Y"), CartesianCoordinate(2.0, 2.0, 3.0), siteId = "Y"),
            ),
            listOf(MoleculeBond(31, 32)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(mol)), pBonds, atoms)
        assertTrue(scene.atomInstance(31).visible)
        assertTrue(scene.atomInstance(32).visible)
        assertTrue(scene.atomInstance(33).visible, "second-cell primary is displayed (supercell)")
        assertTrue(scene.atomInstance(34).visible, "supercell top face frac x=2.0 is inside the closed [0,2] range")
        assertFalse(scene.atomInstance(35).visible, "frac 2.5 lies outside the supercell [0,2] range and is not a molecule atom")
    }

    @Test
    fun moleculeExtendShowsOnlyInCellMoleculeExtension() {
        // 显示范围 = 单胞 0-1a:只显示单胞内分子(t=0 物理位置,含跨胞延伸),不显示任何
        // 相邻晶胞映像。分子 A 的 Z2 跨 z 边界(物理 z=1.03 → 场景 43 显示);分子 B 的
        // Y2 跨 z 负边界(物理 z=-0.25 → 动态原子);t=(0,0,1) 映像(Y1@z=5)不显示。
        val atoms = listOf(
            atom(41, "Z1", "Z", 0.5, 0.5, 0.97),                  // (2,2,3.88)
            atom(42, "Z2", "Z", 0.5, 0.5, 0.03),                  // (2,2,0.12) 原胞代表
            atom(43, "Z2", "Z", 0.5, 0.5, 1.03, offset = Int3(0, 0, 1), shell = true),  // (2,2,4.12)
            atom(51, "Y1", "Y", 0.5, 0.5, 0.25),                  // (2,2,1)
            atom(52, "Y2", "Y", 0.5, 0.5, 0.75),                  // (2,2,3)
        )
        val pBonds = listOf(
            bond(41, 42, "Z1", "Z2"),       // 周期键(Z2 原胞代表,距离 0.24)
            bond(41, 43, "Z1", "Z2", offsetB = Int3(0, 0, 1)),   // 分子 t=0 映像键(Z2@1.03)
            bond(51, 52, "Y1", "Y2"),
        )
        val molA = Molecule(
            "Z2",
            listOf(
                MoleculeAtom(41, "Z1", Species("Z"), CartesianCoordinate(2.0, 2.0, 3.88), siteId = "Z1"),
                MoleculeAtom(42, "Z2", Species("Z"), CartesianCoordinate(2.0, 2.0, 4.12), siteId = "Z2"),
            ),
            listOf(MoleculeBond(41, 42)),
        )
        // Y2 物理位置在 z=-0.25(跨胞负向);其原胞代表 52 在 z=0.75。
        val molB = Molecule(
            "Y2",
            listOf(
                MoleculeAtom(51, "Y1", Species("Y"), CartesianCoordinate(2.0, 2.0, 1.0), siteId = "Y1"),
                MoleculeAtom(52, "Y2", Species("Y"), CartesianCoordinate(2.0, 2.0, -1.0), siteId = "Y2"),
            ),
            listOf(MoleculeBond(51, 52)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(molA, molB)), pBonds, atoms)
        // 全部原胞 primary 可见:A 的 42(Z2 包裹 primary,物理 z=4.12→frac 1.03)位置
        // (2,2,0.12) 恰是分子 A 的 t=(0,0,-1) 映像中 Z2 的位置(全方向规则)→ 挂在
        // 映像上显示;52(Y2 包裹 primary,物理 z=-1.0)由 t=0 映像承载 → 显示。
        listOf(41L, 42L, 43L, 51L, 52L).forEach {
            assertTrue(scene.atomInstance(it).visible, "molecule atom $it should be visible")
        }
        assertTrue(scene.bondBetween(41, 43).visible, "in-image Z1-Z2 bond")
        assertTrue(scene.bondBetween(51, 52).visible)
        // 动态原子:t=0 映像的 Y2@(2,2,-1)(跨 z=0 近侧延伸)、t=1 映像的 Y1@(2,2,6)
        // (Y2'@0.75 落在晶胞内,映像 t=1 与显示范围相交 → 完整补全),以及分子 A 的
        // t=(0,0,-1) 映像 Z1@(2,2,-0.12)(场景无该位置原子)。
        val dynY = scene.atoms.filter { it.id.startsWith("molatom:") }.map { it.atom.cartesianCoordinate.z }.sorted()
        dynY.zip(listOf(-1.0, -0.12, 5.0)).forEach { (actual, expected) ->
            assertEquals(expected, actual, 1e-9)
        }
    }

    @Test
    fun moleculeExtendCreatesDynamicAtomsBeyondMaterializedShell() {
        // 分子原子物理位置超出 BondDetector 的 ±1 层壳层(如尿素分子跨两个晶胞,末端
        // 在 (2,0,0) 层)→ 场景无该原子 → 动态创建,并补齐其分子内键。
        val atoms = listOf(
            atom(31, "X", "X", 0.1, 0.5, 0.5),   // 原胞 X(primary (1,5,5))
            atom(32, "Y", "Y", 0.2, 0.5, 0.5),   // 原胞 Y(primary (2,5,5),属于相邻分子)
        )
        // 分子:X 在原胞,Y 的物理位置在 (2,0,0) 层((20.8,2,2))→ 无场景原子 → 动态创建。
        // 注意测试 lattice a=4,helper cartesian = frac×4:31=(0.4,2,2), 32=(0.8,2,2)。
        val molecule = Molecule(
            "XY",
            listOf(
                MoleculeAtom(31, "X", Species("X"), CartesianCoordinate(0.4, 2.0, 2.0), siteId = "X"),
                MoleculeAtom(32, "Y", Species("Y"), CartesianCoordinate(20.8, 2.0, 2.0), siteId = "Y"),
            ),
            listOf(MoleculeBond(31, 32)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(molecule)), emptyList(), atoms)
        // 动态原子被创建:Y 在 (20.8,2,2)(超出 ±1 层)无场景原子 —— 其坐标作跨胞键端点;
        // 全方向规则下分子 XY 还有 4 个负侧相交映像(t=-5..-2 的 X/Y 落在显示带内),
        // 同样由动态原子承载(其中 X@-3.6 在带内 → 渲染球)。
        val dyns = scene.atoms.filter { it.id.startsWith("molatom:") }
        assertTrue(dyns.isNotEmpty(), "molecule atoms beyond the materialized shell should be created dynamically")
        // 球体不渲染:Y@20.8 的包裹 primary(32@0.8,同 rep 差整数晶胞)已承载该原子在晶胞内的球,
        // 动态坐标仅作键端点(消除重复球)。
        val dynY = dyns.first { it.atom.siteId == "Y" && it.atom.cartesianCoordinate.x > 19.0 }
        assertFalse(dynY.visible, "dynamic duplicate sphere hidden when the wrapped primary carries the atom")
        assertEquals(20.8, dynY.atom.cartesianCoordinate.x, 1e-9)
        // 负侧映像 X@-3.6 在显示带内 → 球可见。
        assertTrue(dyns.any { it.atom.siteId == "X" && it.visible }, "near-side image X@-3.6 renders a sphere")
        // 原胞原子照常显示(31 是分子 XY 的 primary;32 是 Y 的包裹 primary,其位置
        // (0.8,2,2) 恰是 Y 的 t=-5 映像 → 挂在该映像上显示)。
        assertTrue(scene.atomInstance(31).visible)
        assertTrue(scene.atomInstance(32).visible, "wrapped copy of far-cell Y sits on the t=-5 image")
        // 动态原子与 X 的分子键补齐(键长 = |(0.4,2,2)-(20.8,2,2)| = 20.4;补齐块
        // dynamicAtomsByRep 是 HashMap,发射顺序不定,按真实键长查找)。
        val molBond = scene.bonds.firstOrNull {
            it.id.startsWith("molbond:") && kotlin.math.abs(distance(it.start, it.end) - 20.4) < 1e-9
        }
        assertNotNull(molBond, "dynamic atom's molecule bond should be completed")
        assertTrue(molBond.visible)
    }

    @Test
    fun moleculeExtendCompletesWrappedPrimaryInternalBond() {
        // 角上分子(如 C60 的负八分体)物理位置在负侧 frac(-0.05/-0.15),锚定后显示位置
        // frac 0.95/0.85 —— 场景只有两个包裹 primary(41、42)+ 与胞内邻居 C 的跨胞网键
        // (44-45,45 为近侧壳层,承载位置 (-0.2,2,2) 与分子位置不符 → 隐藏)。
        // BondDetector 不会生成 41-42 键(两端都包裹时中心扫描距离膨胀);补齐通道须从
        // 可见 primary 41 出发,按分子拓扑邻居 42 补键(键长 |(3.8,2,2)-(3.4,2,2)|=0.4,
        // ±20% 窗口)。
        val atoms = listOf(
            atom(41, "D", "C", 0.95, 0.5, 0.5),                            // (3.8,2,2) 包裹 primary
            atom(42, "E", "C", 0.85, 0.5, 0.5),                            // (3.4,2,2) 包裹 primary
            atom(44, "C", "C", 0.05, 0.5, 0.5),                            // (0.2,2,2) 胞内邻居(非本分子)
            atom(45, "D", "C", -0.05, 0.5, 0.5, offset = Int3(-1, 0, 0), shell = true),  // (-0.2,2,2) D 近侧壳层
        )
        val pBonds = listOf(bond(44, 45, "C", "D", offsetB = Int3(-1, 0, 0)))
        val mol = Molecule(
            "DE",
            listOf(
                MoleculeAtom(41, "D", Species("C"), CartesianCoordinate(3.8, 2.0, 2.0), siteId = "D"),
                MoleculeAtom(42, "E", Species("C"), CartesianCoordinate(3.4, 2.0, 2.0), siteId = "E"),
            ),
            listOf(MoleculeBond(41, 42)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(mol)), pBonds, atoms)
        // 两个包裹 primary 是分子显示位置 → 可见;近侧壳层 45 是同一原子的另一表示 → 隐藏。
        assertTrue(scene.atomInstance(41).visible)
        assertTrue(scene.atomInstance(42).visible)
        assertTrue(scene.atomInstance(44).visible)
        assertFalse(scene.atomInstance(45).visible, "near-side shell 45 is the wrapped duplicate of molecule atom D")
        // 两端包裹的分子内键由补齐通道从 primary 41 补出。
        val completed = scene.bonds.firstOrNull {
            (it.bond.atomA == 41L && it.bond.atomB == 42L) || (it.bond.atomA == 42L && it.bond.atomB == 41L)
        }
        assertNotNull(completed, "wrapped-primary internal bond 41-42 should be completed")
        assertTrue(completed.visible)
        // 无动态原子:显示位置全被场景原子覆盖。
        assertTrue(scene.atoms.none { it.id.startsWith("molatom:") })
    }

    @Test
    fun moleculeExtendShowsBondsOfNonMoleculeAtoms() {
        // 超胞/游离原子场景:两个分子之外还有不属于任何分子的原子(如超胞里原胞之外
        // 的晶胞原子,或游离原子)。它们的 primary 恒显,分子模式也必须显示它们之间
        // 的真实键 —— 否则超胞画面里出现大量孤立原子("零星几个")。
        val atoms = listOf(
            atom(61, "M1", "C", 0.2, 0.2, 0.2),   // 分子 1 原胞原子
            atom(62, "M2", "C", 0.3, 0.2, 0.2),
            atom(71, "X", "X", 0.6, 0.6, 0.6),    // 游离(非分子)原子
            atom(72, "X", "X", 0.7, 0.6, 0.6),    // 游离原子成键伙伴
            atom(73, "X", "X", 1.6, 0.6, 0.6, offset = Int3(1, 0, 0)),  // 超胞第二胞 primary(非分子)
            atom(74, "X", "X", 1.7, 0.6, 0.6, offset = Int3(1, 0, 0)),
        )
        val pBonds = listOf(
            bond(61, 62, "M1", "M2"),
            bond(71, 72, "X", "X"),
            bond(73, 74, "X", "X"),
            bond(61, 73, "M1", "X", offsetB = Int3(1, 0, 0)),
        )
        val mol = Molecule(
            "M2",
            listOf(
                MoleculeAtom(61, "M1", Species("C"), CartesianCoordinate(0.8, 0.8, 0.8), siteId = "M1"),
                MoleculeAtom(62, "M2", Species("C"), CartesianCoordinate(1.2, 0.8, 0.8), siteId = "M2"),
            ),
            listOf(MoleculeBond(61, 62)),
        )
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = listOf(mol)), pBonds, atoms)
        // 分子内键可见。
        assertTrue(scene.bondBetween(61, 62).visible)
        // 非分子原子间的键(游离 71-72;超胞第二胞 73-74)在分子模式下仍显示。
        assertTrue(scene.bondBetween(71, 72).visible, "bond between non-molecule atoms stays visible")
        assertTrue(scene.bondBetween(73, 74).visible, "supercell independent atoms keep their bonds")
        // 分子原子与非分子原子的跨键:一端属分子、另一端非分子 → 非分子内键 → 隐藏。
        assertFalse(scene.bondBetween(61, 73).visible, "cross molecule/non-molecule bond stays hidden")
    }
}
