package com.krystals.interaction.state

import com.krystals.crystal.core.lattice.Lattice
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.measure.MeasurementSelection

sealed interface ViewerCommand {
    data class Orbit(val dxPx: Float, val dyPx: Float) : ViewerCommand
    data class Zoom(val factor: Float) : ViewerCommand
    data class Pan(val dxPx: Float, val dyPx: Float) : ViewerCommand
    data class AlignCartesian(val axis: Char) : ViewerCommand
    data class AlignCellAxis(val axis: Char, val lattice: Lattice) : ViewerCommand
    data object ToggleLock : ViewerCommand
    data class SetViewport(val width: Int, val height: Int) : ViewerCommand

    data class SelectAtom(val atomId: Long, val siteId: String? = null) : ViewerCommand
    data object ClearSelection : ViewerCommand
    data class InspectAtom(val atomId: Long) : ViewerCommand
    data class ToggleInspectionLock(val atomId: Long, val currentlyLocked: Boolean) : ViewerCommand
    data object ClearTransientInspection : ViewerCommand

    data class SetMeasurementMode(val mode: MeasurementMode) : ViewerCommand
    data class ToggleMeasurementLock(val measurement: MeasurementSelection?, val currentlyLocked: Boolean) : ViewerCommand
    data object ClearTransientViewerState : ViewerCommand

    data class SetSiteHidden(val siteId: String, val hidden: Boolean) : ViewerCommand
    data class SetBondHidden(val bondKey: String, val hidden: Boolean) : ViewerCommand
    data class SetPolyhedronVisible(val siteId: String, val visible: Boolean) : ViewerCommand
    data class SetShowBonds(val visible: Boolean) : ViewerCommand
    data class SetVisibility(val visibility: VisibilityState) : ViewerCommand
    data object ClearVisibility : ViewerCommand
}
