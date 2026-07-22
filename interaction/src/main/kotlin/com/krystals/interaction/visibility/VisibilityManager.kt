package com.krystals.interaction.visibility

import com.krystals.interaction.state.VisibilityState

object VisibilityManager {
    fun setSiteHidden(state: VisibilityState, siteId: String, hidden: Boolean): VisibilityState = state.copy(
        hiddenSites = if (hidden) state.hiddenSites + siteId else state.hiddenSites - siteId,
    )

    fun setBondHidden(state: VisibilityState, bondKey: String, hidden: Boolean): VisibilityState = state.copy(
        hiddenBondPairs = if (hidden) state.hiddenBondPairs + bondKey else state.hiddenBondPairs - bondKey,
    )

    fun setPolyhedronVisible(state: VisibilityState, siteId: String, visible: Boolean): VisibilityState = state.copy(
        polyhedronSites = if (visible) state.polyhedronSites + siteId else state.polyhedronSites - siteId,
    )
}
