package com.krystals.renderer.core.primitive

import com.krystals.crystal.core.math.Vec3
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.RenderObject

enum class MeshKind { POLYHEDRON_FACE, CRYSTAL_PLANE }

data class MeshInstance(
    override val id: String,
    val kind: MeshKind,
    val sourceAtomId: Long?,
    val vertices: List<Vec3>,
    val vertexAtomIds: List<Long>,
    val triangleIndices: List<Int>,
    val normal: Vec3,
    val material: Material,
    override val visible: Boolean = true,
) : RenderObject {
    init {
        require(vertices.size >= 3) { "mesh must contain at least three vertices" }
        require(vertexAtomIds.isEmpty() || vertexAtomIds.size == vertices.size) {
            "vertexAtomIds must be empty or match vertices"
        }
        require(triangleIndices.size % 3 == 0) { "triangle indices must form triangles" }
        require(triangleIndices.all { it in vertices.indices }) { "triangle index is out of range" }
        require(normal.lengthSquared() > 1e-18) { "mesh normal must be non-zero" }
    }
}
