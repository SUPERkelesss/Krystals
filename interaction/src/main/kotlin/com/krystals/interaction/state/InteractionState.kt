package com.krystals.interaction.state

import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.eulerYX
import com.krystals.renderer.core.camera.Camera
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.measure.MeasurementSelection

data class ViewerSessionState(
    val camera: Camera = Camera(rotation = eulerYX(-28.0, 22.0)),
    val locked: Boolean = false,
    val viewportWidth: Int = 1080,
    val viewportHeight: Int = 1080,
)

data class SelectionState(
    val selectedAtomIds: List<Long> = emptyList(),
)

data class InspectionState(
    val inspectedAtomId: Long? = null,
    val lockedInspectedAtomIds: List<Long> = emptyList(),
)

data class VisibilityState(
    val hiddenSites: Set<String> = emptySet(),
    val hiddenBondPairs: Set<String> = emptySet(),
    val polyhedronSites: Set<String> = emptySet(),
    val showBonds: Boolean = true,
)

data class ViewerDocumentState(
    val selection: SelectionState = SelectionState(),
    val measurementMode: MeasurementMode = MeasurementMode.NONE,
    val lockedMeasurements: List<MeasurementSelection> = emptyList(),
    val inspection: InspectionState = InspectionState(),
    val visibility: VisibilityState = VisibilityState(),
)

data class InteractionState(
    val session: ViewerSessionState = ViewerSessionState(),
    val document: ViewerDocumentState = ViewerDocumentState(),
)
