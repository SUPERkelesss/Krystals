package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.isMolecularCrystal
import com.krystals.crystal.analysis.bonding.toMolecules
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.angleDegrees
import com.krystals.crystal.core.model.AtomImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 分子晶体语料回归(用户验收案例):
 * - 白磷 P4:分子展开的分子拓扑完整 —— 每分子 4 原子 6 键、每 P 配位 3。
 * - 尿素 CH4N2O:smartIonic 生成的氢键全部符合代码内规则 —— 距离在规则窗口内、
 *   每个 H 恰一条(per-H 最短)、X-H-Y 角度 > 110°。
 */
class MoleculeCrystalCorpusTest {

    private fun parse(rel: String): com.krystals.crystal.io.ParsedStructure {
        val dir = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("corpus missing")
        return CifCodec.parseStructure(File(dir, rel).readText())
    }

    private fun shortestDisplacement(lattice: com.krystals.crystal.core.math.Mat3, from: Vec3, to: Vec3): Vec3 {
        val frac = lattice.inverse() * (to - from)
        val wrapped = Vec3(
            frac.x - kotlin.math.round(frac.x),
            frac.y - kotlin.math.round(frac.y),
            frac.z - kotlin.math.round(frac.z),
        )
        return lattice * wrapped
    }

    @Test
    fun whitePhosphorusMoleculesHaveCompleteCoordination() {
        val parsed = parse("00_elements/whitephosphorus_P4.cif")
        val net = BondDetector.buildNetwork(parsed.structure, parsed.bondConfiguration)
        assertTrue(net.isMolecularCrystal(), "P4 应为分子晶体")
        val molecules = net.toMolecules()
        assertTrue(molecules.isNotEmpty(), "P4 应解析出分子")
        // 按位点组合去重:白磷 3 种位点组合,每种 2 个对称等价 P4 → 3 个分子项
        // (而非 6 个物理分子)。
        assertEquals(3, molecules.size, "白磷应为 3 个位点组合分子项, got ${molecules.map { "${it.name}:${it.atomCount}" }}")
        for (m in molecules) {
            assertEquals("P4", m.name)
            assertTrue(m.atomCount % 4 == 0, "原子数应为 P4 的整数倍, got ${m.atomCount}")
            val counts = HashMap<Int, Int>()
            for (mb in m.bonds) {
                counts[mb.from] = (counts[mb.from] ?: 0) + 1
                counts[mb.to] = (counts[mb.to] ?: 0) + 1
            }
            assertTrue(counts.values.all { it == 3 }, "每个 P 应配位 3, got ${counts.values}")
        }
    }

    @Test
    fun ureaHbondsConformToCodeRules() {
        val parsed = parse("08_molecular/urea_CH4N2O.cif")
        val bp = CrystalEditor.fromSmartIonicAttempt(parsed.structure, BondConfiguration(), 0.45, smartIonic = null)
        val net = BondDetector.buildNetwork(parsed.structure, BondConfiguration(bp.bondConfiguration.rules))
        val hbonds = net.bonds.filter { it.rule.isHBond }
        assertTrue(hbonds.isNotEmpty(), "尿素应生成氢键")
        val atomById = net.atoms.associateBy { it.id }
        // 1) 距离在规则窗口内。
        for (b in hbonds) {
            assertTrue(
                b.distance >= b.rule.minAngstrom - 1e-9 && b.distance <= b.rule.maxAngstrom + 1e-9,
                "氢键距离 ${b.distance} 超出窗口 ${b.rule.minAngstrom}-${b.rule.maxAngstrom}",
            )
        }
        // 2) per-H 最短:每个 H 原子恰一条氢键。
        val perH = HashMap<Long, Int>()
        for (b in hbonds) {
            val a = atomById[b.atomA] ?: continue
            val hId = if (a.species.symbol == "H") b.atomA else b.atomB
            perH[hId] = (perH[hId] ?: 0) + 1
        }
        assertTrue(perH.values.all { it == 1 }, "每个 H 应恰一条氢键,got $perH")
        // 3) 角度 X-H-Y > 110°(X = H 的共价伙伴)。
        val covalentPartners = HashMap<Long, MutableList<AtomImage>>()
        for (b in net.bonds) {
            if (b.rule.isHBond) continue
            val a = atomById[b.atomA] ?: continue
            val c = atomById[b.atomB] ?: continue
            if (a.species.symbol == "H") covalentPartners.getOrPut(b.atomA) { mutableListOf() } += c
            if (c.species.symbol == "H") covalentPartners.getOrPut(b.atomB) { mutableListOf() } += a
        }
        val lattice = parsed.structure.lattice.matrix
        for (b in hbonds) {
            val a = atomById[b.atomA] ?: continue
            val c = atomById[b.atomB] ?: continue
            val (h, y) = if (a.species.symbol == "H") a to c else c to a
            val hPos = h.cartesianCoordinate.toVec3()
            val toY = shortestDisplacement(lattice, hPos, y.cartesianCoordinate.toVec3())
            val partners = covalentPartners[h.id].orEmpty()
            if (partners.isEmpty()) continue // 无共价伙伴时跳过角度(与 BondDetector 一致)
            val anglesOk = partners.any { x ->
                val toX = shortestDisplacement(lattice, hPos, x.cartesianCoordinate.toVec3())
                angleDegrees(toX, Vec3(0.0, 0.0, 0.0), toY) > 110.0
            }
            assertTrue(anglesOk, "氢键 ${h.siteLabel}-${y.siteLabel} 角度不满足 >110° 规则")
        }
    }
}
