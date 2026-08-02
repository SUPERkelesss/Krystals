package com.krystals.renderer.filament

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.MeshInstance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class SharedGeometry { SPHERE_HIGH, SPHERE_MEDIUM, SPHERE_LOW, CYLINDER, LINE, AXIS, MEASUREMENT }

enum class SphereLod(val rings: Int, val sectors: Int) {
    HIGH(16, 24),
    MEDIUM(10, 16),
    LOW(6, 12),
}

data class MeshBounds(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val halfExtentX: Float,
    val halfExtentY: Float,
    val halfExtentZ: Float,
) {
    companion object {
        fun fromPositions(positions: FloatArray): MeshBounds {
            require(positions.isNotEmpty() && positions.size % 3 == 0)
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            for (index in positions.indices step 3) {
                val x = positions[index]
                val y = positions[index + 1]
                val z = positions[index + 2]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (z > maxZ) maxZ = z
            }
            return MeshBounds(
                centerX = (minX + maxX) * 0.5f,
                centerY = (minY + maxY) * 0.5f,
                centerZ = (minZ + maxZ) * 0.5f,
                halfExtentX = (maxX - minX) * 0.5f,
                halfExtentY = (maxY - minY) * 0.5f,
                halfExtentZ = (maxZ - minZ) * 0.5f,
            )
        }
    }
}

data class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
)

data class UploadedMesh(
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer,
    val indexCount: Int,
    val bounds: MeshBounds,
)

data class MergedMesh(val material: Material, val mesh: MeshData, val objectIds: List<String>)

/** Owns immutable geometry buffers. Scene transforms remain the responsibility of InstanceManager. */
class MeshUploader(private val engine: Engine) : AutoCloseable {
    private val shared = linkedMapOf<SharedGeometry, UploadedMesh>()
    private val uploaded = mutableListOf<UploadedMesh>()

    fun sharedSphere(lod: SphereLod): UploadedMesh {
        val geometry = when (lod) {
            SphereLod.HIGH -> SharedGeometry.SPHERE_HIGH
            SphereLod.MEDIUM -> SharedGeometry.SPHERE_MEDIUM
            SphereLod.LOW -> SharedGeometry.SPHERE_LOW
        }
        return shared.getOrPut(geometry) { upload(uvSphere(lod.rings, lod.sectors)) }
    }

    fun sharedCylinder(): UploadedMesh = shared.getOrPut(SharedGeometry.CYLINDER) { upload(cylinder()) }

    fun upload(mesh: MeshData): UploadedMesh {
        require(mesh.positions.size % 3 == 0 && mesh.normals.size == mesh.positions.size)
        val vertexCount = mesh.positions.size / 3
        require(mesh.indices.all { it in 0 until vertexCount })
        val interleaved = ByteBuffer.allocateDirect(vertexCount * 7 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        repeat(vertexCount) { index ->
            repeat(3) { interleaved.putFloat(mesh.positions[index * 3 + it]) }
            val nx = mesh.normals[index * 3]
            val ny = mesh.normals[index * 3 + 1]
            val nz = mesh.normals[index * 3 + 2]
            val quaternion = normalQuaternion(nx, ny, nz)
            quaternion.forEach(interleaved::putFloat)
        }
        interleaved.flip()
        val indexBytes = ByteBuffer.allocateDirect(mesh.indices.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        mesh.indices.forEach(indexBytes::putInt)
        indexBytes.flip()
        val vertices = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 28)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, 28)
            .build(engine)
        val indices = IndexBuffer.Builder()
            .indexCount(mesh.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        vertices.setBufferAt(engine, 0, interleaved)
        indices.setBuffer(engine, indexBytes)
        return UploadedMesh(vertices, indices, mesh.indices.size, MeshBounds.fromPositions(mesh.positions)).also(uploaded::add)
    }

    fun destroy(mesh: UploadedMesh) {
        if (!uploaded.remove(mesh)) return
        shared.entries.removeAll { it.value == mesh }
        engine.destroyIndexBuffer(mesh.indexBuffer)
        engine.destroyVertexBuffer(mesh.vertexBuffer)
    }

    /**
     * Per v0.7.1: merge polyhedron faces grouped by (sourceAtomId, material) instead of by
     * material alone. This makes each polyhedron a separate renderable entity, allowing
     * Filament's transparent pass to sort polyhedra by distance. Previously, all faces from
     * all polyhedra sharing the same material were merged into one mesh, causing unstable
     * front-back ordering when faces from different polyhedra overlapped in the view.
     */
    fun mergePolyhedra(meshes: List<MeshInstance>): List<MergedMesh> = meshes
        .filter { it.visible }
        .groupBy { it.sourceAtomId to it.material }
        .map { (_, group) ->
            val material = group.first().material
            val positions = ArrayList<Float>()
            val normals = ArrayList<Float>()
            val indices = ArrayList<Int>()
            group.forEach { item ->
                val base = positions.size / 3
                item.vertices.forEach { vertex -> positions.add3(vertex) }
                repeat(item.vertices.size) { normals.add3(item.normal) }
                item.triangleIndices.forEach { indices += base + it }
            }
            MergedMesh(material, MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray()), group.map { it.id })
        }

    fun segments(segments: List<Pair<Vec3, Vec3>>, dashed: Boolean = false): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Int>()
        segments.forEach { (start, end) ->
            val pieces = if (dashed) 8 else 1
            repeat(pieces) { piece ->
                if (dashed && piece % 2 == 1) return@repeat
                val a = start + (end - start) * (piece.toDouble() / pieces)
                val b = start + (end - start) * ((piece + 1.0) / pieces)
                val base = positions.size / 3
                positions.add3(a); positions.add3(b)
                normals.add3(Vec3(0.0, 0.0, 1.0)); normals.add3(Vec3(0.0, 0.0, 1.0))
                indices += base; indices += base + 1
            }
        }
        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    /**
     * Per v0.8.8: generate a longitude-wedge sector of a UV sphere.
     * [startAngleDeg] and [sweepAngleDeg] define the longitude range in degrees
     * (clockwise from +X toward +Z in the XZ plane). The sector spans all
     * latitudes (pole-to-pole) with [rings] latitude rings and [sectorSteps]
     * longitude steps. Normals are per-vertex radial for smooth shading.
     */
    fun sectorSphere(startAngleDeg: Float, sweepAngleDeg: Float, rings: Int = 10, sectorSteps: Int = 4): MeshData {
        require(sweepAngleDeg > 0f) { "sweep must be positive" }
        val startRad = startAngleDeg * PI.toFloat() / 180f
        val sweepRad = sweepAngleDeg * PI.toFloat() / 180f
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        for (ring in 0..rings) {
            val latitude = PI * ring / rings
            val y = cos(latitude)
            val r = sin(latitude)
            for (step in 0..sectorSteps) {
                val longitude = startRad + sweepRad * step / sectorSteps
                val x = r * cos(longitude)
                val z = r * sin(longitude)
                positions.add3(Vec3(x, y, z))
                normals.add3(Vec3(x, y, z))
            }
        }
        val indices = ArrayList<Int>()
        val stride = sectorSteps + 1
        for (ring in 0 until rings) for (step in 0 until sectorSteps) {
            val a = ring * stride + step
            val b = a + stride
            indices += a; indices += b; indices += a + 1
            indices += a + 1; indices += b; indices += b + 1
        }
        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    override fun close() {
        uploaded.asReversed().forEach {
            engine.destroyIndexBuffer(it.indexBuffer)
            engine.destroyVertexBuffer(it.vertexBuffer)
        }
        uploaded.clear()
        shared.clear()
    }

    private fun uvSphere(rings: Int, sectors: Int): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        for (ring in 0..rings) {
            val latitude = PI * ring / rings
            val y = cos(latitude)
            val radius = sin(latitude)
            for (sector in 0..sectors) {
                val longitude = 2.0 * PI * sector / sectors
                val point = Vec3(radius * cos(longitude), y, radius * sin(longitude))
                positions.add3(point); normals.add3(point)
            }
        }
        val indices = ArrayList<Int>()
        val stride = sectors + 1
        for (ring in 0 until rings) for (sector in 0 until sectors) {
            val a = ring * stride + sector
            val b = a + stride
            indices += a; indices += b; indices += a + 1
            indices += a + 1; indices += b; indices += b + 1
        }
        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    private fun cylinder(sectors: Int = 24): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        for (side in 0..sectors) {
            val angle = side * 2.0 * PI / sectors
            val x = cos(angle); val z = sin(angle)
            positions.add3(Vec3(x, 0.0, z)); normals.add3(Vec3(x, 0.0, z))
            positions.add3(Vec3(x, 1.0, z)); normals.add3(Vec3(x, 0.0, z))
        }
        val indices = ArrayList<Int>()
        for (side in 0 until sectors) {
            val a = side * 2; val b = a + 2
            indices += a; indices += a + 1; indices += b
            indices += b; indices += a + 1; indices += b + 1
        }
        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }

    private fun MutableList<Float>.add3(value: Vec3) {
        add(value.x.toFloat()); add(value.y.toFloat()); add(value.z.toFloat())
    }

    private fun normalQuaternion(nx: Float, ny: Float, nz: Float): FloatArray {
        if (nz < -0.9999f) return floatArrayOf(1f, 0f, 0f, 0f)
        val inverseLength = 1f / kotlin.math.sqrt(2f * (1f + nz)).coerceAtLeast(1e-6f)
        return floatArrayOf(-ny * inverseLength, nx * inverseLength, 0f, (1f + nz) * inverseLength)
    }
}
