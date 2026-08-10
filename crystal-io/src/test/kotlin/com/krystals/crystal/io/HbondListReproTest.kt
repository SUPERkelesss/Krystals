package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondGrid
import com.krystals.crystal.analysis.bonding.BondRuleMatching
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.RadiusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import java.io.File

/**
 * 排查复现(2026-08-10 bug 报告):"编辑"-"氢键" 单击"计算"后 viewer 渲染了正确的氢键,
 * 但氢键规则列表为空。模拟完整链路:
 *   1. 打开路径:解析 iceIh → smartIonicRules(includeHbonds=true) 生成初始规则(含氢键)
 *   2. "计算"按钮:rebuildHbondRules(source=lastRadiusSource=SMART_IONIC, 默认)
 *   3. 列表过滤:hasMatchingBond(visibleBondRules 的核心逻辑)
 */
class HbondListReproTest {

    private fun iceIhFile(): File = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
        .map { File(it, "08_molecular/iceIh_H2O.cif") }
        .firstOrNull { it.exists() }
        ?: fail("iceIh CIF not found")

    private fun parseIceIh(): Pair<com.krystals.crystal.core.model.CrystalStructure, BondConfiguration> {
        val parsed = CifCodec.parseStructure(iceIhFile().readText())
        return parsed.structure to parsed.bondConfiguration
    }

    @Test
    fun openPathGeneratesHbondRules() {
        val (structure, config) = parseIceIh()
        val atoms = SymmetryExpander.expand(structure)
        println("iceIh: ${atoms.size} atoms, ${structure.sites.size} sites")
        val smart = BondValence.smartIonicRules(structure, config, 0.45, includeHbonds = true)
        assertTrue(smart.success, "smartIonic should succeed on iceIh")
        val hbonds = smart.rules.filter { it.isHBond }
        println("open-path hbond rules: ${hbonds.size}")
        assertTrue(hbonds.isNotEmpty(), "open path should generate hbond rules")
    }

    @Test
    fun computeButtonKeepsHbondRulesVisible() {
        val (structure, config) = parseIceIh()
        val atoms = SymmetryExpander.expand(structure)
        val smart = BondValence.smartIonicRules(structure, config, 0.45, includeHbonds = true)
        val openedConfig = config.copy(rules = smart.rules)

        // "计算"按钮:rebuildHbondRules(SMART_IONIC) —— lastRadiusSource 默认 SMART_IONIC
        val recomputed = CrystalEditor.rebuildHbondRules(structure, openedConfig, RadiusSource.SMART_IONIC, 0.45)
        val newHbonds = recomputed.bondConfiguration.rules.filter { it.isHBond }
        println("after compute(SMART_IONIC): ${recomputed.bondConfiguration.rules.size} rules, ${newHbonds.size} hbonds")
        assertTrue(newHbonds.isNotEmpty(), "compute(SMART_IONIC) should keep hbond rules")

        // 列表过滤:visibleBondRules 的核心 = hasMatchingBond
        val grid = BondGrid(atoms, structure, BondRuleMatching.estimateCellSize(structure))
        for (rule in newHbonds) {
            val ok = BondRuleMatching.hasMatchingBond(rule, structure, recomputed.bondConfiguration, atoms, grid)
            println("hbond rule ${rule.siteA}-${rule.siteB} [${rule.minAngstrom},${rule.maxAngstrom}] -> hasMatchingBond=$ok")
            assertTrue(ok, "hbond rule ${rule.siteA}-${rule.siteB} filtered by hasMatchingBond")
        }
        assertEquals(newHbonds.size, newHbonds.distinctBy { it.key }.size, "duplicate hbond keys")
    }

    @Test
    fun bondingSourceKeepsHbondRulesVisible() {
        val (structure, config) = parseIceIh()
        val atoms = SymmetryExpander.expand(structure)
        val smart = BondValence.smartIonicRules(structure, config, 0.45, includeHbonds = true)
        val openedConfig = config.copy(rules = smart.rules)

        val recomputed = CrystalEditor.rebuildHbondRules(structure, openedConfig, RadiusSource.BONDING, 0.45)
        val newHbonds = recomputed.bondConfiguration.rules.filter { it.isHBond }
        println("after compute(BONDING): ${newHbonds.size} hbonds")
        val grid = BondGrid(atoms, structure, BondRuleMatching.estimateCellSize(structure))
        for (rule in newHbonds) {
            val ok = BondRuleMatching.hasMatchingBond(rule, structure, recomputed.bondConfiguration, atoms, grid)
            println("hbond rule ${rule.siteA}-${rule.siteB} [${rule.minAngstrom},${rule.maxAngstrom}] -> hasMatchingBond=$ok")
            assertTrue(ok, "hbond rule ${rule.siteA}-${rule.siteB} filtered by hasMatchingBond")
        }
    }
}
