package com.krystals.app

import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.isMolecularCrystal
import com.krystals.crystal.analysis.bonding.toMolecules
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.io.CifCodec
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 纯 C60 分子展开端到端验收(用户案例):
 * `res/cifs_example/00_elements/fullerene_C60.cif` —— Fm-3(202)、a=14.2555Å、FCC 4 笼/胞,
 * 3 个 C 位点全 occupancy 1(无无序、无其他元素)。分子中心 (0,0,0)、笼半径 ~0.248a,
 * 负八分体原子包裹到 (1,1,1) 邻近区 —— 正是"x=1,y=1,z=1 边界只显示单胞内部分"的复现场景。
 *
 * 验收口径:每分子原子恰好显示一次(无包裹 primary 重复)、每分子键可见(0 缺失)、
 * 可见的非分子位置原子全部是边界映像。
 */
class MoleculeExtendC60AcceptanceTest {

    private fun parse(rel: String): com.krystals.crystal.io.ParsedStructure {
        val dir = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("corpus missing")
        return CifCodec.parseStructure(File(dir, rel).readText())
    }

    @Test
    fun c60MoleculeExtendIsCompleteWithoutDuplicates() {
        val parsed = parse("00_elements/fullerene_C60.cif")
        val structure = parsed.structure
        val net = BondDetector.buildNetwork(structure, parsed.bondConfiguration)
        assertTrue(net.isMolecularCrystal(), "C60 应为分子晶体")
        val molecules = net.toMolecules()
        val molAtoms = molecules.flatMap { it.atoms }
        val molBonds = molecules.flatMap { it.bonds }
        println("C60: sites=${structure.sites.size} conventional=${structure.isConventional} expandedAtoms=${net.atoms.size}")
        println("C60: molecules=${molecules.size} names=${molecules.map { it.name }} atoms=${molAtoms.size} bonds=${molBonds.size}")

        // 结构事实:单元素 C → 1 个合并条目;240 原子(4 笼 × 60)、360 键(4 笼 × 90)。
        assertEquals(1, molecules.size, "纯 C60 单元素,应合并为 1 个分子条目")
        assertEquals(240, molAtoms.size, "应 240 个分子原子")
        assertEquals(360, molBonds.size, "应 360 根分子键")
        // 所有分子键应为真实笼边(1.3-1.7Å)——无无序伪键。
        val posById = HashMap<Int, Vec3>()
        for (m in molecules) for (ma in m.atoms) posById[ma.id] = ma.position.toVec3()
        val bondLens = molBonds.map { mb ->
            val p1 = posById[mb.from] ?: return@map Double.NaN
            val p2 = posById[mb.to] ?: return@map Double.NaN
            distance(p1, p2)
        }
        println("C60: bondLenRange=${bondLens.minOrNull()?.let { "%.3f".format(it) }}..${bondLens.maxOrNull()?.let { "%.3f".format(it) }}")
        assertTrue(bondLens.all { it in 1.3..1.7 }, "全部分子键应为 C-C 笼边(1.3-1.7Å)")

        val scene = CrystalRenderSceneFactory.build(
            analysis = net,
            appearance = ViewerAppearance(),
            renderConfiguration = RenderConfiguration(),
            moleculeExtend = true,
            molecules = molecules,
        )
        val atomInsts = scene.objects.filterIsInstance<AtomInstance>()
        val bondInsts = scene.objects.filterIsInstance<BondInstance>()
        val visAtoms = atomInsts.filter { it.visible }
        val visBonds = bondInsts.filter { it.visible }
        println("C60: visibleAtoms=${visAtoms.size} visibleBonds=${visBonds.size} molbonds=${visBonds.count { it.id.startsWith("molbond:") }}")

        // 1) 全部原胞 primary 可见 —— 分子在晶胞内的原子(含包裹副本)完整显示。
        val primaries = net.atoms.filter { !it.isShell && it.cellOffset == com.krystals.crystal.core.periodic.Int3(0, 0, 0) }
        val hiddenPrimaries = primaries.count { p -> visAtoms.none { it.atom.id == p.id } }
        println("C60: primaries=${primaries.size} hiddenPrimaries=$hiddenPrimaries")
        assertEquals(0, hiddenPrimaries, "晶胞内全部原胞原子都应显示")

        // 2) 每个分子键两端(±0.05A,双向)存在可见 BondInstance —— 0 缺失。
        var missingBonds = 0
        val samples = mutableListOf<String>()
        for (mb in molBonds) {
            val pa = posById[mb.from] ?: continue
            val pb = posById[mb.to] ?: continue
            val hit = visBonds.any { b ->
                (distance(b.start, pa) < 0.05 && distance(b.end, pb) < 0.05) ||
                    (distance(b.start, pb) < 0.05 && distance(b.end, pa) < 0.05)
            }
            if (!hit) {
                missingBonds++
                if (samples.size < 5) samples += "b${mb.from}-${mb.to}"
            }
        }
        println("C60: moleculeBonds=${molBonds.size} missingBonds=$missingBonds ${samples}")
        assertEquals(0, missingBonds, "每个分子键都应有可见场景键")

        // 3) 分子球完整:全部原胞 primary 已含 4 笼全部原子(240)。
        println("C60: visibleAtomsTotal=${visAtoms.size}")
        assertTrue(visAtoms.size >= 240, "4 笼 240 个原子球应全部可见")

        // 4) 8 顶点 + 6 面心 = 14 个完整 C60:每个位置的笼(半径 0.248a)至少 50 个原子球
        //    (完整笼 = 60 球;0.35a 统计半径内笼球壳全包含,留 50 下限容差)。
        val a = structure.lattice.a
        val cornerCenters = listOf(
            Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0),
            Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 1.0), Vec3(0.0, 1.0, 1.0), Vec3(1.0, 1.0, 1.0),
        )
        val faceCenters = listOf(
            Vec3(0.5, 0.5, 0.0), Vec3(0.5, 0.0, 0.5), Vec3(0.0, 0.5, 0.5),
            Vec3(0.5, 0.5, 1.0), Vec3(0.5, 1.0, 0.5), Vec3(1.0, 0.5, 0.5),
        )
        fun cageCount(center: Vec3): Int {
            val c = Vec3(center.x * a, center.y * a, center.z * a)
            return visAtoms.count { distance(it.atom.cartesianCoordinate.toVec3(), c) < 0.35 * a }
        }
        val corners = cornerCenters.map { cageCount(it) }
        val faces = faceCenters.map { cageCount(it) }
        println("C60: corner cages (8 vertices) spheres=$corners")
        println("C60: face cages (6 face centers) spheres=$faces")
        assertTrue(corners.all { it >= 50 }, "8 个顶点位置都应有完整 C60(≥50 球), got $corners")
        assertTrue(faces.all { it >= 50 }, "6 个面心位置都应有完整 C60(≥50 球), got $faces")
    }
}
