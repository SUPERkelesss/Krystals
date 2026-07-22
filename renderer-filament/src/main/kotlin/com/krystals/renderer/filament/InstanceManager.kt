package com.krystals.renderer.filament

import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.MeshInstance
import com.krystals.renderer.core.scene.RenderScene

enum class GeometryKind { SPHERE_HIGH, SPHERE_MEDIUM, SPHERE_LOW, CYLINDER, POLYHEDRON, HIGHLIGHT, FRAME, AXIS, MEASUREMENT }

data class MaterialKey(
    val argb: Long,
    val opacityBits: Long,
    val reflective: Boolean,
    val doubleSided: Boolean,
    val occupancyBits: Long,
) {
    constructor(material: Material, occupancy: Double = 1.0) : this(
        material.argb,
        material.opacity.toBits(),
        material.reflective,
        material.doubleSided,
        occupancy.coerceIn(0.0, 1.0).toBits(),
    )
    val transparent: Boolean get() = Double.fromBits(opacityBits) < 0.999 || Double.fromBits(occupancyBits) < 0.999
}

data class BatchKey(val geometry: GeometryKind, val material: MaterialKey)

data class InstanceRecord(
    val objectId: String,
    val pickId: Int,
    val batch: BatchKey,
    val transform: FloatArray,
)

data class SceneDiff(
    val added: Set<String>,
    val updated: Set<String>,
    val removed: Set<String>,
    val batches: Map<BatchKey, List<InstanceRecord>>,
)

/** Builds stable GPU batches without coupling scene changes to camera updates. */
class InstanceManager {
    private val records = linkedMapOf<String, InstanceRecord>()
    private val stablePickIds = linkedMapOf<String, Int>()
    private var nextPickId = 1

    val drawCallCount: Int get() = records.values.map { it.batch }.distinct().size
    val instanceCount: Int get() = records.size

    fun sync(scene: RenderScene): SceneDiff {
        val next = linkedMapOf<String, InstanceRecord>()
        val sphereGeometry = sphereGeometryForVisibleAtoms(scene.atoms.count(AtomInstance::visible))
        scene.atoms.asSequence().filter(AtomInstance::visible).forEach { atom ->
            next[atom.id] = InstanceRecord(
                atom.id, pickId(atom.id), BatchKey(sphereGeometry, MaterialKey(atom.material, atom.atom.occupancy)),
                transform(atom.atom.cartesianCoordinate.x, atom.atom.cartesianCoordinate.y, atom.atom.cartesianCoordinate.z, atom.radius, atom.radius, atom.radius),
            )
        }
        scene.bonds.asSequence().filter(BondInstance::visible).forEach { bond ->
            val middle = (bond.start + bond.end) * 0.5
            listOf(
                "${bond.id}:a" to Triple(bond.start, middle, bond.startMaterial),
                "${bond.id}:b" to Triple(middle, bond.end, bond.endMaterial),
            ).forEach { (id, half) ->
                next[id] = InstanceRecord(
                    id, pickId(bond.id), BatchKey(GeometryKind.CYLINDER, MaterialKey(half.third)),
                    cylinderTransform(half.first, half.second, bond.radius),
                )
            }
        }
        scene.meshes.asSequence().filter(MeshInstance::visible).forEach { mesh ->
            next[mesh.id] = InstanceRecord(mesh.id, pickId(mesh.id), BatchKey(GeometryKind.POLYHEDRON, MaterialKey(mesh.material)), identity())
        }
        val oldIds = records.keys.toSet()
        val newIds = next.keys.toSet()
        val updated = oldIds.intersect(newIds).filterTo(linkedSetOf()) { id -> !records.getValue(id).sameContent(next.getValue(id)) }
        records.clear(); records.putAll(next)
        return SceneDiff(
            added = newIds - oldIds,
            updated = updated,
            removed = oldIds - newIds,
            batches = records.values.groupBy { it.batch },
        )
    }

    fun objectId(pickId: Int): String? = stablePickIds.entries.firstOrNull { it.value == pickId }?.key

    fun clear() = records.clear()

    private fun pickId(objectId: String): Int = stablePickIds.getOrPut(objectId) {
        check(nextPickId <= 0xFFFFFF) { "24-bit picking id space exhausted" }
        nextPickId++
    }

    private fun InstanceRecord.sameContent(other: InstanceRecord): Boolean =
        pickId == other.pickId && batch == other.batch && transform.contentEquals(other.transform)

    private fun transform(x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double) = floatArrayOf(
        sx.toFloat(), 0f, 0f, 0f,
        0f, sy.toFloat(), 0f, 0f,
        0f, 0f, sz.toFloat(), 0f,
        x.toFloat(), y.toFloat(), z.toFloat(), 1f,
    )

    private fun cylinderTransform(start: com.krystals.crystal.core.math.Vec3, end: com.krystals.crystal.core.math.Vec3, radius: Double): FloatArray {
        val delta = end - start
        val length = delta.length()
        val yAxis = if (length < 1e-12) com.krystals.crystal.core.math.Vec3(0.0, 1.0, 0.0) else delta / length
        val helper = if (kotlin.math.abs(yAxis.y) < 0.9) {
            com.krystals.crystal.core.math.Vec3(0.0, 1.0, 0.0)
        } else {
            com.krystals.crystal.core.math.Vec3(1.0, 0.0, 0.0)
        }
        val xAxis = helper.cross(yAxis).normalized()
        val zAxis = xAxis.cross(yAxis).normalized()
        return floatArrayOf(
            (xAxis.x * radius).toFloat(), (xAxis.y * radius).toFloat(), (xAxis.z * radius).toFloat(), 0f,
            (yAxis.x * length).toFloat(), (yAxis.y * length).toFloat(), (yAxis.z * length).toFloat(), 0f,
            (zAxis.x * radius).toFloat(), (zAxis.y * radius).toFloat(), (zAxis.z * radius).toFloat(), 0f,
            start.x.toFloat(), start.y.toFloat(), start.z.toFloat(), 1f,
        )
    }

    private fun identity() = transform(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    companion object {
        fun sphereGeometryForVisibleAtoms(count: Int): GeometryKind = when {
            count <= 2_000 -> GeometryKind.SPHERE_HIGH
            count <= 20_000 -> GeometryKind.SPHERE_MEDIUM
            else -> GeometryKind.SPHERE_LOW
        }
    }
}
