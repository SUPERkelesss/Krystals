package com.krystals.crystal.renderer

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MeasurementMode { NONE, LENGTH, ANGLE, DIHEDRAL }

/** A measurement (length / angle / dihedral) pinned on screen until dismissed. */
data class LockedMeasurement(val atomIds: List<Long>, val mode: MeasurementMode)

data class ViewerVisibility(
    val hiddenSites: Set<String> = emptySet(),
    val hiddenBondPairs: Set<String> = emptySet(),
    val polyhedronSites: Set<String> = emptySet(),
    val showBonds: Boolean = true,
)

internal data class ProjectedAtom(val atom: AtomImage, val point: Offset, val depth: Double, val radius: Float)

private sealed interface Renderable {
    val depth: Double
}

private data class AtomRenderable(val atom: ProjectedAtom, val selected: Boolean) : Renderable {
    override val depth = atom.depth
}

private data class BondRenderable(val a: ProjectedAtom, val b: ProjectedAtom, val width: Float) : Renderable {
    // Per v0.5.3a: sort by the bond's GEOMETRIC CENTRE depth (average of its two endpoints' z).
    // +Z is toward the viewer (larger z = closer). v0.5.2a used the nearest endpoint (maxOf), which
    // sorted a mostly-far bond as if fully near and let it paint over closer atoms — the opposite
    // of correct occlusion. The centre is the stable painter's-algorithm key for a convex segment.
    override val depth = (a.depth + b.depth) / 2.0
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
class ViewerController {
    // Per v0.5.1: orientation is a rotation matrix (world -> camera) instead of two accumulated
    // Euler scalars. Drag deltas are applied as increments about the CAMERA's local axes (left
    // multiply), so once the view is pitched/rolled the horizontal/vertical drags keep tracking
    // the screen axes instead of the world axes — no more "skewed rotation after tilting".
    var rotation: Mat3 by mutableStateOf(eulerYX(-28.0, 22.0))
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var locked by mutableStateOf(false)
    internal var projectedAtoms: List<ProjectedAtom> = emptyList()
    var viewportWidth: Int = 1080
        internal set
    var viewportHeight: Int = 1080
        internal set

    /**
     * Apply a single-finger drag as a camera-local rotation increment. [dxPx]/[dyPx] are pixel
     * deltas. Both axes follow the finger: dragging right/down moves the object right/down.
     * The negations compensate for the projection (`project` uses `-v.y*scale`, and the camera
     * looks down -Z), so the object tracks the finger on both axes. Left-multiplying the
     * increment keeps it in the camera frame, which is what makes a tilted view still follow.
     */
    fun rotateByDrag(dxPx: Float, dyPx: Float, sensitivity: Float = 0.32f) {
        val pitchInc = -dyPx * sensitivity
        val yawInc = -dxPx * sensitivity
        val increment = rotX(pitchInc.toDouble()) * rotY(yawInc.toDouble())
        rotation = (increment * rotation).orthonormalized()
    }

    fun align(axis: Char) {
        val (yaw, pitch) = when (axis.lowercaseChar()) {
            'x' -> -90f to 0f
            'y' -> 0f to 90f
            else -> 0f to 0f
        }
        rotation = eulerYX(yaw.toDouble(), pitch.toDouble())
        panX = 0f
        panY = 0f
    }

    fun alignCellAxis(axis: Char, lattice: Lattice) {
        val v = when (axis.lowercaseChar()) {
            'a' -> lattice.matrix.a
            'b' -> lattice.matrix.b
            else -> lattice.matrix.c
        }
        val pitchRad = atan2(v.y, v.z)
        val zPlane = v.y * sin(pitchRad) + v.z * cos(pitchRad)
        val yawRad = atan2(-v.x, zPlane)
        rotation = eulerYX(Math.toDegrees(yawRad.toDouble()), Math.toDegrees(pitchRad.toDouble()))
        panX = 0f
        panY = 0f
    }

    internal fun pick(position: Offset): AtomImage? = projectedAtoms
        .asSequence()
        .filter { (it.point - position).getDistance() <= max(22f, it.radius * 1.35f) }
        .minByOrNull { (it.point - position).getDistance() - it.depth.toFloat() * 0.0001f }
        ?.atom
}

@Composable
fun rememberViewerController() = remember { ViewerController() }

private data class TapEvent(val time: Long, val position: Offset)

@Composable
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
) {
    val background = colorFromArgb(appearance.backgroundArgb)
    var lastTap by remember { mutableStateOf<TapEvent?>(null) }
    var measurementBoundsList by remember { mutableStateOf<List<Triple<Rect, Boolean, Int>>>(emptyList()) }
    var atomInfoBoundsList by remember { mutableStateOf<List<Triple<Rect, Boolean, Long>>>(emptyList()) }
    Canvas(
        modifier = modifier
            .fillMaxSize()
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
                        if (!controller.locked) {
                            if (pressed.size == 1) {
                                val delta = pressed.first().position - pressed.first().previousPosition
                                if (delta.getDistance() > 0f) {
                                    controller.rotateByDrag(delta.x, delta.y)
                                    onViewMoved()
                                }
                            } else if (pressed.size >= 2) {
                                val zoomDelta = event.calculateZoom()
                                val pan = event.calculatePan()
                                controller.zoom = (controller.zoom * zoomDelta).coerceIn(0.08f, 25f)
                                controller.panX += pan.x
                                controller.panY += pan.y
                                if (abs(zoomDelta - 1f) > 0.001f || pan.getDistance() > 0.5f) onViewMoved()
                            }
                        }
                        event.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) {
                            if (!moved) {
                                // Per v0.3.0: a tap on any measurement or info box triggers the
                                // toggle; the box hit carries its identity + locked state so the host
                                // knows which window was tapped.
                                val tapInMeasurementBox = measurementBoundsList.firstOrNull { it.first.contains(down) }
                                val tapInAtomInfoBox = atomInfoBoundsList.firstOrNull { it.first.contains(down) }
                                when {
                                    tapInMeasurementBox != null -> {
                                        val idx = tapInMeasurementBox.third
                                        val measurement = if (idx >= 0) lockedMeasurements.getOrNull(idx) else null
                                        onMeasurementLockToggle(measurement, tapInMeasurementBox.second)
                                    }
                                    tapInAtomInfoBox != null -> onInspectionLockToggle(tapInAtomInfoBox.third, tapInAtomInfoBox.second)
                                    else -> controller.pick(down)?.let { atom ->
                                        val prev = lastTap
                                        val now = SystemClock.uptimeMillis()
                                        lastTap = TapEvent(now, down)
                                        if (prev != null && now - prev.time < 300 && (down - prev.position).getDistance() < 24f) {
                                            onInspectAtom(atom)
                                        } else {
                                            onAtomTap(atom)
                                        }
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            },
    ) {
        drawRect(background)
        controller.viewportWidth = size.width.toInt().coerceAtLeast(1)
        controller.viewportHeight = size.height.toInt().coerceAtLeast(1)
        measurementBoundsList = emptyList()
        atomInfoBoundsList = emptyList()
        if (snapshot.atoms.isEmpty()) {
            // Per v0.3.42: no longer draw a "No atoms" message; just leave the background.
            controller.projectedAtoms = emptyList()
            return@Canvas
        }

        val center = boundingCenter(snapshot.atoms.map { it.cartesianCoordinate.toVec3() })
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

        drawCellFrames(snapshot, appearance, center, controller, scale, ::project)
        if (appearance.showAxes) drawAxes(snapshot, appearance, controller)

        val projected = snapshot.atoms.map { atom ->
            val v = rotated.getValue(atom)
            val radius = (RenderPalette.defaultRadius(atom.species.symbol).toFloat() * scale).coerceIn(4.5f, 42f)
            ProjectedAtom(atom, project(v), v.z, radius)
        }
        val byId = projected.associateBy { it.atom.id }

        // Per v0.3.41: boundary images (shell atoms on the primary box faces) are displayed by default
        // to complete the visible cell; only genuine external shell atoms remain hidden unless a bond
        // rule opts in to "extend across cell".
        val visibleExternalShellAtomIds = mutableSetOf<Long>()
        if (visibility.showBonds) {
            snapshot.bonds.forEach { bond ->
                if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                val b = byId[bond.atomB] ?: return@forEach
                if (b.atom.isExternalShell && bond.rule.extendAcrossCell && b.atom.siteId !in visibility.hiddenSites) {
                    visibleExternalShellAtomIds += b.atom.id
                }
            }
        }
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

        val coordination = CoordinationAnalyzer.neighbors(snapshot, visibility.showBonds, visibility.hiddenBondPairs)
        val renderables = buildList<Renderable> {
            visibleProjected.forEach { add(AtomRenderable(it, it.atom.id in selectedAtomIds)) }
            if (visibility.showBonds) {
                snapshot.bonds.forEach { bond ->
                    val a = byId[bond.atomA] ?: return@forEach
                    val b = byId[bond.atomB] ?: return@forEach
                    if (bond.rule.key in visibility.hiddenBondPairs) return@forEach
                    // Per v0.3.43: a bond to a boundary image is drawn by default (it completes the
                    // visible cell edges/faces); only a bond to a genuine external shell atom is gated
                    // on extendAcrossCell.
                    val externalBond = b.atom.isExternalShell
                    // Bond-line rendering: an external-shell bond only draws when its rule opts in via
                    // extendAcrossCell. Boundary-image bonds are drawn by default.
                    if (externalBond && !bond.rule.extendAcrossCell) return@forEach
                    val width = (appearance.bondRadius * scale * 0.65f).coerceIn(1.5f, 16f)
                    add(BondRenderable(a, b, width))
                    // An external-shell atom sits outside the primary cell, so its ball must be drawn
                    // too (primary/boundary atoms are drawn above).
                    if (externalBond && b.atom.id in visibleExternalShellAtomIds) {
                        add(AtomRenderable(b, b.atom.id in selectedAtomIds))
                    }
                }
            }
            if (appearance.polyhedronEnabled && visibility.polyhedronSites.isNotEmpty()) {
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
        }.sortedBy { it.depth }

        // Per v0.5.4: depth cueing fades COLOUR toward the background (not alpha). dofFog returns a
        // fog amount in 0..1 (0 = near / no fade, 1 = far / fully faded). Near/Far are signed scene
        // distances (1 unit = 1/6 of the depth span, so the NEAREST atom = +3, the FARTHEST = -3):
        // near=正(toward camera)、far=负(away), 0 = crystal centre. Convention: near >= far.
        val depthRange = run {
            val ds = visibleProjected.map { it.depth }
            if (ds.isEmpty()) null else (ds.min() to ds.max())
        }
        val bgColor = colorFromArgb(appearance.backgroundArgb)
        fun dofFog(depth: Double): Float {
            if (!appearance.depthOfFieldEnabled || depthRange == null) return 0f
            val (dMin, dMax) = depthRange
            val dSpan = (dMax - dMin).coerceAtLeast(1e-6)
            val centre = (dMin + dMax) / 2.0
            // d: 近(大z)=正、远(小z)=负;最近≈+3,最远≈-3。
            val d = ((depth - centre) / dSpan * 6.0).toFloat()
            val near = appearance.dofNear   // 正,近端不淡化阈值
            val far = appearance.dofFar     // 负,远端全淡化阈值
            if (near <= far) return if (d >= near) 0f else 1f
            if (d >= near) return 0f        // 近端不淡化
            if (d <= far) return 1f         // 远端全淡化
            return ((near - d) / (near - far)).coerceIn(0f, 1f)  // 中间线性(近→远,0→1)
        }

        // Per v0.5.4: contact shadows removed (user request). The world light no longer casts a
        // simulated ground shadow — only atom/bond/polyhedron shading remains.

        renderables.forEach { renderable ->
            when (renderable) {
                // Per v0.5.3a: depth cueing blends each object's colour toward the background by its
                // fog amount; opacity is unchanged. Bonds split at the midpoint so each half fades by
                // its endpoint atom's depth (continuous fade into the atoms).
                is AtomRenderable -> drawAtom(renderable.atom, renderable.selected, appearance, renderConfiguration, dofFog(renderable.depth), bgColor)
                is BondRenderable -> drawBond(renderable.a, renderable.b, renderable.width, appearance, renderConfiguration, visibility.hiddenSites, ::dofFog, bgColor)
                is PolyhedronFaceRenderable -> drawPolyhedronFace(renderable, appearance, dofFog(renderable.depth), bgColor)
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
        measurementBoundsList = bounds
        val infoBounds = mutableListOf<Triple<Rect, Boolean, Long>>()
        lockedInspectedAtomIds.forEach { id ->
            drawAtomInfo(projected, id, true, appearance, bondValenceBySite)?.let { infoBounds += Triple(it, true, id) }
        }
        if (inspectedAtomId != null && inspectedAtomId !in lockedInspectedAtomIds) {
            drawAtomInfo(projected, inspectedAtomId, false, appearance, bondValenceBySite)?.let { infoBounds += Triple(it, false, inspectedAtomId) }
        }
        atomInfoBoundsList = infoBounds
        controller.projectedAtoms = visibleProjected
    }
}

private fun DrawScope.drawAtom(
    atom: ProjectedAtom,
    selected: Boolean,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    fog: Float = 0f,
    bgColor: Color = Color.Black,
) {
    // Per v0.5.3a: depth cueing fades COLOUR toward the background by [fog] (0..1); opacity is
    // unchanged (atomOpacity only), so distant atoms dissolve into the bg rather than go transparent.
    val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
    if (opacity < 0.01f) {
        if (selected) drawCircle(Color(0xFF9966CC), atom.radius + 4f, atom.point, style = Stroke(3f))
        return
    }
    val rawBase = colorFromArgb(
        RenderPalette.resolveSiteArgb(atom.atom.siteId, atom.atom.species.symbol, renderConfiguration),
    )
    val base = rawBase.blend(bgColor, fog).copy(alpha = opacity)
    val dark = rawBase.darken(0.65f).blend(bgColor, fog).copy(alpha = opacity)
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
        val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
        // Per v0.5.4: highlight偏移沿光在屏幕平面的方向(light.xy 已含 cos(elevation) 因子),偏移量
        // = r*0.38。掠射(e=0)→light.xy 模长最大→偏移最大;正射(e=90)→light.xy=0→居中。与预览球一致。
        val highlightCenter = atom.point - Offset(light.x.toFloat(), light.y.toFloat()) * (atom.radius * 0.38f)
        // Per v0.5.3a: highlight alpha dims with fog so distant atoms lose their sheen naturally.
        val highlight = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = appearance.lightIntensity.coerceIn(0.05f, 1f) * opacity * (1f - fog)),
                Color.Transparent,
            ),
            center = highlightCenter,
            radius = atom.radius * (0.35f + 0.75f * appearance.diffusion),
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
    if (selected) drawCircle(Color(0xFF9966CC), atom.radius + 4f, atom.point, style = Stroke(3f))
}

private fun DrawScope.drawBond(
    a: ProjectedAtom,
    b: ProjectedAtom,
    width: Float,
    appearance: ViewerAppearance,
    renderConfiguration: RenderConfiguration,
    hiddenSites: Set<String> = emptySet(),
    dofFog: (Double) -> Float,
    bgColor: Color = Color.Black,
) {
    // Per v0.5.3a: depth cueing fades each half's COLOUR toward the background by its endpoint's
    // fog; opacity is unchanged (bondOpacity only). Splitting at the midpoint keeps the fade
    // continuous into the atoms instead of a single mid-depth step.
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
    val perp = Offset(-dir.y, dir.x)

    val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
    val lightOnPerp = (light.x.toFloat() * perp.x + light.y.toFloat() * perp.y).toDouble()
    val midpoint = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)

    if (appearance.bondColorMode == BondColorMode.UNICOLOR) {
        // Unicolor: same colour, each half faded by its endpoint's fog.
        val baseA = colorFromArgb(appearance.uniformBondArgb).blend(bgColor, fogA).copy(alpha = opacity)
        val baseB = colorFromArgb(appearance.uniformBondArgb).blend(bgColor, fogB).copy(alpha = opacity)
        drawBondCylinder(start, midpoint, width / 2f, perp, lightOnPerp, baseA, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
        drawBondCylinder(midpoint, end, width / 2f, perp, lightOnPerp, baseB, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
    } else {
        val baseA = colorFromArgb(
            RenderPalette.resolveSiteArgb(a.atom.siteId, a.atom.species.symbol, renderConfiguration),
        ).blend(bgColor, fogA).copy(alpha = opacity)
        val baseB = colorFromArgb(
            RenderPalette.resolveSiteArgb(b.atom.siteId, b.atom.species.symbol, renderConfiguration),
        ).blend(bgColor, fogB).copy(alpha = opacity)
        drawBondCylinder(start, midpoint, width / 2f, perp, lightOnPerp, baseA, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
        drawBondCylinder(midpoint, end, width / 2f, perp, lightOnPerp, baseB, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
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
    // Per v0.5.2: world-light intensity/diffusion now drive the bond's highlight brightness, shadow
    // contrast and highlight band width (previously fixed 0.55/0.55/0.45 and ±0.15).
    lightIntensity: Float,
    diffusion: Float,
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
    val highlight = if (reflectionEnabled) base.lighten(0.55f * lightIntensity) else base
    val band = 0.06f + 0.20f * diffusion
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
// Per v0.5.2: blend this colour toward [target] by [t] (0..1). Kept for the axis arrowhead
// gradient interpolation; depth cueing now uses pure alpha fade (no colour blend).
private fun Color.blend(target: Color, t: Float) = Color(
    red + (target.red - red) * t,
    green + (target.green - green) * t,
    blue + (target.blue - blue) * t,
    alpha,
)

/**
 * Per v0.5.2: azimuth/elevation (degrees) → unit light direction in screen space. X right, Y down,
 * +Z toward the viewer. elevation is clamped to 0..90 so the light stays at/above the horizon —
 * below-horizon angles are meaningless for a reflection highlight (cos is even) and counter-intuitive.
 * Shared by atom / bond / polyhedron shading so the three light consistently.
 */
private fun lightDirection(azimuthDeg: Float, elevationDeg: Float): Vec3 {
    val a = azimuthDeg / 180.0 * PI
    val e = (elevationDeg.coerceIn(0f, 90f)) / 180.0 * PI
    return Vec3(cos(a) * cos(e), sin(a) * cos(e), sin(e))
}

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

private fun DrawScope.drawPolyhedronFace(face: PolyhedronFaceRenderable, appearance: ViewerAppearance, fog: Float = 0f, bgColor: Color = Color.Black) {
    if (face.screenVerts.size < 3) return
    // Per v0.3.44: back faces are emitted outline-only — skip the fill and just draw the edges at
    // a lower alpha so the polyhedron's back silhouette is faintly visible.
    if (!face.outlineOnly) {
        // Per v0.5.3a: depth cueing fades COLOUR toward the background by [fog]; alpha (polyhedron
        // opacity) is unchanged.
        val fadedBase = face.baseColor.blend(bgColor, fog)
        val fill = if (appearance.polyhedronReflectionEnabled) {
            // Per v0.5.4: Blinn-Phong plastic shading. Lambert diffuse + a white specular highlight
            // via the half vector (light+view, view=(0,0,1) since +Z is toward the viewer). The whole
            // face shares one normal, so the specular term is uniform across the face — a flat
            // reflective film look (plastic) rather than a per-pixel sheen. Shininess≈48 (plastic).
            val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
            val view = Vec3(0.0, 0.0, 1.0)
            val half = (light + view).normalized()
            val diff = face.normalCam.dot(light).coerceIn(0.0, 1.0)
            val spec = Math.pow(face.normalCam.dot(half).coerceIn(0.0, 1.0), 48.0)
            val ambient = (1f - 0.6f * appearance.lightIntensity).coerceIn(0.4f, 1f)
            val diffFactor = (ambient + (1f - ambient) * diff.toFloat()).coerceIn(0f, 1f)
            val specAmount = (spec.toFloat() * appearance.lightIntensity * 0.6f).coerceIn(0f, 1f)
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
    val origin = Offset(size.width * 0.08f + 28f, size.height * 0.08f + 40f)
    val arrowLen = 56f
    val halfWidth = 3f
    val headLen = 16f
    val light = lightDirection(appearance.lightAzimuth, appearance.lightElevation)
    val lightX = light.x.toFloat()
    val lightY = light.y.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; textSize = 30f; setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
    }
    directions.forEachIndexed { index, dir ->
        val rotated = controller.rotation * dir
        // Normalize the projected direction so each arrow is the same screen length.
        val projected = Offset(rotated.x.toFloat(), -rotated.y.toFloat())
        val len = projected.getDistance()
        val unit = if (len > 0.0001f) projected / len else Offset(0f, 0f)
        val tip = origin + unit * arrowLen
        val color = colors[index]
        val perp = Offset(-unit.y, unit.x)
        val lightOnPerp = (lightX * perp.x + lightY * perp.y).toDouble()
        // 3D cylinder shaft (lit the same way bonds are) instead of a flat line.
        drawBondCylinder(origin, tip - unit * headLen, halfWidth, perp, lightOnPerp, color, appearance.bondReflectionEnabled, appearance.lightIntensity, appearance.diffusion)
        // Conical arrowhead, filled with the same lit gradient as the shaft.
        val headBase = tip - unit * headLen
        val headHalf = headLen * 0.6f
        val arrowPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(headBase.x + perp.x * headHalf, headBase.y + perp.y * headHalf)
            lineTo(headBase.x - perp.x * headHalf, headBase.y - perp.y * headHalf)
            close()
        }
        val center = headBase
        val highlightPos = (0.5 - lightOnPerp * 0.35).toFloat().coerceIn(0.1f, 0.9f)
        val shadowA = color.darken(1f - 0.45f * appearance.lightIntensity)
        val shadowB = color.darken(1f - 0.35f * appearance.lightIntensity)
        val highlight = if (appearance.bondReflectionEnabled) color.lighten(0.55f * appearance.lightIntensity) else color
        val band = 0.06f + 0.20f * appearance.diffusion
        val brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to shadowA,
                (highlightPos - band).coerceIn(0.02f, 0.98f) to color,
                highlightPos to highlight,
                (highlightPos + band).coerceIn(0.02f, 0.98f) to color,
                1.0f to shadowB,
            ),
            start = center - perp * headHalf,
            end = center + perp * headHalf,
        )
        drawPath(arrowPath, brush)
        paint.color = android.graphics.Color.WHITE
        drawContext.canvas.nativeCanvas.drawText(labels[index], tip.x + unit.x * 8f - 6f, tip.y + unit.y * 8f + 10f, paint)
    }
}

private fun DrawScope.drawCellFrames(
    snapshot: BondNetwork,
    appearance: ViewerAppearance,
    center: Vec3,
    controller: ViewerController,
    scale: Float,
    project: (Vec3) -> Offset,
) {
    if (appearance.frameMode == FrameMode.NONE) return
    val ex = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.x else 1
    val ey = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.y else 1
    val ez = if (appearance.frameMode == FrameMode.ALL_CELLS) snapshot.expansion.z else 1
    val effect = if (appearance.lineStyle == LineStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
    val edges = listOf(
        0 to 1, 0 to 2, 0 to 4, 1 to 3, 1 to 5, 2 to 3, 2 to 6, 3 to 7,
        4 to 5, 4 to 6, 5 to 7, 6 to 7,
    )
    for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
        val vertices = listOf(
            Vec3(ix.toDouble(), iy.toDouble(), iz.toDouble()), Vec3(ix + 1.0, iy.toDouble(), iz.toDouble()),
            Vec3(ix.toDouble(), iy + 1.0, iz.toDouble()), Vec3(ix + 1.0, iy + 1.0, iz.toDouble()),
            Vec3(ix.toDouble(), iy.toDouble(), iz + 1.0), Vec3(ix + 1.0, iy.toDouble(), iz + 1.0),
            Vec3(ix.toDouble(), iy + 1.0, iz + 1.0), Vec3(ix + 1.0, iy + 1.0, iz + 1.0),
        ).map { snapshot.structure.lattice.toCartesian(FractionalCoordinate.fromVec3(it)).toVec3() - center }
            .map { controller.rotation * it }
            .map(project)
        edges.forEach { (a, b) -> drawLine(Color.Gray.copy(alpha = 0.72f), vertices[a], vertices[b], 1.4f, pathEffect = effect) }
    }
}

private fun boundingCenter(points: List<Vec3>): Vec3 = Vec3(
    (points.minOf { it.x } + points.maxOf { it.x }) / 2,
    (points.minOf { it.y } + points.maxOf { it.y }) / 2,
    (points.minOf { it.z } + points.maxOf { it.z }) / 2,
)

private fun colorFromArgb(argb: Long) = Color(argb)
