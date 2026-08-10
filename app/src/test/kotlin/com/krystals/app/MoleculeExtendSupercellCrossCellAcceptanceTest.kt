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
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-08-09 回归:分子拓展 + 扩展晶胞(2×2×2)下,穿过子晶胞表面的分子内键
 * (两端 cellOffset 不同的键)必须全部显示。根因:moleculeIndexOf 对非 shell
 * 原子直接返回 null,超胞 primary(cellOffset≠0)归属分子失败,跨胞键被隐藏;
 * 修复后经 siteId + 整数平移找回原胞代表。
 *
 * 数值口径(urea,2×2×2,moleculeExtend=true):
 *  - 全部 88 条跨胞键(任一端是壳层或 cellOffset 非零)渲染可见,与网络一一对应
 *  - 1×1×1 行为不变(16/16)
 */
class MoleculeExtendSupercellCrossCellAcceptanceTest {

    private fun corpus(): File =
        sequenceOf(File("../res/cifs_example"), File("res/cifs_example")).firstOrNull { it.isDirectory } ?: error("corpus missing")

    private fun scene(expansion: Expansion): Pair<RenderScene, com.krystals.crystal.analysis.bonding.BondNetwork> {
        val cif = File(corpus(), "08_molecular/urea_CH4N2O.cif")
        val parsed = CifCodec.parseStructure(cif.readText())
        val withHbond = CrystalEditor.rebuildHbondRules(parsed.structure, parsed.bondConfiguration, RadiusSource.BONDING)
        val net = BondDetector.buildNetwork(withHbond.structure, withHbond.bondConfiguration, expansion)
        return CrystalRenderSceneFactory.build(
            analysis = net,
            appearance = ViewerAppearance(),
            renderConfiguration = RenderConfiguration(),
            moleculeExtend = true,
            molecules = net.toMolecules(),
        ) to net
    }

    private fun assertAllCrossCellBondsVisible(expansion: Expansion, expected: Int) {
        val (scene, net) = scene(expansion)
        val atomById = net.atoms.associateBy { it.id }
        val visibleBonds = scene.objects.filterIsInstance<BondInstance>().filter { it.visible }
        val visibleBalls = scene.objects.filterIsInstance<AtomInstance>().filter { it.visible }
            .map { it.atom.cartesianCoordinate.toVec3() }
        var crossTotal = 0
        for (b in net.bonds) {
            val a1 = atomById[b.atomA] ?: continue
            val a2 = atomById[b.atomB] ?: continue
            val cross = a1.cellOffset != a2.cellOffset || a1.isShell || a2.isShell
            if (!cross) continue
            crossTotal++
            val rendered = visibleBonds.any { vb ->
                (distance(vb.start, a1.cartesianCoordinate.toVec3()) < 1e-3 && distance(vb.end, a2.cartesianCoordinate.toVec3()) < 1e-3) ||
                    (distance(vb.start, a2.cartesianCoordinate.toVec3()) < 1e-3 && distance(vb.end, a1.cartesianCoordinate.toVec3()) < 1e-3)
            }
            assertTrue(rendered, "cross-cell bond ${b.atomA}-${b.atomB} (${b.rule.siteA}-${b.rule.siteB}) not rendered in $expansion")
        }
        assertEquals(expected, crossTotal, "cross-cell bond count in $expansion")
        // 不悬空:每条可见键两端都有可见球(决策 2 口径)。
        for (vb in visibleBonds) {
            assertTrue(
                visibleBalls.any { distance(it, vb.start) < 0.6 } && visibleBalls.any { distance(it, vb.end) < 0.6 },
                "dangling bond ${vb.id}",
            )
        }
    }

    @Test
    fun supercellCrossCellBondsAllVisible() {
        assertAllCrossCellBondsVisible(Expansion(1, 1, 1), 16)
        assertAllCrossCellBondsVisible(Expansion(2, 2, 2), 88)
    }
}
