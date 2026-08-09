package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Perf baseline + golden pin for the periodic Voronoi neighbour search. The pyrochlore cell
 * (Fd-3m, 88 expanded atoms) is the structure whose bond-rule computation took ~31 s on device
 * (logcat "OpenCIF 6/6 [compute +31244ms]"); this test quantifies the JVM baseline before and
 * after the buildCell early-break optimisation and pins the smart-ionic rule count (7 on open).
 */
class VoronoiPerformanceTest {
    // pyrochlore La2Zr2O7, Fd-3m (#227), a = 10.80701912 Å(与 res/cifs_example/02_oxides/pyrochlore_La2Zr2O7.cif 一致)
    private fun pyrochlore(): CrystalStructure {
        val sg = SpaceGroupCatalog.find("Fd-3m")!!
        return CrystalStructure(
            blockName = "La2Zr2O7",
            lattice = Lattice(10.80701912, 10.80701912, 10.80701912, 90.0, 90.0, 90.0),
            spaceGroup = sg,
            symmetryOperations = SpaceGroupCatalog.operations(sg.symbol),
            sites = listOf(
                Site("La0", "La0", Species("La"), FractionalCoordinate(0.125, 0.125, 0.125), 1.0),
                Site("Zr1", "Zr1", Species("Zr"), FractionalCoordinate(0.125, 0.125, 0.625), 1.0),
                Site("O2", "O2", Species("O"), FractionalCoordinate(0.0, 0.0, 0.29309247), 1.0),
                Site("O3", "O3", Species("O"), FractionalCoordinate(0.0, 0.0, 0.0), 1.0),
            ),
        )
    }

    @Test
    fun pyrochloreVoronoiRunsFastAndProducesGoldenResult() {
        val structure = pyrochlore()
        val atoms = SymmetryExpander.expand(structure)
        assertEquals(88, atoms.size, "对称扩展后应为 88 原子")

        val start = System.nanoTime()
        val neighbours = VoronoiNeighbours.find(structure, atoms)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // 宽松性能断言:JVM 上优化后应远小于此(目标 < 500ms);CI 慢机器留余量
        assertTrue(elapsedMs < 5_000, "Voronoi find 耗时 ${elapsedMs}ms,超过 5s 阈值")
        assertTrue(neighbours.isNotEmpty())
        // golden:每个位点按 Voronoi 面的配位数(实现后可打印实际值回填,先只断言非空 + 原子数)
        println("pyrochlore Voronoi: ${atoms.size} atoms, ${neighbours.size} edges, ${elapsedMs}ms")
    }

    @Test
    fun smartIonicRulesOnPyrochloreMatchesOpenLog() {
        val structure = pyrochlore()
        val bondConfiguration = BondConfiguration(emptyList())
        val result = BondValence.smartIonicRules(structure, bondConfiguration, 0.45, includeHbonds = false)
        assertTrue(result.success)
        // 打开日志显示 7 rules / 0 hbonds;若此处实测不是 7,先查明差异(可能 bondEpsilon 非 0.45)再回填
        println("smartIonic rules: ${result.rules}")
        assertEquals(7, result.rules.size, "与 logcat 的 7 rules 对齐")
    }

    @Test
    fun voronoiHonoursCancellationCheck() {
        val structure = pyrochlore()
        val atoms = SymmetryExpander.expand(structure)
        var checks = 0
        val start = System.nanoTime()
        val thrown = runCatching {
            VoronoiNeighbours.find(structure, atoms) { checks++; checks > 64 }
        }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(thrown is VoronoiAbortedException, "应抛 VoronoiAbortedException,实际: $thrown")
        assertTrue(elapsedMs < 1_000, "取消后应在 1s 内返回,实际 ${elapsedMs}ms")
    }

    @Test
    fun smartIonicRulesPropagatesCancellation() {
        val structure = pyrochlore()
        val bondConfiguration = BondConfiguration(emptyList())
        var checks = 0
        val thrown = runCatching {
            BondValence.smartIonicRules(
                structure, bondConfiguration, 0.45, includeHbonds = false,
                cancelCheck = { checks++; checks > 64 },
            )
        }.exceptionOrNull()
        assertTrue(thrown is VoronoiAbortedException, "smartIonicRules 应透传取消异常,实际: $thrown")
    }
}
