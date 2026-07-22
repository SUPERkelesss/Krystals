package com.krystals.interaction

import com.krystals.crystal.core.math.Vec3
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.state.InteractionReducer
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class InteractionReducerTest {
    @Test fun measurementSelectionResetsAtExpectedSize() {
        var state = InteractionState()
        state = InteractionReducer.reduce(state, ViewerCommand.SetMeasurementMode(MeasurementMode.ANGLE))
        state = InteractionReducer.reduce(state, ViewerCommand.SelectAtom(1))
        state = InteractionReducer.reduce(state, ViewerCommand.SelectAtom(2))
        state = InteractionReducer.reduce(state, ViewerCommand.SelectAtom(3))
        state = InteractionReducer.reduce(state, ViewerCommand.SelectAtom(4))
        assertEquals(listOf(4L), state.document.selection.selectedAtomIds)
    }

    @Test fun viewMoveClearsTransientMeasurementSelectionButKeepsLocks() {
        var state = InteractionState()
        state = InteractionReducer.reduce(state, ViewerCommand.SetMeasurementMode(MeasurementMode.LENGTH))
        state = InteractionReducer.reduce(state, ViewerCommand.SelectAtom(1))
        state = InteractionReducer.reduce(state, ViewerCommand.SelectAtom(2))
        state = InteractionReducer.reduce(state, ViewerCommand.ToggleMeasurementLock(null, false))
        state = InteractionReducer.reduce(state, ViewerCommand.Orbit(2f, 1f))
        assertTrue(state.document.selection.selectedAtomIds.isEmpty())
        assertEquals(1, state.document.lockedMeasurements.size)
    }

    @Test fun degenerateDihedralProducesNoPlane() {
        assertTrue(DihedralTool.planes(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO).isEmpty())
    }

    @Test fun lockedViewerIgnoresCameraGestures() {
        val locked = InteractionReducer.reduce(InteractionState(), ViewerCommand.ToggleLock)
        val moved = InteractionReducer.reduce(locked, ViewerCommand.Orbit(20f, 10f))
        assertEquals(locked.session.camera, moved.session.camera)
    }

    @Test fun zoomIsClampedToLegacyRange() {
        val zoomedOut = InteractionReducer.reduce(InteractionState(), ViewerCommand.Zoom(0.0001f))
        val zoomedIn = InteractionReducer.reduce(zoomedOut, ViewerCommand.Zoom(1_000_000f))
        assertEquals(0.08, zoomedOut.session.camera.zoom)
        assertEquals(25.0, zoomedIn.session.camera.zoom)
    }

    @Test fun inspectionLockKeepsPersistentWindowAndClearsTransientWindow() {
        var state = InteractionReducer.reduce(InteractionState(), ViewerCommand.InspectAtom(12))
        state = InteractionReducer.reduce(state, ViewerCommand.ToggleInspectionLock(12, false))
        assertEquals(listOf(12L), state.document.inspection.lockedInspectedAtomIds)
        assertNull(state.document.inspection.inspectedAtomId)
    }
}
