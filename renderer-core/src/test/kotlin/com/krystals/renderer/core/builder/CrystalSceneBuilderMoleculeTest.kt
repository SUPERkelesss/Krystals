package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.Bond
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
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
import kotlin.test.assertFalse
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
        bond(7, 8, "Cl1", "Cl2"),
        // 同原子周期自像跨胞键:Cl2 与其 +x 外部映像(氯分子在晶胞外的另一半)。
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
                MoleculeAtom(8, "Cl2", Species("Cl"), CartesianCoordinate(3.8, 2.0, 2.0)),
            ),
            listOf(MoleculeBond(7, 8)),
        ),
    )

    private fun build(options: SceneBuildOptions = SceneBuildOptions()): RenderScene =
        CrystalSceneBuilder().build(structure, BondNetwork(atoms, bonds, structure, Expansion()), options)

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
        // 分子原子(含外部壳层映像)全部可见。
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L).forEach {
            assertTrue(scene.atomInstance(it).visible, "molecule atom $it should be visible")
        }
        // 游离(非分子)原胞原子仍可见,但其外部映像不可见。
        assertTrue(scene.atomInstance(11).visible)
        assertFalse(scene.atomInstance(12).visible, "non-molecule external atom should stay hidden")
        // 边界映像按原逻辑可见。
        assertTrue(scene.atomInstance(13).visible)
        // 跨胞键可见:同原子自像键(8-10)与原胞内键(7-8)都是氯分子的一部分。
        assertTrue(scene.bondBetween(8, 10).visible, "cross-cell Cl2 self-image bond should be visible")
        assertTrue(scene.bondBetween(7, 8).visible)
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
        assertFalse(scene.bondBetween(7, 8).visible)
    }
}
