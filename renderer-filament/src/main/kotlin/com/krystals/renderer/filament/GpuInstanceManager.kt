package com.krystals.renderer.filament

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.krystals.renderer.core.primitive.MeshInstance
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.scene.CellFrameGeometry
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.scene.allBounds
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.state.InteractionState
import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.style.FrameMode
import com.krystals.renderer.core.style.LineStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val FRAME_RADIUS_FACTOR = 0.002
private const val FRAME_CLIP_MIN_LENGTH = 1e-6

/** Materializes InstanceManager's stable diff as automatically-instanced Filament entities. */
class GpuInstanceManager(
    private val engine: Engine,
    private val scene: Scene,
    private val meshes: MeshUploader,
    private val materials: MaterialFactory,
) : AutoCloseable {
    private val entities = linkedMapOf<String, Int>()
    private val objectByEntity = linkedMapOf<Int, String>()
    private val entityLock = Any()
    private val ownedMeshByEntity = linkedMapOf<Int, UploadedMesh>()
    private val auxiliaryIds = linkedSetOf<String>()
    private val sceneAuxiliaryIds = linkedSetOf<String>()
    private val auxiliaryMeshes = linkedMapOf<String, MeshData>()
    private val polyhedronBatchIds = linkedSetOf<String>()
    private val polyhedronOutlineIds = linkedSetOf<String>()
    private val model = InstanceManager()
    private var lastDocumentState: com.krystals.interaction.state.ViewerDocumentState? = null
    private var lastSnapshot: RenderScene? = null
    private var atomsById: Map<Long, AtomInstance> = emptyMap()

    init {
        engine.isAutomaticInstancingEnabled = true
    }

    val drawCallCount: Int get() = model.drawCallCount
    val instanceCount: Int get() = model.instanceCount

    fun sync(snapshot: RenderScene) {
        if (lastSnapshot === snapshot) return
        atomsById = snapshot.atoms.associateBy { it.atom.id }
        val diff = model.sync(snapshot)
        (diff.removed + diff.updated).forEach(::destroy)
        val records = diff.batches.values.flatten().associateBy { it.objectId }
        (diff.added + diff.updated).forEach { id ->
            val record = records[id] ?: return@forEach
            if (record.batch.geometry == GeometryKind.POLYHEDRON) return@forEach
            create(record, snapshot)?.let { entity ->
                entities[id] = entity
                synchronized(entityLock) { objectByEntity[entity] = id.substringBeforeLast(":a").substringBeforeLast(":b") }
            }
        }
        polyhedronBatchIds.toList().asReversed().forEach(::destroy)
        polyhedronBatchIds.clear()
        polyhedronOutlineIds.toList().asReversed().forEach(::destroy)
        polyhedronOutlineIds.clear()
        sceneAuxiliaryIds.toList().asReversed().forEach(::destroy)
        sceneAuxiliaryIds.clear()
        meshes.mergePolyhedra(snapshot.meshes).forEachIndexed { index, merged ->
            val id = "polyhedron-batch:$index"
            auxiliaryMeshes[id] = merged.mesh
            val record = InstanceRecord(id, 0, BatchKey(GeometryKind.POLYHEDRON, MaterialKey(merged.material)), identity())
            create(record, snapshot)?.let { entity ->
                entities[id] = entity
                synchronized(entityLock) { objectByEntity[entity] = merged.objectIds.firstOrNull().orEmpty() }
                polyhedronBatchIds += id
            }
        }
        polyhedronEdges(snapshot.meshes).forEachIndexed { index, (start, end) ->
            val id = "polyhedron-outline:$index"
            val record = InstanceRecord(
                id,
                0,
                BatchKey(
                    GeometryKind.CYLINDER,
                    MaterialKey(Material(0xFFFFFFFF, opacity = 0.25, reflective = false)),
                ),
                cylinderTransform(start, end, 0.012),
            )
            create(record, snapshot)?.let { entity ->
                entities[id] = entity
                polyhedronOutlineIds += id
            }
        }
        addFrameAndAxes(snapshot)
        lastDocumentState = null
        lastSnapshot = snapshot
    }

    fun objectIdForEntity(entity: Int): String? = synchronized(entityLock) { objectByEntity[entity] }

    fun updateInteraction(snapshot: RenderScene, state: InteractionState) {
        if (lastDocumentState == state.document) return
        lastDocumentState = state.document
        auxiliaryIds.toList().asReversed().forEach(::destroy)
        auxiliaryIds.clear()
        auxiliaryMeshes.keys.removeAll { it.startsWith("aux:") }
        // P8: 3D highlight spheres removed — selection rings are now drawn as 2D overlays
        // in FilamentLegacyStyleOverlay (ViewerBackendHost.kt), matching Legacy's approach.

        val measurements = state.document.lockedMeasurements + if (
            state.document.measurementMode != MeasurementMode.NONE && state.document.selection.selectedAtomIds.size > 1
        ) listOf(com.krystals.interaction.measure.MeasurementSelection(state.document.selection.selectedAtomIds, state.document.measurementMode)) else emptyList()
        measurements.forEachIndexed { measurementIndex, measurement ->
            val expected = when (measurement.mode) {
                MeasurementMode.LENGTH -> 2
                MeasurementMode.ANGLE -> 3
                MeasurementMode.DIHEDRAL -> 4
                else -> 0
            }
            val points = if (expected > 0) {
                measurement.atomIds.takeLast(expected).mapNotNull { atomsById[it]?.atom?.cartesianCoordinate?.toVec3() }
            } else {
                emptyList()
            }
            points.zipWithNext().forEachIndexed { segmentIndex, (start, end) ->
                addAuxiliary(
                    InstanceRecord(
                        "aux:measurement:$measurementIndex:$segmentIndex", 0,
                        BatchKey(GeometryKind.MEASUREMENT, MaterialKey(Material(0xFFE6D6FF, reflective = false))),
                        cylinderTransform(start, end, 0.035),
                    ), snapshot,
                )
            }
            // Per v0.6: dihedral planes are now drawn as gradient canvas overlays in
            // FilamentRenderer.composeOverlay (matching the Legacy renderer) instead of solid 3D meshes.
        }
    }

    fun clear() {
        entities.keys.toList().asReversed().forEach(::destroy)
        model.clear()
        auxiliaryIds.clear()
        sceneAuxiliaryIds.clear()
        auxiliaryMeshes.clear()
        polyhedronBatchIds.clear()
        polyhedronOutlineIds.clear()
        lastDocumentState = null
        lastSnapshot = null
        atomsById = emptyMap()
    }

    override fun close() = clear()

    private fun create(record: InstanceRecord, snapshot: RenderScene): Int? {
        val uploaded = when (record.batch.geometry) {
            GeometryKind.SPHERE_HIGH, GeometryKind.HIGHLIGHT -> meshes.sharedSphere(SphereLod.HIGH)
            GeometryKind.SPHERE_MEDIUM -> meshes.sharedSphere(SphereLod.MEDIUM)
            GeometryKind.SPHERE_LOW -> meshes.sharedSphere(SphereLod.LOW)
            GeometryKind.CYLINDER -> meshes.sharedCylinder()
            GeometryKind.POLYHEDRON -> {
                val mesh = auxiliaryMeshes[record.objectId]
                    ?: snapshot.meshes.firstOrNull { it.id == record.objectId }?.toMeshData()
                    ?: return null
                meshes.upload(mesh)
            }
            GeometryKind.FRAME, GeometryKind.AXIS, GeometryKind.MEASUREMENT -> meshes.sharedCylinder()
        }
        val materialKind = materialKindFor(record.batch.geometry, record.batch.material)
        val material = materials.create(materialKind, record.batch.material, snapshot.environment) ?: return null
        val entity = EntityManager.get().create()
        val bounds = uploaded.bounds
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, uploaded.vertexBuffer, uploaded.indexBuffer, 0, uploaded.indexCount)
            .material(0, material)
            .boundingBox(
                Box(
                    bounds.centerX,
                    bounds.centerY,
                    bounds.centerZ,
                    bounds.halfExtentX.coerceAtLeast(0.001f),
                    bounds.halfExtentY.coerceAtLeast(0.001f),
                    bounds.halfExtentZ.coerceAtLeast(0.001f),
                ),
            )
            .culling(true)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        val transform = engine.transformManager.create(entity)
        engine.transformManager.setTransform(transform, record.transform)
        scene.addEntity(entity)
        if (record.batch.geometry == GeometryKind.POLYHEDRON) ownedMeshByEntity[entity] = uploaded
        return entity
    }

    private fun addFrameAndAxes(snapshot: RenderScene) {
        val edges = CellFrameGeometry.edges(snapshot.structure.lattice, snapshot.expansion, snapshot.environment.frame.mode)
        if (edges.isEmpty()) return
        // Scale the frame radius with the scene so its on-screen thickness stays comparable
        // to the legacy renderer's ~1.4 px stroke across different structures.
        val radius = (snapshot.allBounds()?.radius ?: snapshot.structure.lattice.a) * FRAME_RADIUS_FACTOR
        val visibleAtoms = snapshot.atoms.filter { it.visible }
        val clippedSegments = edges.flatMap { clipSegmentBySpheres(it, visibleAtoms) }
        val renderedSegments = if (snapshot.environment.frame.lineStyle == LineStyle.DASHED) clippedSegments.flatMap(::dash) else clippedSegments
        renderedSegments.forEachIndexed { index, (start, end) ->
            addSceneAuxiliary(
                InstanceRecord(
                    "aux:frame:$index", 0,
                    BatchKey(GeometryKind.FRAME, MaterialKey(Material(0xFFA0A0AA, reflective = false))),
                    cylinderTransform(start, end, radius),
                ), snapshot,
            )
        }
    }

    private fun polyhedronEdges(meshInstances: List<MeshInstance>): List<Pair<Vec3, Vec3>> {
        val edges = linkedMapOf<String, Pair<Vec3, Vec3>>()
        meshInstances.asSequence().filter(MeshInstance::visible).forEach { mesh ->
            mesh.vertices.indices.forEach { index ->
                val next = (index + 1) % mesh.vertices.size
                val key = if (mesh.vertexAtomIds.size == mesh.vertices.size) {
                    val first = mesh.vertexAtomIds[index]
                    val second = mesh.vertexAtomIds[next]
                    "${mesh.sourceAtomId}:${minOf(first, second)}:${maxOf(first, second)}"
                } else {
                    val first = mesh.vertices[index]
                    val second = mesh.vertices[next]
                    val ends = listOf(first, second).sortedWith(compareBy<Vec3>({ it.x }, { it.y }, { it.z }))
                    "${mesh.sourceAtomId}:${ends[0]}:${ends[1]}"
                }
                edges.putIfAbsent(key, mesh.vertices[index] to mesh.vertices[next])
            }
        }
        return edges.values.toList()
    }

    private fun addAuxiliary(record: InstanceRecord, snapshot: RenderScene) {
        create(record, snapshot)?.let { entity ->
            entities[record.objectId] = entity
            auxiliaryIds += record.objectId
        }
    }

    private fun addSceneAuxiliary(record: InstanceRecord, snapshot: RenderScene) {
        create(record, snapshot)?.let { entity ->
            entities[record.objectId] = entity
            sceneAuxiliaryIds += record.objectId
        }
    }

    private fun dash(segment: Pair<Vec3, Vec3>): List<Pair<Vec3, Vec3>> = buildList {
        repeat(8) { index ->
            if (index % 2 == 0) {
                val delta = segment.second - segment.first
                add(segment.first + delta * (index / 8.0) to segment.first + delta * ((index + 1.0) / 8.0))
            }
        }
    }

    private fun clipSegmentBySpheres(
        segment: Pair<Vec3, Vec3>,
        atoms: List<AtomInstance>,
    ): List<Pair<Vec3, Vec3>> {
        var segments = listOf(segment)
        atoms.forEach { atom ->
            val center = atom.atom.cartesianCoordinate.toVec3()
            segments = segments.flatMap { clipSegmentBySphere(it, center, atom.radius) }
        }
        return segments.filter { (a, b) -> (b - a).length() > FRAME_CLIP_MIN_LENGTH }
    }

    private fun clipSegmentBySphere(
        segment: Pair<Vec3, Vec3>,
        center: Vec3,
        radius: Double,
    ): List<Pair<Vec3, Vec3>> {
        val (a, b) = segment
        val ab = b - a
        val length = ab.length()
        if (length < FRAME_CLIP_MIN_LENGTH) return listOf(segment)
        val dir = ab / length
        val ac = a - center
        val bCoef = 2.0 * dir.dot(ac)
        val cCoef = ac.dot(ac) - radius * radius
        val discriminant = bCoef * bCoef - 4.0 * cCoef
        if (discriminant <= 0.0) return listOf(segment)
        val sqrtDisc = sqrt(discriminant)
        val tEnter = (-bCoef - sqrtDisc) / 2.0
        val tExit = (-bCoef + sqrtDisc) / 2.0
        // The sphere is entirely before or after the segment.
        if (tExit < 0.0 || tEnter > length) return listOf(segment)
        val enterRatio = (tEnter / length).coerceIn(0.0, 1.0)
        val exitRatio = (tExit / length).coerceIn(0.0, 1.0)
        return buildList {
            if (enterRatio > 0.0) add(a to (a + ab * enterRatio))
            if (exitRatio < 1.0) add((a + ab * exitRatio) to b)
        }
    }

    private fun sphereTransform(center: Vec3, radius: Double) = floatArrayOf(
        radius.toFloat(), 0f, 0f, 0f, 0f, radius.toFloat(), 0f, 0f, 0f, 0f, radius.toFloat(), 0f,
        center.x.toFloat(), center.y.toFloat(), center.z.toFloat(), 1f,
    )

    private fun cylinderTransform(start: Vec3, end: Vec3, radius: Double): FloatArray {
        val delta = end - start
        val length = delta.length()
        val y = if (length < 1e-12) Vec3(0.0, 1.0, 0.0) else delta / length
        val helper = if (kotlin.math.abs(y.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val x = helper.cross(y).normalized()
        val z = x.cross(y).normalized()
        return floatArrayOf(
            (x.x * radius).toFloat(), (x.y * radius).toFloat(), (x.z * radius).toFloat(), 0f,
            (y.x * length).toFloat(), (y.y * length).toFloat(), (y.z * length).toFloat(), 0f,
            (z.x * radius).toFloat(), (z.y * radius).toFloat(), (z.z * radius).toFloat(), 0f,
            start.x.toFloat(), start.y.toFloat(), start.z.toFloat(), 1f,
        )
    }

    private fun identity() = sphereTransform(Vec3.ZERO, 1.0)

    private fun destroy(id: String) {
        val entity = entities.remove(id) ?: return
        synchronized(entityLock) { objectByEntity.remove(entity) }
        ownedMeshByEntity.remove(entity)?.let(meshes::destroy)
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
    }

    private fun MeshInstance.toMeshData(): MeshData {
        val positions = FloatArray(vertices.size * 3)
        val normals = FloatArray(vertices.size * 3)
        vertices.forEachIndexed { index, vertex ->
            positions[index * 3] = vertex.x.toFloat(); positions[index * 3 + 1] = vertex.y.toFloat(); positions[index * 3 + 2] = vertex.z.toFloat()
            normals[index * 3] = normal.x.toFloat(); normals[index * 3 + 1] = normal.y.toFloat(); normals[index * 3 + 2] = normal.z.toFloat()
        }
        return MeshData(positions, normals, triangleIndices.toIntArray())
    }
}

internal fun materialKindFor(geometry: GeometryKind, material: MaterialKey): MaterialKind = when {
    geometry == GeometryKind.HIGHLIGHT -> MaterialKind.HIGHLIGHT
    geometry == GeometryKind.SPHERE_HIGH && material.transparent -> MaterialKind.ATOM_TRANSPARENT
    geometry == GeometryKind.SPHERE_MEDIUM && material.transparent -> MaterialKind.ATOM_TRANSPARENT
    geometry == GeometryKind.SPHERE_LOW && material.transparent -> MaterialKind.ATOM_TRANSPARENT
    geometry == GeometryKind.SPHERE_HIGH -> MaterialKind.ATOM_OPAQUE
    geometry == GeometryKind.SPHERE_MEDIUM -> MaterialKind.ATOM_OPAQUE
    geometry == GeometryKind.SPHERE_LOW -> MaterialKind.ATOM_OPAQUE
    geometry == GeometryKind.POLYHEDRON && material.reflective -> MaterialKind.POLYHEDRON
    geometry == GeometryKind.POLYHEDRON -> MaterialKind.UNLIT_POLYHEDRON
    material.transparent && material.reflective -> MaterialKind.TRANSPARENT
    material.transparent -> MaterialKind.UNLIT_TRANSPARENT
    material.reflective -> MaterialKind.OPAQUE
    else -> MaterialKind.UNLIT_OPAQUE
}
