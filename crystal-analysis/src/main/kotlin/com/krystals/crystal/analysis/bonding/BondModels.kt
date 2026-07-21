package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.periodic.Int3

enum class BondRuleSource { CUSTOM, EXPLICIT, AUTO }

data class BondRule(
    val siteA: String,
    val siteB: String,
    val minAngstrom: Double,
    val maxAngstrom: Double,
    val source: BondRuleSource = BondRuleSource.CUSTOM,
    val extendAcrossCell: Boolean = false,
) {
    init {
        require(minAngstrom >= 0.0 && maxAngstrom >= minAngstrom)
    }

    val key: String get() = listOf(siteA, siteB).sorted().joinToString("\u0000")
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
