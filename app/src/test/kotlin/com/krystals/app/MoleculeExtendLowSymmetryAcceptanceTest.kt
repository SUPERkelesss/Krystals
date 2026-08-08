package com.krystals.app

import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.isMolecularCrystal
import com.krystals.crystal.analysis.bonding.toMolecules
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.io.CifCodec
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.HbondInstance
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 低对称晶胞(单斜/正交)分子展开端到端验收:任一方向与 [0,1]³ 显示区相交的分子映像
 * 都必须完整 —— 每个映像原子位置 ±0.05A 有可见球、每根分子键两端 ±0.05A 有可见键。
 * 氢键结构(glycine/h3po4)额外验收决策 2:氢键仅在两端球都可见时渲染,不漏、不悬空。
 */
class MoleculeExtendLowSymmetryAcceptanceTest {

    private fun parse(rel: String): com.krystals.crystal.io.ParsedStructure {
        val dir = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("corpus missing")
        return CifCodec.parseStructure(File(dir, rel).readText())
    }

    @Test
    fun benzeneMoleculeExtendIsComplete() =
        checkCompleteness("08_molecular/benzene_C6H6.cif", "benzene", withHbonds = false)

    @Test
    fun oxalicacidMoleculeExtendIsComplete() =
        checkCompleteness("08_molecular/oxalicacid_C2H6O6.cif", "oxalicacid", withHbonds = false)

    @Test
    fun glycineMoleculeExtendIsCompleteWithHbonds() =
        checkCompleteness("08_molecular/glycine_C2H5NO2.cif", "glycine", withHbonds = true)

    @Test
    fun h3po4MoleculeExtendIsCompleteWithHbonds() =
        checkCompleteness("08_molecular/h3po4_H3PO4.cif", "h3po4", withHbonds = true)

    private fun checkCompleteness(rel: String, label: String, withHbonds: Boolean) {
        val parsed = parse(rel)
        val structure = parsed.structure
        // 默认 bondConfiguration 无氢键规则:含氢键的结构须先重建氢键规则再建网络
        // (参照 crystal-analysis HbondRebuildTest 用法)。
        val config = if (withHbonds) {
            CrystalEditor.rebuildHbondRules(structure, parsed.bondConfiguration, RadiusSource.BONDING)
                .bondConfiguration
        } else parsed.bondConfiguration
        val net = BondDetector.buildNetwork(structure, config, Expansion(1, 1, 1))
        assertTrue(net.isMolecularCrystal(), "$label 应为分子晶体")
        val molecules = net.toMolecules()
        val molAtoms = molecules.flatMap { it.atoms }
        val molBonds = molecules.flatMap { it.bonds }
        println("$label: molecules=${molecules.size} atoms=${molAtoms.size} bonds=${molBonds.size} hbonds=${net.hbonds.size}")

        val scene = CrystalRenderSceneFactory.build(
            analysis = net,
            appearance = ViewerAppearance(),
            renderConfiguration = RenderConfiguration(),
            moleculeExtend = true,
            molecules = molecules,
        )
        val visAtoms = scene.objects.filterIsInstance<AtomInstance>().filter { it.visible }
        val visBonds = scene.objects.filterIsInstance<BondInstance>().filter { it.visible }
        val visHbonds = scene.objects.filterIsInstance<HbondInstance>().filter { it.visible }

        // 1) 每个与 [0,1]³ 相交的分子映像(按 t 范围公式独立计算)的每个原子 ±0.05A 有可见球。
        val images = molecules.flatMap { intersectingImages(it, structure) }
        println("$label: images=${images.size} imageAtoms=${images.sumOf { it.size }}")
        val missing = images.flatten().count { (_, p) ->
            visAtoms.none { distance(it.atom.cartesianCoordinate.toVec3(), p) < 0.05 }
        }
        assertEquals(0, missing, "$label: 每个相交映像原子都应有可见球")

        // 2) 每根分子键两端(±0.05A,双向)有可见键。
        val posById = HashMap<Int, Vec3>()
        for (m in molecules) for (ma in m.atoms) posById[ma.id] = ma.position.toVec3()
        var missingBonds = 0
        for (mb in molBonds) {
            val pa = posById.getValue(mb.from)
            val pb = posById.getValue(mb.to)
            val hit = visBonds.any { b ->
                (distance(b.start, pa) < 0.05 && distance(b.end, pb) < 0.05) ||
                    (distance(b.start, pb) < 0.05 && distance(b.end, pa) < 0.05)
            }
            if (!hit) missingBonds++
        }
        println("$label: moleculeBonds=${molBonds.size} missingBonds=$missingBonds")
        assertEquals(0, missingBonds, "$label: 每根分子键都应有可见场景键")

        if (withHbonds) {
            val atomById = net.atoms.associateBy { it.id }
            // 3) 每条可见 HbondInstance 两端 0.05A 内有可见球(不悬空)。
            for (hbI in visHbonds) {
                val donorPos = atomById[hbI.hbond.donorId]?.cartesianCoordinate?.toVec3() ?: continue
                val acceptorPos = atomById[hbI.hbond.acceptorId]?.cartesianCoordinate?.toVec3() ?: continue
                assertTrue(
                    visAtoms.any { distance(it.atom.cartesianCoordinate.toVec3(), donorPos) < 0.05 },
                    "$label: 可见 hbond 的 donor 端必须有可见球",
                )
                assertTrue(
                    visAtoms.any { distance(it.atom.cartesianCoordinate.toVec3(), acceptorPos) < 0.05 },
                    "$label: 可见 hbond 的 acceptor 端必须有可见球",
                )
            }
            // 4) 每条两端球皆可见的 hbond 均可见(不漏)。
            val visHbondKeys = visHbonds.map { setOf(it.hbond.donorId, it.hbond.acceptorId) }.toSet()
            var missed = 0
            for (hb in net.hbonds) {
                val donorPos = atomById[hb.donorId]?.cartesianCoordinate?.toVec3() ?: continue
                val acceptorPos = atomById[hb.acceptorId]?.cartesianCoordinate?.toVec3() ?: continue
                val donorVisible = visAtoms.any { distance(it.atom.cartesianCoordinate.toVec3(), donorPos) < 0.05 }
                val acceptorVisible = visAtoms.any { distance(it.atom.cartesianCoordinate.toVec3(), acceptorPos) < 0.05 }
                if (donorVisible && acceptorVisible &&
                    !visHbondKeys.any { it == setOf(hb.donorId, hb.acceptorId) }
                ) missed++
            }
            println("$label: hbonds=${net.hbonds.size} visHbonds=${visHbonds.size} missedVisibleHbonds=$missed")
            assertEquals(0, missed, "$label: 两端球皆可见的 hbond 必须可见")
            assertTrue(visHbonds.isNotEmpty(), "$label: 至少一条可见氢键")
        }
    }

    /** 按新规则独立计算分子 m 与 [0,1]³ 显示区相交的全部周期映像(每映像 = 原子位置列表)。 */
    private fun intersectingImages(
        m: com.krystals.crystal.core.model.Molecule,
        structure: com.krystals.crystal.core.model.CrystalStructure,
    ): List<List<Pair<Int, Vec3>>> {
        val atomById = m.atoms.associateBy { it.id }
        val adj = HashMap<Int, MutableList<Int>>()
        for (b in m.bonds) {
            adj.getOrPut(b.from) { mutableListOf() } += b.to
            adj.getOrPut(b.to) { mutableListOf() } += b.from
        }
        val visited = HashSet<Int>()
        val comps = mutableListOf<List<com.krystals.crystal.core.model.MoleculeAtom>>()
        for (a in m.atoms) {
            if (!visited.add(a.id)) continue
            val comp = mutableListOf<com.krystals.crystal.core.model.MoleculeAtom>()
            val stack = ArrayDeque<Int>(); stack.add(a.id)
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                comp += atomById.getValue(id)
                for (n in adj[id].orEmpty()) if (visited.add(n)) stack.add(n)
            }
            comps += comp
        }
        return comps.flatMap { comp ->
            val fr = comp.map {
                it.id to structure.lattice.toFractional(CartesianCoordinate(it.position.x, it.position.y, it.position.z))
            }
            val minX = fr.minOf { it.second.x }; val maxX = fr.maxOf { it.second.x }
            val minY = fr.minOf { it.second.y }; val maxY = fr.maxOf { it.second.y }
            val minZ = fr.minOf { it.second.z }; val maxZ = fr.maxOf { it.second.z }
            val txRange = kotlin.math.ceil(-maxX + 1e-6).toInt()..kotlin.math.floor(1.0 - minX - 1e-6).toInt()
            val tyRange = kotlin.math.ceil(-maxY + 1e-6).toInt()..kotlin.math.floor(1.0 - minY - 1e-6).toInt()
            val tzRange = kotlin.math.ceil(-maxZ + 1e-6).toInt()..kotlin.math.floor(1.0 - minZ - 1e-6).toInt()
            val result = mutableListOf<List<Pair<Int, Vec3>>>()
            for (tx in txRange) for (ty in tyRange) for (tz in tzRange) {
                result += fr.map { (id, f) ->
                    val shifted = structure.lattice.toCartesian(FractionalCoordinate(f.x + tx, f.y + ty, f.z + tz))
                    id to Vec3(shifted.x, shifted.y, shifted.z)
                }
            }
            result
        }
    }
}
