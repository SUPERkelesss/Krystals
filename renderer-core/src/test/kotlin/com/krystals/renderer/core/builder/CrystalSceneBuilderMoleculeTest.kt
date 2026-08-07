package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.Bond
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
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
    ): RenderScene =
        CrystalSceneBuilder().build(structure, BondNetwork(atomList, bondList, structure, Expansion()), options)

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
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 10L, 11L, 13L).forEach {
            assertTrue(scene.atomInstance(it).visible, "molecule atom $it should be visible")
        }
        // 相邻分子的外部映像不显示(精确归属:位置必须落在分子原子物理坐标上)。
        // 9 = O1 的 +y 映像(水分子不跨胞);12 = 游离 X1 的 +y 映像。
        listOf(9L, 12L).forEach {
            assertFalse(scene.atomInstance(it).visible, "foreign image $it should stay hidden")
        }
        // 跨胞氯分子键 7-10 可见;同原子自像键 8-10 非分子内键 → 不可见。
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
        // 水分子 2 与氯分子不受影响。
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
        val scene = build(
            SceneBuildOptions(
                moleculeExtend = true,
                molecules = molecules,
                hiddenSiteIds = setOf("O2", "H3", "H4"),
            ),
            bonds + hbond,
        )
        val h = scene.bonds.single { it.bond.rule.isHBond }
        assertFalse(h.visible, "hbond to a fully-hidden molecule should hide with its endpoint")
    }

    @Test
    fun moleculeExtendShowsInterMolecularHbondsButNotCrossMoleculeCovalent() {
        // 分子间氢键(O1 与另一分子的 H3):分子展开下仍显示(氢键不属于分子,不沿其展开)。
        val hbond = Bond(1, 5, 2.4, BondRule("O1", "H3", 1.5, 3.0, BondRuleSource.AUTO, isHBond = true))
        val scene = build(SceneBuildOptions(moleculeExtend = true, molecules = molecules), bonds + hbond)
        val h = scene.bonds.single { it.bond.rule.isHBond }
        assertTrue(h.visible, "inter-molecular hbond should be visible under molecule-extend")
        // 水分子内部键照常显示。
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
        // 动态原子被创建:Y 在 (20.8,2,2)(超出 ±1 层)无场景原子。
        val dyn = scene.atoms.firstOrNull { it.id.startsWith("molatom:") }
        assertNotNull(dyn, "molecule atom beyond the materialized shell should be created dynamically")
        assertTrue(dyn.visible)
        assertEquals(20.8, dyn.atom.cartesianCoordinate.x, 1e-9)
        assertEquals("Y", dyn.atom.siteId)
        // 原胞原子照常显示。
        assertTrue(scene.atomInstance(31).visible)
        assertTrue(scene.atomInstance(32).visible)
        // 动态原子与 X 的分子键补齐(键长 = |(0.4,2,2)-(20.8,2,2)| = 20.4)。
        val molBond = scene.bonds.firstOrNull { it.id.startsWith("molbond:") }
        assertNotNull(molBond, "dynamic atom's molecule bond should be completed")
        assertTrue(molBond.visible)
        assertEquals(20.4, distance(molBond.start, molBond.end), 1e-9)
    }
}
