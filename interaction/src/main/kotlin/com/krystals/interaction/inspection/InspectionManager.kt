package com.krystals.interaction.inspection

import com.krystals.interaction.state.InspectionState

object InspectionManager {
    fun inspect(state: InspectionState, atomId: Long): InspectionState = state.copy(inspectedAtomId = atomId)

    fun toggleLock(state: InspectionState, atomId: Long, currentlyLocked: Boolean): InspectionState =
        if (currentlyLocked) {
            state.copy(lockedInspectedAtomIds = state.lockedInspectedAtomIds - atomId)
        } else {
            state.copy(lockedInspectedAtomIds = state.lockedInspectedAtomIds + atomId, inspectedAtomId = null)
        }
}
