package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.HydrogenBond
import com.krystals.crystal.core.periodic.Int3

enum class BondRuleSource { CUSTOM, EXPLICIT, AUTO }

data class BondRule(
    val siteA: String,
    val siteB: String,
    val minAngstrom: Double,
    val maxAngstrom: Double,
    val source: BondRuleSource = BondRuleSource.CUSTOM,
    val extendAtoB: Boolean = false, // Per v0.6.5: show cross-cell bonds where siteA atom is inside, siteB atom is external
    val extendBtoA: Boolean = false, // Per v0.6.5: show cross-cell bonds where siteB atom is inside, siteA atom is external
    val isHBond: Boolean = false,
) {
    init {
        require(minAngstrom >= 0.0 && maxAngstrom >= minAngstrom)
    }

    // Per v0.8.1: Hbond rules append "\u0000hbond" to the sorted-pair key so a normal
    // rule and an hbond rule for the same site pair coexist in BondConfiguration
    // (add replaces by key). Plain-pair lookups in disabledPairs / BondDetector
    // intentionally don't match the discriminator — hbonds are toggled in their own
    // submenu, not via the disabled-pair flow.
    val key: String = listOf(siteA, siteB).sorted().joinToString("\u0000") + if (isHBond) "\u0000hbond" else ""

    /** True if this rule allows extending across the cell boundary when the atom of [insideSiteId]
     *  is inside the unit cell and the other atom is an external shell. */
    fun shouldExtendAcrossCell(insideSiteId: String, outsideAtomIsExternal: Boolean): Boolean {
        if (!outsideAtomIsExternal) return true
        return when {
            siteA == siteB -> extendAtoB || extendBtoA
            insideSiteId == siteA -> extendAtoB
            insideSiteId == siteB -> extendBtoA
            else -> false
        }
    }
}

data class Bond(
    val atomA: Long,
    val atomB: Long,
    val distance: Double,
    val rule: BondRule,
    val offsetB: Int3 = Int3(0, 0, 0),
)

/**
 * 把一根氢键 [Bond](rule.isHBond == true)转成独立模型 [HydrogenBond]。
 *
 * [atomById] 用于判定供体 H 端(壳层原子作 atomB 的约定使 H 可能在任意一端);
 * 方向对消费端无关,但 [HydrogenBond.donorId] 语义必须正确。
 * 调用方保证该 [Bond] 的 rule 是氢键规则;普通键误转会产生错误的氢键数据。
 */
fun Bond.toHydrogenBond(atomById: Map<Long, AtomImage>): HydrogenBond {
    val donorFirst = atomById[atomA]?.species?.symbol == "H"
    val donor = if (donorFirst) atomA else atomB
    val acceptor = if (donorFirst) atomB else atomA
    return HydrogenBond(
        donorId = donor,
        acceptorId = acceptor,
        distance = distance,
        siteA = rule.siteA,
        siteB = rule.siteB,
        ruleKey = rule.key,
        extendAtoB = rule.extendAtoB,
        extendBtoA = rule.extendBtoA,
        offsetB = offsetB,
    )
}

data class BondConfiguration(
    val rules: List<BondRule> = emptyList(),
    val disabledPairs: Set<String> = emptySet(),
) {
    fun add(rule: BondRule): BondConfiguration {
        val existingIndex = rules.indexOfFirst { it.key == rule.key }
        val updatedRules = if (existingIndex < 0) {
            rules + rule
        } else {
            rules.mapIndexed { index, existing -> if (index == existingIndex) rule else existing }
        }
        return copy(rules = updatedRules, disabledPairs = disabledPairs - rule.key)
    }
    fun remove(rule: BondRule): BondConfiguration = copy(rules = rules.filterNot { it.key == rule.key })
    fun disable(siteA: String, siteB: String): BondConfiguration = copy(
        disabledPairs = disabledPairs + listOf(siteA, siteB).sorted().joinToString("\u0000"),
    )
    fun clear(): BondConfiguration = BondConfiguration()
}
