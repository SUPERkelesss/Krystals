package com.krystals.renderer.filament

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.krystals.renderer.core.primitive.MeshInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.interaction.measure.DihedralTool
import com.krystals.interaction.measure.MeasurementMode
import com.krystals.interaction.state.InteractionState
import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.style.FrameMode
import com.krystals.renderer.core.style.LineStyle

/** Materializes InstanceManager's stable diff as automatically-instanced Filament entities. */
class GpuInstanceManager(
    private val engine: Engine,
    private val scene: Scene,
    private val meshes: MeshUploader,
    private val materials: MaterialFactory,
) : AutoCloseable {
    private val entities = linkedMapOf<String, Int>()
    private val objectByEntity = linkedMapOf<Int, String>()
    private val ownedMeshByEntity = linkedMapOf<Int, UploadedMesh>()
    private val auxiliaryIds = linkedSetOf<String>()
    private val auxiliaryMeshes = linkedMapOf<String, MeshData>()
    private val polyhedronBatchIds = linkedSetOf<String>()
    private val polyhedronOutlineIds = linkedSetOf<String>()
    private val model = InstanceManager()
    private var lastDocumentState: com.krystals.interaction.state.ViewerDocumentState? = null
    private var lastSnapshot: RenderScene? = null

    init {
        engine.isAutomaticInstancingEnabled = true
    }

    val drawCallCount: Int get() = model.drawCallCount
    val instanceCount: Int get() = model.instanceCount

    fun sync(snapshot: RenderScene) {
        if (lastSnapshot === snapshot) return
        val diff = model.sync(snapshot)
        (diff.removed + diff.updated).forEach(::destroy)
        val records = diff.batches.values.flatten().associateBy { it.objectId }
        (diff.added + diff.updated).forEach { id ->
            val record = records[id] ?: return@forEach
            if (record.batch.geometry == GeometryKind.POLYHEDRON) return@forEach
            create(record, snapshot)?.let { entity ->
                entities[id] = entity
                objectByEntity[entity] = id.substringBeforeLast(":a").substringBeforeLast(":b")
            }
        }
        polyhedronBatchIds.toList().asReversed().forEach(::destroy)
        polyhedronBatchIds.clear()
        polyhedronOutlineIds.toList().asReversed().forEach(::destroy)
        polyhedronOutlineIds.clear()
        meshes.mergePolyhedra(snapshot.meshes).forEachIndexed { index, merged ->
            val id = "polyhedron-batch:$index"
            auxiliaryMeshes[id] = merged.mesh
            val record = InstanceRecord(id, 0, BatchKey(GeometryKind.POLYHEDRON, MaterialKey(merged.material)), identity())
            create(record, snapshot)?.let { entity ->
                entities[id] = entity
                objectByEntity[entity] = merged.objectIds.firstOrNull().orEmpty()
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
                    MaterialKey(Material(0xFFFFFFFF, opacity = 0.55, reflective = false)),
                ),
                cylinderTransform(start, end, 0.012),
            )
            create(record, snapshot)?.let { entity ->
                entities[id] = entity
                polyhedronOutlineIds += id
            }
        }
        lastDocumentState = null
        lastSnapshot = snapshot
    }

    fun objectIdForEntity(entity: Int): String? = objectByEntity[entity]

    fun updateInteraction(snapshot: RenderScene, state: InteractionState) {
        if (lastDocumentState == state.document) return
        lastDocumentState = state.document
        auxiliaryIds.toList().asReversed().forEach(::destroy)
        auxiliaryIds.clear()
        auxiliaryMeshes.clear()
        val atoms = snapshot.atoms.associateBy { it.atom.id }
        val lockedIds = state.document.lockedMeasurements.flatMap { it.atomIds }.toSet() +
            state.document.inspection.lockedInspectedAtomIds
        val highlighted = state.document.selection.selectedAtomIds.toSet() + lockedIds +
            listOfNotNull(state.document.inspection.inspectedAtomId)
        highlighted.forEach { atomId ->
            val atom = atoms[atomId] ?: return@forEach
            val color = if (atomId in lockedIds) 0xFFCFA7F5 else 0xFF7542A5
            addAuxiliary(
                InstanceRecord(
                    "aux:highlight:$atomId", 0,
                    BatchKey(GeometryKind.HIGHLIGHT, MaterialKey(Material(color, 0.55, reflective = false))),
                    sphereTransform(atom.atom.cartesianCoordinate.toVec3(), atom.radius * 1.18),
                ), snapshot,
            )
        }

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
                measurement.atomIds.takeLast(expected).mapNotNull { atoms[it]?.atom?.cartesianCoordinate?.toVec3() }
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
            if (measurement.mode == MeasurementMode.DIHEDRAL && points.size >= 4) {
                DihedralTool.planes(points[0], points[1], points[2], points[3]).forEachIndexed { planeIndex, plane ->
                    val id = "aux:dihedral:$measurementIndex:$planeIndex"
                    auxiliaryMeshes[id] = planeMesh(plane.vertices, plane.normal)
                    addAuxiliary(
                        InstanceRecord(
                            id, 0,
                            BatchKey(GeometryKind.POLYHEDRON, MaterialKey(Material(0xFFB89AE8, 0.24, reflective = false, doubleSided = true))),
                            identity(),
                        ), snapshot,
                    )
                }
            }
        }
        addFrameAndAxes(snapshot)
    }

    fun clear() {
        entities.keys.toList().asReversed().forEach(::destroy)
        model.clear()
        auxiliaryIds.clear()
        auxiliaryMeshes.clear()
        polyhedronBatchIds.clear()
        polyhedronOutlineIds.clear()
        lastDocumentState = null
        lastSnapshot = null
    }

    override fun close() = clear()

    private fun create(record: InstanceRecord, snapshot: RenderScene): Int? {
        val uploaded = when (record.batch.geometry) {
            GeometryKind.SPHERE, GeometryKind.HIGHLIGHT -> meshes.sharedSphere()
            GeometryKind.CYLINDER -> meshes.sharedCylinder()
            GeometryKind.POLYHEDRON -> {
                val mesh = auxiliaryMeshes[record.objectId]
                    ?: snapshot.meshes.firstOrNull { it.id == record.objectId }?.toMeshData()
                    ?: return null
                meshes.upload(mesh)
            }
            GeometryKind.FRAME, GeometryKind.AXIS, GeometryKind.MEASUREMENT -> meshes.sharedCylinder()
        }
        val materialKind = when {
            record.batch.geometry == GeometryKind.HIGHLIGHT -> MaterialKind.HIGHLIGHT
            record.batch.geometry == GeometryKind.POLYHEDRON -> MaterialKind.POLYHEDRON
            record.batch.material.transparent -> MaterialKind.TRANSPARENT
            else -> MaterialKind.OPAQUE
        }
        val material = materials.create(materialKind, record.batch.material, snapshot.environment) ?: return null
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, uploaded.vertexBuffer, uploaded.indexBuffer, 0, uploaded.indexCount)
            .material(0, material)
            .boundingBox(Box(0f, 0f, 0f, 1.5f, 1.5f, 1.5f))
            .culling(false)
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
        val matrix = snapshot.structure.lattice.matrix
        val expansion = snapshot.expansion
        val limitX = if (snapshot.environment.frame.mode == FrameMode.ALL_CELLS) expansion.x else 1
        val limitY = if (snapshot.environment.frame.mode == FrameMode.ALL_CELLS) expansion.y else 1
        val limitZ = if (snapshot.environment.frame.mode == FrameMode.ALL_CELLS) expansion.z else 1
        if (snapshot.environment.frame.mode != FrameMode.NONE) {
            val segments = linkedSetOf<Pair<Vec3, Vec3>>()
            for (x in 0..limitX) for (y in 0..limitY) segments += matrix.a * x.toDouble() + matrix.b * y.toDouble() to matrix.a * x.toDouble() + matrix.b * y.toDouble() + matrix.c * limitZ.toDouble()
            for (x in 0..limitX) for (z in 0..limitZ) segments += matrix.a * x.toDouble() + matrix.c * z.toDouble() to matrix.a * x.toDouble() + matrix.c * z.toDouble() + matrix.b * limitY.toDouble()
            for (y in 0..limitY) for (z in 0..limitZ) segments += matrix.b * y.toDouble() + matrix.c * z.toDouble() to matrix.b * y.toDouble() + matrix.c * z.toDouble() + matrix.a * limitX.toDouble()
            val renderedSegments = if (snapshot.environment.frame.lineStyle == LineStyle.DASHED) segments.flatMap(::dash) else segments.toList()
            renderedSegments.forEachIndexed { index, (start, end) ->
                addAuxiliary(
                    InstanceRecord(
                        "aux:frame:$index", 0,
                        BatchKey(GeometryKind.FRAME, MaterialKey(Material(0xFFA0A0AA, reflective = false))),
                        cylinderTransform(start, end, 0.015),
                    ), snapshot,
                )
            }
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

    private fun dash(segment: Pair<Vec3, Vec3>): List<Pair<Vec3, Vec3>> = buildList {
        repeat(8) { index ->
            if (index % 2 == 0) {
                val delta = segment.second - segment.first
                add(segment.first + delta * (index / 8.0) to segment.first + delta * ((index + 1.0) / 8.0))
            }
        }
    }

    private fun planeMesh(vertices: List<Vec3>, normal: Vec3): MeshData {
        val positions = vertices.flatMap { listOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }.toFloatArray()
        val normals = List(vertices.size) { listOf(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()) }.flatten().toFloatArray()
        val indices = buildList { for (index in 1 until vertices.lastIndex) { add(0); add(index); add(index + 1) } }.toIntArray()
        return MeshData(positions, normals, indices)
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
        objectByEntity.remove(entity)
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
