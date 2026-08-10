package com.krystals.renderer.filament

import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.GatheredAtomInstance
import com.krystals.renderer.core.primitive.HbondInstance
import com.krystals.renderer.core.primitive.MeshInstance
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.crystal.core.math.Vec3

enum class GeometryKind { SPHERE_HIGH, HIGHLIGHT, SPHERE_MEDIUM, SPHERE_LOW, CYLINDER, HBOND, POLYHEDRON, FRAME, AXIS, MEASUREMENT, PIE_SECTOR }

data class MaterialKey(
    val argb: Long,
    val opacityBits: Long,
    val reflective: Boolean,
    val doubleSided: Boolean,
    val occupancyBits: Long,
    /** Gathered-atom pie sectors, each encoded as (argb shl 16) or (fraction*65535).
     *  Empty for regular atoms. Content-equality keeps material instances deduped by
     *  actual sector layout. */
    val slices: List<Long> = emptyList(),
) {
    constructor(material: Material, occupancy: Double = 1.0, slices: List<Long> = emptyList()) : this(
        material.argb,
        material.opacity.toBits(),
        material.reflective,
        material.doubleSided,
        occupancy.coerceIn(0.0, 1.0).toBits(),
        slices,
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
        val atomsByImageId = scene.atoms.associateBy { it.atom.id }

        // Per v0.7.0: gather co-located mixed-occupancy atoms — per-slice sector meshes
        // are emitted by GpuInstanceManager. InstanceManager only tracks membership.
        // Per v0.7.0: index members of ALL groups (visible or not). A group hidden because it
        // is a pure boundary-image bundle (e.g. the +z images of (0,0,1)) must still suppress
        // its member atoms — otherwise they fall back to individual sphere rendering and the
        // position shows a plain single-site ball instead of nothing.
        val gatheredByMemberId = linkedMapOf<Long, String>()
        scene.objects.asSequence().filterIsInstance<GatheredAtomInstance>().forEach { gInst ->
            val g = gInst.gathered
            val memberIds = g.memberAtomIds.sorted().joinToString(",")
            val baseId = "gathered:$memberIds"
            g.memberAtomIds.forEach { gatheredByMemberId[it] = baseId }
        }

        scene.atoms.asSequence().filter(AtomInstance::visible).forEach { atom ->
            if (atom.atom.id in gatheredByMemberId) return@forEach // rendered as gathered pie
            next[atom.id] = InstanceRecord(
                atom.id, pickId(atom.id), BatchKey(sphereGeometry, MaterialKey(atom.material, atom.atom.occupancy)),
                transform(atom.atom.cartesianCoordinate.x, atom.atom.cartesianCoordinate.y, atom.atom.cartesianCoordinate.z, atom.radius, atom.radius, atom.radius),
            )
        }
        scene.bonds.asSequence().filter(BondInstance::visible).forEach { bond ->
            val startAtom = atomsByImageId[bond.bond.atomA]
            val endAtom = atomsByImageId[bond.bond.atomB]
            // Per v0.7.0: skip radius clip for gathered group endpoints — the scene
            // builder already anchored the bond at the sphere surface.
            val startRadius = if (startAtom?.atom?.id in gatheredByMemberId) 0.0
                else startAtom?.takeIf { it.visible }?.radius
            val endRadius = if (endAtom?.atom?.id in gatheredByMemberId) 0.0
                else endAtom?.takeIf { it.visible }?.radius
            val (clippedStart, clippedEnd) = clipBondEndpoints(
                bond.start, bond.end, startRadius, endRadius,
            )
            val middle = (clippedStart + clippedEnd) * 0.5
            val halves: List<Pair<String, Triple<Vec3, Vec3, Material>>> = listOf(
                "${bond.id}:a" to Triple(clippedStart, middle, bond.startMaterial),
                "${bond.id}:b" to Triple(middle, clippedEnd, bond.endMaterial),
            )
            halves.forEach { (id, half) ->
                next[id] = InstanceRecord(
                    id, pickId(bond.id), BatchKey(GeometryKind.CYLINDER, MaterialKey(half.third)),
                    cylinderTransform(half.first, half.second, bond.radius),
                )
            }
        }
        // Hydrogen bonds: a single translucent cylinder (no two-half split), same endpoint
        // clipping as normal bonds (the scene builder already anchored at sphere surfaces).
        scene.hbonds.asSequence().filter(HbondInstance::visible).forEach { hb ->
            val startAtom = atomsByImageId[hb.hbond.donorId]
            val endAtom = atomsByImageId[hb.hbond.acceptorId]
            val startRadius = if (startAtom?.atom?.id in gatheredByMemberId) 0.0
                else startAtom?.takeIf { it.visible }?.radius
            val endRadius = if (endAtom?.atom?.id in gatheredByMemberId) 0.0
                else endAtom?.takeIf { it.visible }?.radius
            val (clippedStart, clippedEnd) = clipBondEndpoints(hb.start, hb.end, startRadius, endRadius)
            next[hb.id] = InstanceRecord(
                hb.id, pickId(hb.id),
                BatchKey(GeometryKind.HBOND, MaterialKey(hb.material)),
                cylinderTransform(clippedStart, clippedEnd, hb.radius),
            )
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

    private fun clipBondEndpoints(
        start: Vec3,
        end: Vec3,
        startRadius: Double?,
        endRadius: Double?,
    ): Pair<Vec3, Vec3> {
        val delta = end - start
        val length = delta.length()
        if (length < 1e-12) return start to end
        val dir = delta / length
        // Extend the cylinder 1% into the atom sphere so the joint is tight instead of
        // leaving a visible gap at the surface.
        val startOffset = startRadius?.let { minOf(it * 0.99, length * 0.5) } ?: 0.0
        val endOffset = endRadius?.let { minOf(it * 0.99, length * 0.5) } ?: 0.0
        return (start + dir * startOffset) to (end - dir * endOffset)
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
