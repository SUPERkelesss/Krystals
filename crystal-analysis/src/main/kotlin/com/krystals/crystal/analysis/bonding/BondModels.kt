package com.krystals.crystal.analysis.bonding

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
