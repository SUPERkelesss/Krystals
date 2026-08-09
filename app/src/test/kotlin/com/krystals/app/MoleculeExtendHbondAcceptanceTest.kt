package com.krystals.app

import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.toMolecules
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.io.CifCodec
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.HbondInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4.2 验收(2026-08-09):分子展开模式下,分子映像补全的氢键(含动态原子端点)必须渲染。
 *
 * 数值口径(moleculeExtend=true,默认氢键规则,角度阈值 110°):
 *  - urea:   8 条网络氢键模板 → 12 条可见氢键(+4 映像副本)
 *  - h3po4: 12 条网络氢键模板 → 26 条可见氢键(+14 映像副本)
 *  - iceIh: 32 条网络氢键模板 → 21 条可见氢键(无动态端点,副本已由主 pass 覆盖,不增)
 *
 * 不变量:可见氢键两端都有可见球(不悬空);无重复端点对;每条可见氢键的几何
 * 都是某网络模板的周期副本(偏移 ℤ³ 同余 + 键长一致)。
 */
class MoleculeExtendHbondAcceptanceTest {

    private fun parse(rel: String): com.krystals.crystal.io.ParsedStructure {
        val dir = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("corpus missing")
        return CifCodec.parseStructure(File(dir, rel).readText())
    }

    private fun scene(rel: String): RenderScene {
        val parsed = parse(rel)
        val withHbond = CrystalEditor.rebuildHbondRules(parsed.structure, parsed.bondConfiguration, RadiusSource.BONDING)
        val net = BondDetector.buildNetwork(withHbond.structure, withHbond.bondConfiguration, Expansion(1, 1, 1))
        val molecules = net.toMolecules()
        return CrystalRenderSceneFactory.build(
            analysis = net,
            appearance = ViewerAppearance(),
            renderConfiguration = RenderConfiguration(),
            moleculeExtend = true,
            molecules = molecules,
        )
    }

    private fun visibleBalls(s: RenderScene): List<Vec3> =
        s.objects.filterIsInstance<AtomInstance>().filter { it.visible }
            .map { it.atom.cartesianCoordinate.toVec3() }

    private fun visibleHbonds(s: RenderScene): List<HbondInstance> =
        s.objects.filterIsInstance<HbondInstance>().filter { it.visible }

    private fun assertNoDanglingHbond(s: RenderScene) {
        val balls = visibleBalls(s)
        for (h in visibleHbonds(s)) {
            assertTrue(
                balls.any { distance(it, h.start) < 0.6 } && balls.any { distance(it, h.end) < 0.6 },
                "dangling hbond ${h.id}: start=${h.start} end=${h.end}",
            )
        }
    }

    private fun assertNoDuplicateHbond(s: RenderScene) {
        val seen = HashSet<Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>>()
        fun key(p: Vec3) = Triple((p.x * 1000).toInt(), (p.y * 1000).toInt(), (p.z * 1000).toInt())
        for (h in visibleHbonds(s)) {
            assertTrue(seen.add(key(h.start) to key(h.end)), "duplicate hbond endpoint pair for ${h.id}")
        }
    }

    /** 每条可见氢键的几何必须是某网络模板的周期副本(平移保几何)—— 由单测
     *  moleculeExtendCompletesHbondPeriodicCopies 的定向断言覆盖。 */

    @Test
    fun ureaCompletesHbondImageCopies() {
        val s = scene("08_molecular/urea_CH4N2O.cif")
        assertEquals(12, visibleHbonds(s).size, "urea: 8 templates + 4 image copies")
        assertNoDanglingHbond(s)
        assertNoDuplicateHbond(s)
    }

    @Test
    fun h3po4CompletesHbondImageCopies() {
        val s = scene("08_molecular/h3po4_H3PO4.cif")
        assertEquals(26, visibleHbonds(s).size, "h3po4: 12 templates + 14 image copies")
        assertNoDanglingHbond(s)
        assertNoDuplicateHbond(s)
    }

    @Test
    fun iceIhHbondCountUnchanged() {
        val s = scene("08_molecular/iceIh_H2O.cif")
        assertEquals(21, visibleHbonds(s).size, "iceIh: no dynamic endpoints, copies covered by main pass")
        assertNoDanglingHbond(s)
        assertNoDuplicateHbond(s)
    }
}
