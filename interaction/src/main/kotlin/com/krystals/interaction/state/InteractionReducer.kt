package com.krystals.interaction.state

import com.krystals.crystal.core.math.eulerYX
import com.krystals.interaction.camera.AlignmentController
import com.krystals.interaction.camera.OrbitController
import com.krystals.interaction.camera.PanController
import com.krystals.interaction.camera.ZoomController
import com.krystals.interaction.measure.MeasurementSelection
import com.krystals.interaction.inspection.InspectionManager
import com.krystals.interaction.visibility.VisibilityManager

object InteractionReducer {
    fun reduce(state: InteractionState, command: ViewerCommand): InteractionState = when (command) {
        is ViewerCommand.Orbit -> if (state.session.locked) state else state.copy(
            session = state.session.copy(camera = OrbitController.orbit(state.session.camera, command.dxPx, command.dyPx)),
            document = state.document.clearTransientAfterViewMove(),
        )
        is ViewerCommand.Zoom -> if (state.session.locked) state else state.copy(
            session = state.session.copy(camera = ZoomController.zoom(state.session.camera, command.factor)),
            document = state.document.clearTransientAfterViewMove(),
        )
        is ViewerCommand.Pan -> if (state.session.locked) state else state.copy(
            session = state.session.copy(camera = PanController.pan(state.session.camera, command.dxPx, command.dyPx)),
            document = state.document.clearTransientAfterViewMove(),
        )
        is ViewerCommand.AlignCartesian -> state.copy(
            session = state.session.copy(camera = AlignmentController.alignCartesian(state.session.camera, command.axis)),
            document = state.document.clearTransientAfterViewMove(),
        )
        is ViewerCommand.AlignCellAxis -> state.copy(
            session = state.session.copy(camera = AlignmentController.alignCellAxis(state.session.camera, command.axis, command.lattice)),
            document = state.document.clearTransientAfterViewMove(),
        )
        ViewerCommand.ToggleLock -> state.copy(session = state.session.copy(locked = !state.session.locked))
        is ViewerCommand.SetViewport -> state.copy(session = state.session.copy(
            viewportWidth = command.width.coerceAtLeast(1), viewportHeight = command.height.coerceAtLeast(1),
        ))
        is ViewerCommand.SelectAtom -> state.copy(document = state.document.select(command.atomId))
        ViewerCommand.ClearSelection -> state.copy(document = state.document.copy(selection = SelectionState()))
        is ViewerCommand.InspectAtom -> state.copy(document = state.document.copy(
            inspection = InspectionManager.inspect(state.document.inspection, command.atomId),
        ))
        is ViewerCommand.ToggleInspectionLock -> state.copy(document = state.document.copy(
            inspection = InspectionManager.toggleLock(state.document.inspection, command.atomId, command.currentlyLocked),
        ))
        ViewerCommand.ClearTransientInspection -> state.copy(document = state.document.copy(inspection = state.document.inspection.copy(inspectedAtomId = null)))
        is ViewerCommand.SetMeasurementMode -> state.copy(document = state.document.copy(
            measurementMode = command.mode,
            selection = SelectionState(),
        ))
        is ViewerCommand.ToggleMeasurementLock -> state.copy(document = state.document.toggleMeasurement(command.measurement, command.currentlyLocked))
        ViewerCommand.ClearTransientViewerState -> state.copy(document = state.document.clearTransient())
        is ViewerCommand.SetSiteHidden -> state.copy(document = state.document.copy(
            visibility = VisibilityManager.setSiteHidden(state.document.visibility, command.siteId, command.hidden),
        ))
        is ViewerCommand.SetBondHidden -> state.copy(document = state.document.copy(
            visibility = VisibilityManager.setBondHidden(state.document.visibility, command.bondKey, command.hidden),
        ))
        is ViewerCommand.SetPolyhedronVisible -> state.copy(document = state.document.copy(
            visibility = VisibilityManager.setPolyhedronVisible(state.document.visibility, command.siteId, command.visible),
        ))
        is ViewerCommand.SetShowBonds -> state.copy(document = state.document.copy(visibility = state.document.visibility.copy(showBonds = command.visible)))
        is ViewerCommand.SetVisibility -> state.copy(document = state.document.copy(visibility = command.visibility))
        ViewerCommand.ClearVisibility -> state.copy(document = state.document.copy(visibility = VisibilityState()))
    }

    fun isUndoable(command: ViewerCommand): Boolean = when (command) {
        is ViewerCommand.Orbit, is ViewerCommand.Zoom, is ViewerCommand.Pan, is ViewerCommand.SetViewport -> false
        ViewerCommand.ToggleLock -> false
        else -> true
    }

    private fun ViewerDocumentState.select(atomId: Long): ViewerDocumentState {
        val expected = when (measurementMode) {
            com.krystals.interaction.measure.MeasurementMode.LENGTH -> 2
            com.krystals.interaction.measure.MeasurementMode.ANGLE -> 3
            com.krystals.interaction.measure.MeasurementMode.DIHEDRAL -> 4
            com.krystals.interaction.measure.MeasurementMode.NONE -> 1
        }
        val ids = if (selection.selectedAtomIds.size >= expected) listOf(atomId) else selection.selectedAtomIds + atomId
        return copy(selection = SelectionState(ids))
    }

    private fun ViewerDocumentState.toggleMeasurement(measurement: MeasurementSelection?, currentlyLocked: Boolean): ViewerDocumentState {
        if (currentlyLocked && measurement != null) return copy(lockedMeasurements = lockedMeasurements.filterNot { it == measurement })
        if (!currentlyLocked && measurementMode != com.krystals.interaction.measure.MeasurementMode.NONE && selection.selectedAtomIds.isNotEmpty()) {
            return copy(lockedMeasurements = lockedMeasurements + MeasurementSelection(selection.selectedAtomIds, measurementMode), selection = SelectionState())
        }
        return this
    }

    private fun ViewerDocumentState.clearTransientAfterViewMove(): ViewerDocumentState = copy(
        selection = if (measurementMode == com.krystals.interaction.measure.MeasurementMode.NONE) selection else SelectionState(),
        inspection = inspection.copy(inspectedAtomId = null),
    )

    private fun ViewerDocumentState.clearTransient(): ViewerDocumentState = copy(
        selection = SelectionState(), inspection = inspection.copy(inspectedAtomId = null),
    )
}
