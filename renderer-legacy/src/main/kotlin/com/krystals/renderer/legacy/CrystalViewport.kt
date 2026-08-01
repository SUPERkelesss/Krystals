package com.krystals.renderer.legacy

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.coordination.CoordinationAnalyzer
import com.krystals.crystal.analysis.polyhedron.PolyhedronHull
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.angleDegrees
import com.krystals.crystal.core.math.dihedralDegrees
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.math.eulerYX
import com.krystals.crystal.core.math.rotX
import com.krystals.crystal.core.math.rotY
import com.krystals.crystal.core.model.AtomImage
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.scene.CellFrameGeometry
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.toCameraDepthRange
import com.krystals.renderer.core.scene.visibleBounds
import com.krystals.renderer.core.style.SelectionColors
import com.krystals.renderer.core.style.backgroundColor
import com.krystals.interaction.state.InteractionReducer
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerCommand
import com.krystals.interaction.state.ViewerSessionState
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.selection.Picker
import com.krystals.interaction.selection.PickResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ProjectedAtom(val atom: AtomImage, val point: Offset, val depth: Double, val radius: Float)

private sealed interface Renderable {
    val depth: Double
    val depthLayer: Int get() = 0
}

private data class AtomRenderable(
    val atom: ProjectedAtom,
    val selected: Boolean,
    val lockedHighlight: Boolean = false,
) : Renderable {
    override val depth = atom.depth
    override val depthLayer = 2
}

private data class BondRenderable(val a: ProjectedAtom, val b: ProjectedAtom, val width: Float, val isHBond: Boolean = false) : Renderable {
    // Per v0.5.3a: sort by the bond's GEOMETRIC CENTRE depth (average of its two endpoints' z).
    // +Z is toward the viewer (larger z = closer). v0.5.2a used the nearest endpoint (maxOf), which
    // sorted a mostly-far bond as if fully near and let it paint over closer atoms — the opposite
    // of correct occlusion. The centre is the stable painter's-algorithm key for a convex segment.
    override val depth = (a.depth + b.depth) / 2.0
    override val depthLayer = 1
}

private fun splitBondRenderables(a: ProjectedAtom, b: ProjectedAtom, width: Float): List<BondRenderable> {
    val midpoint = Offset((a.point.x + b.point.x) / 2f, (a.point.y + b.point.y) / 2f)
    val midpointDepth = (a.depth + b.depth) / 2.0
    val midpointA = ProjectedAtom(a.atom, midpoint, midpointDepth, 0f)
    val midpointB = ProjectedAtom(b.atom, midpoint, midpointDepth, 0f)
    return listOf(BondRenderable(a, midpointA, width), BondRenderable(midpointB, b, width))
}

/**
 * A single polygonal face of a polyhedron, emitted as its own renderable so it sorts against atoms
 * and bonds by its own face depth (rather than the whole polyhedron sorting as one block by
 * its center, which let back faces occlude front atoms). [screenVerts] are the projected 2D vertices
 * in draw order; [faceDepth] is the average-vertex rotated-Z of the face (geometric centre depth;
 * larger z = closer to camera, +Z toward viewer); [normal] is the outward face normal in camera
 * space (after rotate) for screen-space lighting + back-face culling.
 */
private data class PolyhedronFaceRenderable(
    val baseColor: Color,
    val screenVerts: List<Offset>,
    val vertexIds: List<Long>,
    val faceDepth: Double,
    val normalCam: Vec3,
    // Per v0.3.44: when true the face is back-facing and only its outline is drawn (no fill), at
    // [outlineAlpha], so the polyhedron's back edges are still visible as faint lines.
    val outlineOnly: Boolean = false,
    val outlineAlpha: Float = 0.35f,
) : Renderable {
    override val depth = faceDepth
}

@Stable
@Deprecated("Use InteractionState with a renderer backend; retained for Canvas compatibility")
class ViewerController : Picker {
    private var session by mutableStateOf(ViewerSessionState())
    private var commandSink: ((ViewerCommand) -> Unit)? = null

    var rotation: Mat3
        get() = session.camera.rotation
        set(value) { session = session.copy(camera = session.camera.copy(rotation = value)) }
    var zoom: Float
        get() = session.camera.zoom.toFloat()
        set(value) { session = session.copy(camera = session.camera.copy(zoom = value.toDouble().coerceIn(0.08, 25.0))) }
    var panX: Float
        get() = session.camera.panX.toFloat()
        set(value) { session = session.copy(camera = session.camera.copy(panX = value.toDouble())) }
    var panY: Float
        get() = session.camera.panY.toFloat()
        set(value) { session = session.copy(camera = session.camera.copy(panY = value.toDouble())) }
    var locked: Boolean
        get() = session.locked
        set(value) {
            if (value != session.locked) dispatch(ViewerCommand.ToggleLock)
        }
    internal var projectedAtoms: List<ProjectedAtom> = emptyList()
    val viewportWidth: Int get() = session.viewportWidth
    val viewportHeight: Int get() = session.viewportHeight

    internal fun bind(value: ViewerSessionState, sink: (ViewerCommand) -> Unit) {
        session = value
        commandSink = sink
    }

    internal fun dispatch(command: ViewerCommand) {
        val sink = commandSink
        if (sink != null) {
            sink(command)
        } else {
            session = InteractionReducer.reduce(InteractionState(session = session), command).session
        }
    }

    /**
     * Apply a single-finger drag as a camera-local rotation increment. [dxPx]/[dyPx] are pixel
     * deltas. Both axes follow the finger: dragging right/down moves the object right/down.
     * Left-multiplying the increment keeps it in the camera frame, which is what makes a
     * tilted view still follow.
     */
    fun rotateByDrag(dxPx: Float, dyPx: Float, sensitivity: Float = 0.32f) {
        dispatch(ViewerCommand.Orbit(dxPx * sensitivity / 0.32f, dyPx * sensitivity / 0.32f))
    }

    fun zoomBy(factor: Float) = dispatch(ViewerCommand.Zoom(factor))

    fun panBy(dxPx: Float, dyPx: Float) = dispatch(ViewerCommand.Pan(dxPx, dyPx))

    internal fun setViewport(width: Int, height: Int) = dispatch(ViewerCommand.SetViewport(width, height))

    fun align(axis: Char) {
        dispatch(ViewerCommand.AlignCartesian(axis))
    }

    fun alignCellAxis(axis: Char, lattice: Lattice) {
        dispatch(ViewerCommand.AlignCellAxis(axis, lattice))
    }

    internal fun pick(position: Offset): AtomImage? = projectedAtoms
        .asSequence()
        .filter { (it.point - position).getDistance() <= max(22f, it.radius * 1.35f) }
        .minByOrNull { (it.point - position).getDistance() - it.depth.toFloat() * 0.0001f }
        ?.atom

    override suspend fun pick(x: Float, y: Float): PickResult? = pick(Offset(x, y))?.let { atom ->
        PickResult(objectId = "atom:${atom.id}", atomId = atom.id, siteId = atom.siteId)
    }
}

@Composable
fun rememberViewerController() = remember { ViewerController() }

private data class TapEvent(val time: Long, val position: Offset)

/** Per v0.8.1: mutable draw-frame outputs that are only read by the gesture handler. Plain fields
 *  (NOT Compose state) — writing them every frame inside the Canvas draw lambda must not schedule a
 *  recomposition cascade. */
private class TapHitState {
    var lastTap: TapEvent? = null
    var measurementBounds = emptyList<Triple<Rect, Boolean, Int>>()
    var atomInfoBounds = emptyList<Triple<Rect, Boolean, Long>>()
}

@Composable
@Deprecated("Use the Filament backend; retained as Canvas-Legacy fallback")
fun CrystalViewport(
    scene: RenderScene,
    interactionState: InteractionState,
    onCommand: (ViewerCommand) -> Unit,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    modifier: Modifier = Modifier,
    bondValenceBySite: Map<String, Double> = emptyMap(),
    onAtomTap: (AtomImage) -> Boolean = { false },
) {
    val controller = rememberViewerController()
    SideEffect { controller.bind(interactionState.session, onCommand) }
    val document = interactionState.document
    CrystalViewport(
        scene = scene,
        appearance = appearance,
        renderConfiguration = renderConfiguration,
        modifier = modifier,
        controller = controller,
        visibility = document.visibility,
        selectedAtomIds = document.selection.selectedAtomIds,
        measurementMode = document.measurementMode,
        lockedMeasurements = document.lockedMeasurements,
        onMeasurementLockToggle = { measurement, locked ->
            onCommand(ViewerCommand.ToggleMeasurementLock(measurement, locked))
        },
        inspectedAtomId = document.inspection.inspectedAtomId,
        lockedInspectedAtomIds = document.inspection.lockedInspectedAtomIds,
        bondValenceBySite = bondValenceBySite,
        onInspectAtom = { onCommand(ViewerCommand.InspectAtom(it.id)) },
        onInspectionLockToggle = { atomId, locked ->
            onCommand(ViewerCommand.ToggleInspectionLock(atomId, locked))
        },
        onAtomTap = { atom -> if (!onAtomTap(atom)) onCommand(ViewerCommand.SelectAtom(atom.id, atom.siteId)) },
        onBlankTap = { onCommand(ViewerCommand.ClearTransientViewerState) },
    )
}

private data class DihedralPlaneRenderable(
    val screenVerts: List<Offset>,
    val normalCam: Vec3,
    override val depth: Double,
) : Renderable

@Composable
@Deprecated("Use the RenderScene + InteractionState overload")
fun CrystalViewport(
    snapshot: BondNetwork,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    modifier: Modifier = Modifier,
    controller: ViewerController = rememberViewerController(),
    visibility: ViewerVisibility = ViewerVisibility(),
    selectedAtomIds: List<Long> = emptyList(),
    measurementMode: MeasurementMode = MeasurementMode.NONE,
    lockedMeasurements: List<LockedMeasurement> = emptyList(),
    onMeasurementLockToggle: (measurement: LockedMeasurement?, isLocked: Boolean) -> Unit = { _, _ -> },
    inspectedAtomId: Long? = null,
    lockedInspectedAtomIds: List<Long> = emptyList(),
    bondValenceBySite: Map<String, Double> = emptyMap(),
    onInspectAtom: (AtomImage) -> Unit = {},
    onInspectionLockToggle: (atomId: Long, isLocked: Boolean) -> Unit = { _, _ -> },
    onAtomTap: (AtomImage) -> Unit = {},
    onViewMoved: () -> Unit = {},
    onBlankTap: () -> Unit = {},
) {
    val scene = remember(snapshot, appearance, renderConfiguration, visibility) {
        LegacyRenderSceneAdapter.build(snapshot, appearance, renderConfiguration, visibility)
    }
    CrystalViewport(
        scene = scene,
        appearance = appearance,
        renderConfiguration = renderConfiguration,
        modifier = modifier,
        controller = controller,
        visibility = visibility,
        selectedAtomIds = selectedAtomIds,
        measurementMode = measurementMode,
        lockedMeasurements = lockedMeasurements,
        onMeasurementLockToggle = onMeasurementLockToggle,
        inspectedAtomId = inspectedAtomId,
        lockedInspectedAtomIds = lockedInspectedAtomIds,
        bondValenceBySite = bondValenceBySite,
        onInspectAtom = onInspectAtom,
        onInspectionLockToggle = onInspectionLockToggle,
        onAtomTap = onAtomTap,
        onViewMoved = onViewMoved,
        onBlankTap = onBlankTap,
    )
}

@Composable
@Deprecated("Use the RenderScene + InteractionState overload")
fun CrystalViewport(
    scene: RenderScene,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    modifier: Modifier = Modifier,
    controller: ViewerController = rememberViewerController(),
    visibility: ViewerVisibility = ViewerVisibility(),
    selectedAtomIds: List<Long> = emptyList(),
    measurementMode: MeasurementMode = MeasurementMode.NONE,
    lockedMeasurements: List<LockedMeasurement> = emptyList(),
    onMeasurementLockToggle: (measurement: LockedMeasurement?, isLocked: Boolean) -> Unit = { _, _ -> },
    inspectedAtomId: Long? = null,
    lockedInspectedAtomIds: List<Long> = emptyList(),
    bondValenceBySite: Map<String, Double> = emptyMap(),
    onInspectAtom: (AtomImage) -> Unit = {},
    onInspectionLockToggle: (atomId: Long, isLocked: Boolean) -> Unit = { _, _ -> },
    onAtomTap: (AtomImage) -> Unit = {},
    onViewMoved: () -> Unit = {},
    onBlankTap: () -> Unit = {},
) {
    val snapshot = remember(scene) { LegacyRenderSceneAdapter.toBondNetwork(scene) }
    LegacyCanvasViewport(
        snapshot = snapshot,
        scene = scene,
        appearance = appearance,
        renderConfiguration = renderConfiguration,
        modifier = modifier,
        controller = controller,
        visibility = visibility,
        selectedAtomIds = selectedAtomIds,
        measurementMode = measurementMode,
        lockedMeasurements = lockedMeasurements,
        onMeasurementLockToggle = onMeasurementLockToggle,
        inspectedAtomId = inspectedAtomId,
        lockedInspectedAtomIds = lockedInspectedAtomIds,
        bondValenceBySite = bondValenceBySite,
        onInspectAtom = onInspectAtom,
        onInspectionLockToggle = onInspectionLockToggle,
        onAtomTap = onAtomTap,
        onViewMoved = onViewMoved,
        onBlankTap = onBlankTap,
    )
}

@Composable
private fun LegacyCanvasViewport(
    snapshot: BondNetwork,
    scene: RenderScene? = null,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    modifier: Modifier = Modifier,
    controller: ViewerController = rememberViewerController(),
    visibility: ViewerVisibility = ViewerVisibility(),
    selectedAtomIds: List<Long> = emptyList(),
    measurementMode: MeasurementMode = MeasurementMode.NONE,
    lockedMeasurements: List<LockedMeasurement> = emptyList(),
    onMeasurementLockToggle: (measurement: LockedMeasurement?, isLocked: Boolean) -> Unit = { _, _ -> },
    inspectedAtomId: Long? = null,
    lockedInspectedAtomIds: List<Long> = emptyList(),
    bondValenceBySite: Map<String, Double> = emptyMap(),
    onInspectAtom: (AtomImage) -> Unit = {},
    onInspectionLockToggle: (atomId: Long, isLocked: Boolean) -> Unit = { _, _ -> },
    onAtomTap: (AtomImage) -> Unit = {},
    onViewMoved: () -> Unit = {},
    onBlankTap: () -> Unit = {},
) {
    val background = colorFromArgb(backgroundColor(appearance.backgroundArgb).srgbArgb)
    // Per v0.8.1: draw-frame outputs live in a plain (non-Compose-state) holder so per-frame writes
    // inside the Canvas draw lambda do not schedule recompositions.
    val hitState = remember { TapHitState() }
    // Per v0.8.1: reflection parameters depend only on lightIntensity/diffusion (change on slider
    // moves, not per frame) — compute once here instead of once per atom/bond/polyhedron-face.
    val reflection = remember(appearance.lightIntensity, appearance.diffusion) {
        legacyReflectionParameters(appearance.lightIntensity, appearance.diffusion)
    }
    // Per v0.7.1: cache rotation-independent data outside the Canvas draw lambda so it is
    // NOT recomputed every frame during drag-rotation of large cells.
    // — coordination neighbors: O(bonds) per frame → now O(1)
    // — bounding center: O(atoms) per frame → now O(1)
    // — atom-by-id lookup: O(atoms) per frame → now O(1)
    val coordination = remember(snapshot, visibility.showBonds, visibility.hiddenBondPairs) {
        CoordinationAnalyzer.neighbors(snapshot, visibility.showBonds, visibility.hiddenBondPairs)
    }
    val center = remember(snapshot) {
        boundingCenter(snapshot.atoms.map { it.cartesianCoordinate.toVec3() })
    }
    val atomsById = remember(snapshot) { snapshot.atoms.associateBy { it.id } }
    // Per v0.7.1: cache visibleExternalShellAtomIds — depends only on bonds/visibility, NOT rotation.
    val visibleExternalShellAtomIds = remember(snapshot, visibility.showBonds, visibility.hiddenBondPairs, visibility.hiddenSites) {
        val ids = mutableSetOf<Long>()
        if (visibility.showBonds) {
            val atomById = snapshot.atoms.associateBy { it.id }
            snapshot.bonds.forEach { bond ->
                if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                val a = atomById[bond.atomA] ?: return@forEach
                val b = atomById[bond.atomB] ?: return@forEach
                if (b.isExternalShell && bond.rule.shouldExtendAcrossCell(a.siteId, true) && b.siteId !in visibility.hiddenSites) {
                    ids += b.id
                }
            }
        }
        ids
    }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width != controller.viewportWidth || size.height != controller.viewportHeight) {
                    controller.setViewport(size.width, size.height)
                }
            }
            .pointerInput(snapshot, controller.locked, lockedMeasurements, lockedInspectedAtomIds) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val down = first.position
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val movement = event.changes.sumOf { it.position.minus(it.previousPosition).getDistance().toDouble() }
                        if (movement > 2.0) {
                            moved = true
                        }
                        var handled = false
                        if (!controller.locked) {
                            if (pressed.size == 1) {
                                val delta = pressed.first().position - pressed.first().previousPosition
                                if (delta.getDistance() > 0f) {
                                    controller.rotateByDrag(-delta.x, -delta.y)
                                    onViewMoved()
                                    handled = true
                                }
                            } else if (pressed.size >= 2) {
                                val zoomDelta = event.calculateZoom()
                                val pan = event.calculatePan()
                                controller.zoomBy(zoomDelta)
                                controller.panBy(pan.x, pan.y)
                                if (abs(zoomDelta - 1f) > 0.001f || pan.getDistance() > 0.5f) onViewMoved()
                                handled = true
                            }
                        }
                        // Per v0.7.1: only consume events we actually handled (rotation/zoom/pan).
                        // Unconditionally consuming ALL events (including UP/CANCEL) breaks the
                        // system velocity tracker on Huawei devices, triggering the
                        // getSplineFlingDurationByReflection exception.
                        if (handled) {
                            event.changes.forEach { it.consume() }
                        }
                        if (pressed.isEmpty()) {
                            if (!moved) {
                                // Per v0.3.0: a tap on any measurement or info box triggers the
                                // toggle; the box hit carries its identity + locked state so the host
                                // knows which window was tapped.
                                val tapInMeasurementBox = hitState.measurementBounds.firstOrNull { it.first.contains(down) }
                                val tapInAtomInfoBox = hitState.atomInfoBounds.firstOrNull { it.first.contains(down) }
                                when {
                                    tapInMeasurementBox != null -> {
                                        val idx = tapInMeasurementBox.third
                                        val measurement = if (idx >= 0) lockedMeasurements.getOrNull(idx) else null
                                        onMeasurementLockToggle(measurement, tapInMeasurementBox.second)
                                    }
                                    tapInAtomInfoBox != null -> onInspectionLockToggle(tapInAtomInfoBox.third, tapInAtomInfoBox.second)
                                    else -> controller.pick(down)?.let { atom ->
                                        val prev = hitState.lastTap
                                        val now = SystemClock.uptimeMillis()
                                        hitState.lastTap = TapEvent(now, down)
                                        if (prev != null && now - prev.time < 300 && (down - prev.position).getDistance() < 24f) {
                                            onInspectAtom(atom)
                                        } else {
                                            onAtomTap(atom)
                                        }
                                    } ?: onBlankTap()
                                }
                            }
                            break
                        }
                    }
                }
            },
    ) {
        drawRect(background)
        hitState.measurementBounds = emptyList()
        hitState.atomInfoBounds = emptyList()
        if (snapshot.atoms.isEmpty()) {
            // Per v0.3.42: no longer draw a "No atoms" message; just leave the background.
            controller.projectedAtoms = emptyList()
            return@Canvas
        }

        // Per v0.7.1: center and coordination are now cached outside the draw lambda.
        // Per v0.3.0: project ALL atoms (not just visible) so bonds/polyhedra survive hiding an
        // atom — the bond endpoint / polyhedron vertex lookup (byId) needs the hidden atoms too.
        val rotated = snapshot.atoms.associateWith { controller.rotation * (it.cartesianCoordinate.toVec3() - center) }
        val fullRotated = rotated.values.toList()
        val extentX = fullRotated.maxOf { it.x } - fullRotated.minOf { it.x }
        val extentY = fullRotated.maxOf { it.y } - fullRotated.minOf { it.y }
        val baseScale = min(size.width / max(1.0, extentX).toFloat(), size.height / max(1.0, extentY).toFloat()) * 0.72f
        val scale = baseScale * controller.zoom
        fun project(value: Vec3) = Offset(
            size.width / 2f + controller.panX + value.x.toFloat() * scale,
            size.height / 2f + controller.panY - value.y.toFloat() * scale,
        )

        drawCellFrames(snapshot, appearance, center, controller, scale, ::project, scene?.structuralExpansion ?: false)
        if (appearance.showAxes) drawAxes(snapshot, appearance, controller)

        val projected = snapshot.atoms.map { atom ->
            val v = rotated.getValue(atom)
            val radius = (RenderPalette.defaultRadius(atom.species.symbol).toFloat() * scale).coerceIn(9f, 84f)
            ProjectedAtom(atom, project(v), legacyCameraDepth(v), radius)
        }
        val byId = projected.associateBy { it.atom.id }

        // Per v0.7.1: visibleExternalShellAtomIds is now cached outside the draw lambda.
        // Primary atoms and boundary images are always visible unless hidden by site; external shell
        // atoms are visible only when referenced by an extending cross-cell bond.
        val visibleProjected = projected.filter {
            (!it.atom.isShell && it.atom.siteId !in visibility.hiddenSites) ||
                (it.atom.isBoundaryImage && it.atom.siteId !in visibility.hiddenSites) ||
                (it.atom.isExternalShell && it.atom.id in visibleExternalShellAtomIds)
        }
        if (visibleProjected.isEmpty()) {
            controller.projectedAtoms = emptyList()
        } else {
            controller.projectedAtoms = visibleProjected
        }

        // Per v0.7.1: coordination and atomsById are now cached outside the draw lambda.
        val lockedHighlightIds = lockedMeasurements.flatMap { it.atomIds }.toSet() + lockedInspectedAtomIds
        val highlightedIds = selectedAtomIds.toSet() + lockedHighlightIds + listOfNotNull(inspectedAtomId)
        val dihedralSelections = lockedMeasurements.filter { it.mode == MeasurementMode.DIHEDRAL } +
            if (measurementMode == MeasurementMode.DIHEDRAL && selectedAtomIds.size >= 4) {
                listOf(LockedMeasurement(selectedAtomIds.takeLast(4), MeasurementMode.DIHEDRAL))
            } else emptyList()
        val dihedralPlanes = dihedralSelections.flatMap { measurement ->
            val positions = measurement.atomIds.takeLast(4).mapNotNull { atomsById[it]?.cartesianCoordinate?.toVec3() }
            if (positions.size != 4) return@flatMap emptyList()
            DihedralTool.planes(positions[0], positions[1], positions[2], positions[3]).map { plane ->
                val rotatedVertices = plane.vertices.map { controller.rotation * (it - center) }
                DihedralPlaneRenderable(
                    screenVerts = rotatedVertices.map(::project),
                    normalCam = controller.rotation * plane.normal,
                    depth = rotatedVertices.map(::legacyCameraDepth).average(),
                )
            }
        }
        val renderables = buildList<Renderable> {
            addAll(dihedralPlanes)
            visibleProjected.forEach { add(AtomRenderable(it, it.atom.id in highlightedIds, it.atom.id in lockedHighlightIds)) }
            if (visibility.showBonds) {
                snapshot.bonds.forEach { bond ->
                    val a = byId[bond.atomA] ?: return@forEach
                    val b = byId[bond.atomB] ?: return@forEach
                    if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                    val externalBond = b.atom.isExternalShell
                    if (externalBond && !bond.rule.shouldExtendAcrossCell(a.atom.siteId, true)) return@forEach
                    val width = (appearance.bondRadius * scale * 0.65f).coerceIn(3f, 32f)
                    // Per v0.8.1: H-bonds are a single gray dotted line (no split-cylinder).
                    if (bond.rule.isHBond) {
                        add(BondRenderable(a, b, width, isHBond = true))
                    } else {
                        addAll(splitBondRenderables(a, b, width))
                    }
                }
            }
            if (visibility.polyhedronSites.isNotEmpty()) {
                // Polyhedra use the full (unfiltered) neighbor set so hiding a bond or a ligand atom
                // does not dissolve the polyhedron — visibility is decoupled per the v0.3.0 fix.
                // Per v0.3.42: boundary images are visible atoms too, so they also act as polyhedron
                // centres (a corner/edge/face atom's coordination was previously dropped because it
                // is marked isShell). External-shell atoms remain excluded (they are not real centres).
                projected.filter {
                    (!it.atom.isShell || it.atom.isBoundaryImage) && it.atom.siteId in visibility.polyhedronSites
                }.forEach { center ->
                    val vertices = coordination[center.atom.id].orEmpty().mapNotNull { byId[it.id] }
                    if (vertices.size >= 3) {
                        // Per v0.3.0: emit each face as its own renderable so faces sort against atoms
                        // by their own depth (fixes back faces occluding front atoms) and get screen-
                        // space lighting. Per v0.3.2: back faces are always culled (not just when
                        // nearly opaque) so translucent polyhedra don't have back faces paint over
                        // front atoms.
                        val baseArgb = RenderPalette.resolveSiteArgb(
                            center.atom.siteId,
                            center.atom.species.symbol,
                            renderConfiguration,
                        )
                        val baseColor = colorFromArgb(baseArgb).copy(alpha = appearance.polyhedronOpacity.coerceIn(0f, 1f))
                        polyhedronFaceRenderables(center, vertices, baseColor, controller.rotation).forEach { add(it) }
                    }
                }
            }
        }.legacyBackToFront(depthOf = { it.depth }, layerOf = { it.depthLayer })

        // Depth normalization comes from the visible atom bounding box, so the cueing scale
        // adapts to the actual content extent. When a RenderScene is available, use the shared
        // bounding-box helper; fall back to per-atom depths for the deprecated BondNetwork path.
        val depthRange = scene?.visibleBounds()
            ?.toCameraDepthRange(Camera(rotation = controller.rotation))
            ?.let { LegacyDepthRange(it.far, it.near) }
            ?: rotated.filter { it.key.siteId !in visibility.hiddenSites }
                .values.map { legacyCameraDepth(it) }
                .let { if (it.size >= 2) LegacyDepthRange(it.min(), it.max()) else null }
        val bgColor = colorFromArgb(appearance.backgroundArgb)
        fun dofFog(depth: Double): Float {
            if (!appearance.depthOfFieldEnabled) return 0f
            return legacyDepthCueFog(depth, depthRange, appearance.dofNear, appearance.dofFar)
        }

        // Per v0.5.4: contact shadows removed (user request). The world light no longer casts a
        // simulated ground shadow — only atom/bond/polyhedron shading remains.

        renderables.forEach { renderable ->
            when (renderable) {
                // Per v0.5.3a: depth cueing blends each object's colour toward the background by its
                // fog amount; opacity is unchanged. Bonds split at the midpoint so each half fades by
                // its endpoint atom's depth (continuous fade into the atoms).
                is AtomRenderable -> drawAtom(renderable.atom, renderable.selected, renderable.lockedHighlight, appearance, renderConfiguration, dofFog(renderable.depth), bgColor, reflection)
                is BondRenderable -> drawBond(renderable.a, renderable.b, renderable.width, renderable.isHBond, appearance, renderConfiguration, visibility.hiddenSites, ::dofFog, bgColor, reflection)
                is PolyhedronFaceRenderable -> drawPolyhedronFace(renderable, appearance, dofFog(renderable.depth), bgColor, reflection)
                is DihedralPlaneRenderable -> drawDihedralPlane(renderable, appearance, dofFog(renderable.depth), bgColor)
            }
        }
        // Per v0.2.3: draw the locked (persistent) measurement/info windows first, then the active
        // (unlocked) ones, so both can coexist. Each returns its Rect + a "locked" flag + an index
        // (locked measurements by position, -1 for the active one) for hit-testing.
        val bounds = mutableListOf<Triple<Rect, Boolean, Int>>()
        lockedMeasurements.forEachIndexed { idx, m ->
            drawMeasurement(projected, m.atomIds, m.mode, true)?.let { bounds += Triple(it, true, idx) }
        }
        drawMeasurement(projected, selectedAtomIds, measurementMode, false)?.let { bounds += Triple(it, false, -1) }
        hitState.measurementBounds = bounds
        val infoBounds = mutableListOf<Triple<Rect, Boolean, Long>>()
        lockedInspectedAtomIds.forEach { id ->
            drawAtomInfo(projected, id, true, appearance, bondValenceBySite)?.let { infoBounds += Triple(it, true, id) }
        }
        if (inspectedAtomId != null && inspectedAtomId !in lockedInspectedAtomIds) {
            drawAtomInfo(projected, inspectedAtomId, false, appearance, bondValenceBySite)?.let { infoBounds += Triple(it, false, inspectedAtomId) }
        }
        hitState.atomInfoBounds = infoBounds
        // Per v0.8.1: projectedAtoms was already set at line ~531-533 above; this duplicate
        // assignment (legacy dead write) is removed.
    }
}

private fun DrawScope.drawAtom(
    atom: ProjectedAtom,
    selected: Boolean,
    lockedHighlight: Boolean,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    fog: Float = 0f,
    bgColor: Color = Color.Black,
    reflection: LegacyReflectionParameters = legacyReflectionParameters(appearance.lightIntensity, appearance.diffusion),
) {
    // Per v0.6: depth cueing modulates OPACITY by (1 - fog); distant atoms become transparent.
    val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
    if (opacity < 0.01f) {
        if (selected) drawCircle(
            if (lockedHighlight) Color(SelectionColors.LOCKED_ARGB) else Color(SelectionColors.SELECTED_ARGB),
            atom.radius + 5f,
            atom.point,
            style = Stroke(if (lockedHighlight) 6f else 5f),
        )
        return
    }
    val rawBase = colorFromArgb(
        RenderPalette.resolveSiteArgb(atom.atom.siteId, atom.atom.species.symbol, renderConfiguration),
    )
    val depthAlpha = (1f - fog).coerceIn(0f, 1f)
    val base = rawBase.copy(alpha = opacity * depthAlpha)
    val dark = rawBase.darken(0.65f).copy(alpha = opacity * depthAlpha)
    val sphere = Brush.radialGradient(
        listOf(base, dark),
        center = atom.point,
        radius = atom.radius,
    )
    // Per v0.5.2b: partial-occupancy atoms render as a pie chart — the occ fraction is drawn solid,
    // the remainder at low alpha, both as screen-space wedges starting at the top (12 o'clock) going
    // clockwise. occ==1 keeps the fast full-circle path. The radial gradient's centre is the atom's
    // absolute screen point, so it spans both wedges seamlessly.
    val occ = atom.atom.occupancy.coerceIn(0.0, 1.0)
    if (occ >= 0.999) {
        drawCircle(sphere, atom.radius, atom.point)
    } else {
        val r = atom.radius
        val rect = Rect(atom.point.x - r, atom.point.y - r, atom.point.x + r, atom.point.y + r)
        val occSweep = (occ * 360.0).toFloat()
        fun wedgePath(start: Float, sweep: Float) = Path().apply {
            moveTo(atom.point.x, atom.point.y)
            arcTo(rect, start, sweep, false)
            close()
        }
        drawPath(wedgePath(-90f, occSweep), sphere)
        val faded = Brush.radialGradient(
            listOf(base.copy(alpha = opacity * 0.15f), dark.copy(alpha = opacity * 0.15f)),
            center = atom.point, radius = r,
        )
        drawPath(wedgePath(-90f + occSweep, 360f - occSweep), faded)
    }
    if (appearance.reflectionEnabled) {
        val highlightOffset = legacyHighlightOffset(
            atom.radius.toDouble(),
            appearance.lightAzimuth,
            appearance.lightElevation,
        )
        val highlightCenter = atom.point - Offset(highlightOffset.x.toFloat(), highlightOffset.y.toFloat())
        // Per v0.5.3a: highlight alpha dims with fog so distant atoms lose their sheen naturally.
        val highlight = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = reflection.highlightAlpha * opacity * (1f - fog)),
                0.45f to Color.White.copy(alpha = reflection.highlightMiddleAlpha * opacity * (1f - fog)),
                1f to Color.Transparent,
            ),
            center = highlightCenter,
            radius = atom.radius * reflection.radialRadiusMultiplier,
        )
        // Highlight only on the solid wedge for partial-occ atoms (looks natural; the faded wedge
        // stays matte). Full-occ atoms keep the full-circle highlight.
        if (occ >= 0.999) {
            drawCircle(highlight, atom.radius, atom.point)
        } else {
            val r = atom.radius
            val rect = Rect(atom.point.x - r, atom.point.y - r, atom.point.x + r, atom.point.y + r)
            val occSweep = (occ * 360.0).toFloat()
            val solidWedge = Path().apply {
                moveTo(atom.point.x, atom.point.y)
                arcTo(rect, -90f, occSweep, false)
                close()
            }
            drawPath(solidWedge, highlight)
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.28f * opacity), atom.radius, atom.point, style = Stroke(max(0.8f, atom.radius * 0.045f)))
    if (selected) drawCircle(
        if (lockedHighlight) Color(SelectionColors.LOCKED_ARGB) else Color(SelectionColors.SELECTED_ARGB),
        atom.radius + 4f,
        atom.point,
        style = Stroke(if (lockedHighlight) 6f else 5f),
    )
}

private fun DrawScope.drawDihedralPlane(
    plane: DihedralPlaneRenderable,
    appearance: ViewerAppearance,
    fog: Float,
    background: Color,
) {
    if (plane.screenVerts.size != 4) return
    val light = legacyLightDirection(appearance.lightAzimuth, appearance.lightElevation)
    val normal = if (plane.normalCam.z >= 0.0) plane.normalCam else plane.normalCam * -1.0
    val intensity = (0.58f + normal.normalized().dot(light).coerceIn(0.0, 1.0).toFloat() * appearance.lightIntensity * 0.42f)
        .coerceIn(0f, 1f)
    val depthAlpha = (1f - fog).coerceIn(0f, 1f)
    val shaded = Color(0xFF9966CC).copy(
        red = 0.60f * intensity,
        green = 0.40f * intensity,
        blue = 0.80f * intensity,
        alpha = 0.58f * depthAlpha,
    )
    val path = Path().apply {
        moveTo(plane.screenVerts[0].x, plane.screenVerts[0].y)
        plane.screenVerts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(
        path,
        Brush.linearGradient(
            listOf(shaded, shaded.copy(alpha = 0.35f), Color.Transparent),
            start = plane.screenVerts[0],
            end = plane.screenVerts[3],
        ),
    )
}

private fun DrawScope.drawBond(
    a: ProjectedAtom,
    b: ProjectedAtom,
    width: Float,
    isHBond: Boolean,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    hiddenSites: Set<String> = emptySet(),
    dofFog: (Double) -> Float,
    bgColor: Color = Color.Black,
    reflection: LegacyReflectionParameters = legacyReflectionParameters(appearance.lightIntensity, appearance.diffusion),
) {
    // Per v0.6: depth cueing modulates each half's OPACITY by (1 - fog); splitting at the
    // midpoint keeps the fade continuous into the atoms instead of a single mid-depth step.
    val opacity = appearance.bondOpacity.coerceIn(0f, 1f)
    if (opacity < 0.01f) return
    val fogA = dofFog(a.depth).coerceIn(0f, 1f)
    val fogB = dofFog(b.depth).coerceIn(0f, 1f)
    val delta = b.point - a.point
    val length = delta.getDistance()
    if (length < 0.001f) return
    val dir = delta / length
    // Per v0.3.2: a hidden atom's ball is not drawn, so a bond ending at one would float with a
    // gap (it started at the atom's surface, not its center). Extend the bond to the hidden atom's
    // center; visible atoms still start the bond at their surface.
    val aHidden = a.atom.siteId in hiddenSites
    val bHidden = b.atom.siteId in hiddenSites
    val start = a.point + dir * if (aHidden) 0f else a.radius
    val end = b.point - dir * if (bHidden) 0f else b.radius
    val clipped = end - start
    if (clipped.getDistance() < 0.001f) return
    // Per v0.8.1: H-bonds are drawn as a single gray dotted line instead of split cylinders.
    if (isHBond) {
        drawLine(Color.Gray.copy(alpha = 0.31f), start, end, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        return
    }
    val perp = Offset(-dir.y, dir.x)

    val light = legacyLightDirection(appearance.lightAzimuth, appearance.lightElevation)
    val lightOnPerp = (light.x.toFloat() * perp.x + light.y.toFloat() * perp.y).toDouble()
    val midpoint = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)

    if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
        // Unicolor: same colour, each half's opacity modulated by its endpoint's fog.
        val baseA = colorFromArgb(appearance.uniformBondArgb).copy(alpha = opacity * (1f - fogA))
        val baseB = colorFromArgb(appearance.uniformBondArgb).copy(alpha = opacity * (1f - fogB))
        drawBondCylinder(start, midpoint, width / 2f, perp, lightOnPerp, baseA, appearance.bondReflectionEnabled, appearance.lightIntensity, reflection)
        drawBondCylinder(midpoint, end, width / 2f, perp, lightOnPerp, baseB, appearance.bondReflectionEnabled, appearance.lightIntensity, reflection)
    } else {
        val baseA = colorFromArgb(
            RenderPalette.resolveSiteArgb(a.atom.siteId, a.atom.species.symbol, renderConfiguration),
        ).copy(alpha = opacity * (1f - fogA))
        val baseB = colorFromArgb(
            RenderPalette.resolveSiteArgb(b.atom.siteId, b.atom.species.symbol, renderConfiguration),
        ).copy(alpha = opacity * (1f - fogB))
        drawBondCylinder(start, midpoint, width / 2f, perp, lightOnPerp, baseA, appearance.bondReflectionEnabled, appearance.lightIntensity, reflection)
        drawBondCylinder(midpoint, end, width / 2f, perp, lightOnPerp, baseB, appearance.bondReflectionEnabled, appearance.lightIntensity, reflection)
    }
}

private fun DrawScope.drawBondCylinder(
    start: Offset,
    end: Offset,
    halfWidth: Float,
    perp: Offset,
    lightOnPerp: Double,
    base: Color,
    reflectionEnabled: Boolean,
    // Per v0.5.2: world-light intensity drives the bond's shadow contrast (previously fixed 0.45).
    lightIntensity: Float,
    reflection: LegacyReflectionParameters,
) {
    val offset = perp * halfWidth
    val p1 = start + offset
    val p2 = end + offset
    val p3 = end - offset
    val p4 = start - offset
    val path = Path().apply {
        moveTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        lineTo(p4.x, p4.y)
        close()
    }

    val center = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
    val shadowA = base.darken(1f - 0.45f * lightIntensity)
    val shadowB = base.darken(1f - 0.35f * lightIntensity)
    val highlight = if (reflectionEnabled) base.lighten(reflection.bondHighlightFactor) else base
    val band = reflection.bondHighlightBand
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to shadowA,
            (highlightPos - band).coerceIn(0.02f, 0.98f) to base,
            highlightPos to highlight,
            (highlightPos + band).coerceIn(0.02f, 0.98f) to base,
            1.0f to shadowB,
        ),
        start = center - perp * halfWidth,
        end = center + perp * halfWidth,
    )
    drawPath(path, brush)
}

private fun Color.darken(factor: Float) = Color(red * factor, green * factor, blue * factor, alpha)
private fun Color.lighten(factor: Float) = Color(
    red + (1f - red) * factor,
    green + (1f - green) * factor,
    blue + (1f - blue) * factor,
    alpha,
)
// Blend RGB toward [target] while preserving the source opacity.
private fun Color.blend(target: Color, t: Float) = Color(
    red + (target.red - red) * t,
    green + (target.green - green) * t,
    blue + (target.blue - blue) * t,
    alpha,
)

/**
 * Build per-face renderables for a coordination polyhedron. Each face is a polygon (coplanar
 * triangles merged by [PolyhedronHull]) and becomes its own [PolyhedronFaceRenderable] carrying a
 * camera-space outward normal — used both for back-face culling and screen-space Lambert shading
 * consistent with atoms/bonds. The view direction is +Z (camera looks down -Z), so a face is
 * back-facing when its camera-space normal has a non-positive Z component.
 */
private fun polyhedronFaceRenderables(
    center: ProjectedAtom,
    vertices: List<ProjectedAtom>,
    baseColor: Color,
    rotation: Mat3,
): List<PolyhedronFaceRenderable> {
    if (vertices.size < 3) return emptyList()
    val byCartesian = vertices.associateBy { it.atom.cartesianCoordinate.toVec3() }

    // Per v0.3.41: always use convexHullFaces so coplanar ligands produce a single correctly-ordered
    // polygon (triangle, quad, etc.) instead of being handled by the allMutuallyAdjacent shortcut.
    val hullPolygons: List<List<ProjectedAtom>> =
        PolyhedronHull.faces(
            center.atom.cartesianCoordinate.toVec3(),
            vertices.map { it.atom.cartesianCoordinate.toVec3() },
        )
            .mapNotNull { poly -> poly.map { p -> byCartesian[p] ?: return@mapNotNull null } }

    // For a flat coordination (e.g. trigonal-planar CO3 or square-planar) the polyhedron is really a
    // single polygon. Back-face culling would hide it when viewed from the centre-atom side, so emit
    // each face twice — once with the outward normal and once with the reversed normal.
    val allCoplanar = vertices.size >= 3 && run {
        val v0 = vertices[0].atom.cartesianCoordinate.toVec3()
        val n = (vertices[1].atom.cartesianCoordinate.toVec3() - v0)
            .cross(vertices[2].atom.cartesianCoordinate.toVec3() - v0)
        if (n.lengthSquared() < 1e-12) return@run false
        vertices.drop(3).all { abs(n.dot(it.atom.cartesianCoordinate.toVec3() - v0)) < 1e-6 }
    }

    val result = mutableListOf<PolyhedronFaceRenderable>()
    hullPolygons.forEach { faceVerts ->
        if (faceVerts.size < 3) return@forEach
        // Outward normal in world space, then rotate to camera space for culling + lighting.
        val va = faceVerts[0]; val vb = faceVerts[1]; val vc = faceVerts[2]
        val vaCartesian = va.atom.cartesianCoordinate.toVec3()
        var worldNormal = (vb.atom.cartesianCoordinate.toVec3() - vaCartesian)
            .cross(vc.atom.cartesianCoordinate.toVec3() - vaCartesian)
        val toCenter = center.atom.cartesianCoordinate.toVec3() - vaCartesian
        // Per v0.3.42: toCenter points from the face toward the centre atom, so the outward normal
        // (pointing away from the centre) must have a NEGATIVE dot with toCenter. The previous code
        // flipped when dot < 0, which turned outward into inward and culled the front face.
        if (worldNormal.dot(toCenter) > 0) worldNormal = worldNormal * -1.0
        val len = worldNormal.length()
        if (len < 1e-12) return@forEach
        val normal = worldNormal / len
        val camNormal = rotation * normal
        val screenVerts = faceVerts.map { it.point }
        // Per v0.5.3a: sort by the face's GEOMETRIC CENTRE depth (average vertex z). +Z is toward the
        // viewer (larger z = closer). v0.5.2a used the nearest vertex (maxOf), which sorted a large
        // face as if it were entirely at its near edge and let it occlude atoms in front of it. The
        // centre is the stable painter's-algorithm key for a convex face.
        val faceDepth = faceVerts.map { it.depth }.average()
        val vertexIds = faceVerts.map { it.atom.id }
        // Per v0.3.2: always cull back faces (camera looks down -Z).
        if (camNormal.z > 0.0) {
            result += PolyhedronFaceRenderable(baseColor, screenVerts, vertexIds, faceDepth, camNormal)
        } else {
            // Per v0.3.44: back face — emit an outline-only renderable so the polyhedron's back edges
            // are still visible as faint lines (no fill).
            result += PolyhedronFaceRenderable(baseColor, screenVerts, vertexIds, faceDepth, camNormal, outlineOnly = true, outlineAlpha = 0.18f)
        }
        // Per v0.3.41: for flat coordinations, also emit the reversed face so the polygon is visible
        // from the centre-atom side. Rendered after (so it paints over) with lower alpha to look like
        // a translucent back side.
        if (allCoplanar) {
            val reversedCamNormal = rotation * (normal * -1.0)
            if (reversedCamNormal.z > 0.0) {
                val backColor = baseColor.copy(alpha = (baseColor.alpha * 0.4f).coerceIn(0f, 1f))
                result += PolyhedronFaceRenderable(backColor, screenVerts.reversed(), vertexIds.reversed(), faceDepth, reversedCamNormal)
            }
        }
    }
    return result
}

private fun DrawScope.drawPolyhedronFace(face: PolyhedronFaceRenderable, appearance: ViewerAppearance, fog: Float = 0f, bgColor: Color = Color.Black, reflection: LegacyReflectionParameters = legacyReflectionParameters(appearance.lightIntensity, appearance.diffusion)) {
    if (face.screenVerts.size < 3) return
    // Per v0.3.44: back faces are emitted outline-only — skip the fill and just draw the edges at
    // a lower alpha so the polyhedron's back silhouette is faintly visible.
    if (!face.outlineOnly) {
        // Per v0.6: depth cueing modulates alpha by (1 - fog).
        val fadedBase = face.baseColor.copy(alpha = face.baseColor.alpha * (1f - fog))
        val fill = if (appearance.polyhedronReflectionEnabled) {
            // Per v0.5.4: Blinn-Phong plastic shading. Lambert diffuse + a white specular highlight
            // via the half vector (light+view, view=(0,0,1) since +Z is toward the viewer). The whole
            // face shares one normal, so the specular term is uniform across the face — a flat
            // reflective film look (plastic) rather than a per-pixel sheen. Shininess≈48 (plastic).
            val light = legacyLightDirection(appearance.lightAzimuth, appearance.lightElevation)
            val view = Vec3(0.0, 0.0, 1.0)
            val half = (light + view).normalized()
            val diff = face.normalCam.dot(light).coerceIn(0.0, 1.0)
            val spec = Math.pow(face.normalCam.dot(half).coerceIn(0.0, 1.0), 48.0)
            val ambient = (1f - 0.6f * appearance.lightIntensity).coerceIn(0.4f, 1f)
            val diffFactor = (ambient + (1f - ambient) * diff.toFloat()).coerceIn(0f, 1f)
            val specAmount = (spec.toFloat() * reflection.polyhedronSpecularFactor).coerceIn(0f, 1f)
            fadedBase.copy(
                red = (fadedBase.red * diffFactor + specAmount).coerceIn(0f, 1f),
                green = (fadedBase.green * diffFactor + specAmount).coerceIn(0f, 1f),
                blue = (fadedBase.blue * diffFactor + specAmount).coerceIn(0f, 1f),
            )
        } else fadedBase
        val path = Path().apply {
            moveTo(face.screenVerts[0].x, face.screenVerts[0].y)
            for (i in 1 until face.screenVerts.size) lineTo(face.screenVerts[i].x, face.screenVerts[i].y)
            close()
        }
        drawPath(path, fill)
    }
    // Outline polygon edges, deduplicated across adjacent faces via the caller's shared set is not
    // possible here (per-face), so dedupe within the face by sorted endpoint pair.
    val outlineColor = Color.White.copy(alpha = face.outlineAlpha)
    val drawn = mutableSetOf<List<Long>>()
    for (i in face.screenVerts.indices) {
        val a = face.vertexIds[i]
        val b = face.vertexIds[(i + 1) % face.vertexIds.size]
        if (drawn.add(listOf(a, b).sorted())) {
            drawLine(outlineColor, face.screenVerts[i], face.screenVerts[(i + 1) % face.screenVerts.size], 1.2f)
        }
    }
}

private fun DrawScope.drawMeasurement(
    projected: List<ProjectedAtom>,
    ids: List<Long>,
    mode: MeasurementMode,
    locked: Boolean,
): Rect? {
    val expected = when (mode) { MeasurementMode.LENGTH -> 2; MeasurementMode.ANGLE -> 3; MeasurementMode.DIHEDRAL -> 4; else -> 0 }
    if (expected == 0 || ids.size < expected) return null
    val points = ids.takeLast(expected).mapNotNull { id -> projected.firstOrNull { it.atom.id == id } }
    if (points.size != expected) return null
    for (index in 0 until points.lastIndex) drawLine(Color(0xFFE1C6FF), points[index].point, points[index + 1].point, 2.5f)
    val label = when (mode) {
        MeasurementMode.LENGTH -> "%.4f Å".format(distance(points[0].atom.cartesianCoordinate.toVec3(), points[1].atom.cartesianCoordinate.toVec3()))
        MeasurementMode.ANGLE -> "%.3f°".format(angleDegrees(points[0].atom.cartesianCoordinate.toVec3(), points[1].atom.cartesianCoordinate.toVec3(), points[2].atom.cartesianCoordinate.toVec3()))
        MeasurementMode.DIHEDRAL -> "%.3f°".format(dihedralDegrees(points[0].atom.cartesianCoordinate.toVec3(), points[1].atom.cartesianCoordinate.toVec3(), points[2].atom.cartesianCoordinate.toVec3(), points[3].atom.cartesianCoordinate.toVec3()))
        else -> return null
    }
    val anchor = points.map { it.point }.reduce { a, b -> a + b } / points.size.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 48f
        setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
    }
    val bounds = android.graphics.Rect()
    paint.getTextBounds(label, 0, label.length, bounds)
    val pad = 16f
    val boxLeft = anchor.x + 12f - pad
    val boxTop = anchor.y - 12f - bounds.height() - pad
    val boxRight = anchor.x + 12f + bounds.width() + pad
    val boxBottom = anchor.y - 12f + pad
    val boxColor = if (locked) Color(0xFF9966CC).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
    drawRoundRect(boxColor, topLeft = Offset(boxLeft, boxTop), size = androidx.compose.ui.geometry.Size(boxRight - boxLeft, boxBottom - boxTop), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f))
    drawContext.canvas.nativeCanvas.drawText(label, anchor.x + 12f, anchor.y - 12f, paint)
    return Rect(Offset(boxLeft, boxTop), Offset(boxRight, boxBottom))
}

private fun DrawScope.drawAtomInfo(
    projected: List<ProjectedAtom>,
    inspectedAtomId: Long?,
    locked: Boolean,
    appearance: ViewerAppearance,
    bondValenceBySite: Map<String, Double> = emptyMap(),
): Rect? {
    val atom = inspectedAtomId?.let { id -> projected.firstOrNull { it.atom.id == id } } ?: return null
    // Per v0.5.0: append the atom's bond-valence sum (s = X.XX) after occupancy when available.
    val bvs = bondValenceBySite[atom.atom.siteId]
    val bvsText = bvs?.let { "  s = %.2f".format(it) } ?: ""
    val fractional = atom.atom.fractionalCoordinate
    val label = "${atom.atom.species.symbol}  ${atom.atom.siteLabel}  occ ${atom.atom.occupancy}$bvsText\n(${fractional.x.formatFract()}, ${fractional.y.formatFract()}, ${fractional.z.formatFract()})"
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 40f
        setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
    }
    val lines = label.split('\n')
    val widths = lines.map { line -> paint.measureText(line) }
    val maxWidth = widths.maxOrNull() ?: 0f
    val lineHeight = paint.fontMetrics.run { descent - ascent }
    val pad = 16f
    val boxLeft = atom.point.x + atom.radius + 14f
    val boxTop = atom.point.y - atom.radius - 14f - lines.size * lineHeight - pad
    val boxRight = boxLeft + maxWidth + pad * 2
    val boxBottom = atom.point.y - atom.radius - 14f + pad
    val boxColor = if (locked) Color(0xFF9966CC).copy(alpha = 0.82f) else Color.Black.copy(alpha = 0.65f)
    drawRoundRect(boxColor, topLeft = Offset(boxLeft, boxTop), size = androidx.compose.ui.geometry.Size(boxRight - boxLeft, boxBottom - boxTop), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f))
    lines.forEachIndexed { index, line ->
        drawContext.canvas.nativeCanvas.drawText(line, boxLeft + pad, boxTop + pad + (index + 1) * lineHeight - paint.fontMetrics.descent, paint)
    }
    return Rect(Offset(boxLeft, boxTop), Offset(boxRight, boxBottom))
}

private fun Double.formatFract() = "%.4f".format(this)

private fun DrawScope.drawAxes(
    snapshot: BondNetwork,
    appearance: ViewerAppearance,
    controller: ViewerController,
) {
    // Three unit direction vectors, in the same world space the atoms live in.
    val directions: List<Vec3> = when (appearance.axisMode) {
        AxisMode.ABC -> listOf(snapshot.structure.lattice.matrix.a, snapshot.structure.lattice.matrix.b, snapshot.structure.lattice.matrix.c)
        AxisMode.XYZ -> listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
    }
    val labels = when (appearance.axisMode) {
        AxisMode.ABC -> listOf("a", "b", "c")
        AxisMode.XYZ -> listOf("X", "Y", "Z")
    }
    val colors = listOf(Color(0xFFE57373), Color(0xFF81C784), Color(0xFF64B5F6))
    val origin = Offset(size.width * appearance.axisOffsetX + 28f, size.height * appearance.axisOffsetY + 40f - 75f)
    val arrowLen = 75f
    val headLenBase = 14f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; textSize = 30f; setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
    }
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(122, 0, 0, 0); strokeWidth = 7f; strokeCap = Paint.Cap.ROUND
    }
    val rotatedDirs = directions.mapIndexed { index, dir ->
        val rotated = (controller.rotation * dir).normalized()
        Triple(index, dir, rotated)
    }
    fun drawArrow(index: Int, rotated: Vec3) {
        // Per v0.6.3: arrow length varies with projected direction (3D perspective).
        val dx = rotated.x.toFloat()
        val dy = -rotated.y.toFloat()
        val projectedLength = kotlin.math.sqrt(dx * dx + dy * dy)
        val visibleLen = arrowLen * projectedLength
        val unit = if (projectedLength > 0.0001f) Offset(dx / projectedLength, dy / projectedLength) else Offset(0f, 0f)
        val tip = origin + unit * visibleLen
        val headLen = headLenBase
        val color = colors[index]
        val perp = Offset(-unit.y, unit.x)
        val headBase = tip - unit * headLen
        val headHalf = headLen * 0.6f
        // Simple 2D shaft with shadow.
        drawContext.canvas.nativeCanvas.drawLine(origin.x, origin.y, headBase.x, headBase.y, shadowPaint)
        paint.color = color.toArgb()
        drawContext.canvas.nativeCanvas.drawLine(origin.x, origin.y, headBase.x, headBase.y, paint)
        // Filled arrowhead.
        val arrowPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(headBase.x + perp.x * headHalf, headBase.y + perp.y * headHalf)
            lineTo(headBase.x - perp.x * headHalf, headBase.y - perp.y * headHalf)
            close()
        }
        drawPath(arrowPath, color)
        paint.color = color.toArgb()
        drawContext.canvas.nativeCanvas.drawText(labels[index], tip.x + 4f, tip.y - 4f, paint)
    }
    // Draw back arrows (pointing away from viewer) first.
    rotatedDirs.filter { it.third.z <= 0.0 }.forEach { (index, _, rotated) ->
        drawArrow(index, rotated)
    }
    // Center hub with radial gradient (matching filament overlay).
    val hubRadius = 12f
    val light = legacyLightDirection(appearance.lightAzimuth, appearance.lightElevation)
    val highlightX = (origin.x + light.x * (-hubRadius * 0.375)).toFloat()
    val highlightY = (origin.y + light.y * (-hubRadius * 0.375)).toFloat()
    drawCircle(Color.Black.copy(alpha = 0.5f), hubRadius + 1f, origin + Offset(1f, 1f))
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFE0E0E0), Color(0xFF68686F)),
            center = Offset(highlightX, highlightY),
            radius = hubRadius,
        ),
        hubRadius,
        origin,
    )
    // Draw front arrows (pointing toward viewer) on top of the sphere.
    rotatedDirs.filter { it.third.z > 0.0 }.forEach { (index, _, rotated) ->
        drawArrow(index, rotated)
    }
}

private fun DrawScope.drawCellFrames(
    snapshot: BondNetwork,
    appearance: ViewerAppearance,
    center: Vec3,
    controller: ViewerController,
    scale: Float,
    project: (Vec3) -> Offset,
    structuralExpansion: Boolean = false,
) {
    if (appearance.frameMode == FrameMode.NONE) return
    val effect = if (appearance.lineStyle == LineStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
    val edges = CellFrameGeometry.edges(snapshot.structure.lattice, snapshot.expansion, appearance.frameMode, structuralExpansion)
    edges.forEach { (start, end) ->
        val a = project(controller.rotation * (start - center))
        val b = project(controller.rotation * (end - center))
        drawLine(Color.Gray.copy(alpha = 0.72f), a, b, 1.4f, pathEffect = effect)
    }
}

private fun boundingCenter(points: List<Vec3>): Vec3 = Vec3(
    (points.minOf { it.x } + points.maxOf { it.x }) / 2,
    (points.minOf { it.y } + points.maxOf { it.y }) / 2,
    (points.minOf { it.z } + points.maxOf { it.z }) / 2,
)

private fun colorFromArgb(argb: Long) = Color(argb)
