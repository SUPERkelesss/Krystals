package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.model.Site
import com.krystals.crystal.data.PeriodicTableData

/** Shared ordering for generated bond rules: metals first, then larger atomic number. */
internal val bondSymbolIndex: Map<String, Int> =
    PeriodicTableData.symbols.withIndex().associate { it.value to it.index }

internal fun orderBondSites(siteA: Site, siteB: Site): Pair<Site, Site> {
    val aMetal = PeriodicTableData.isMetal(siteA.species.symbol)
    val bMetal = PeriodicTableData.isMetal(siteB.species.symbol)
    return when {
        aMetal && !bMetal -> siteA to siteB
        !aMetal && bMetal -> siteB to siteA
        else -> {
            val aNum = bondSymbolIndex[siteA.species.symbol] ?: -1
            val bNum = bondSymbolIndex[siteB.species.symbol] ?: -1
            if (aNum >= bNum) siteA to siteB else siteB to siteA
        }
    }
}
