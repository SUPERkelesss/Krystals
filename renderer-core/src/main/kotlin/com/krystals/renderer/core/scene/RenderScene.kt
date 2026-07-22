package com.krystals.renderer.core.scene

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.camera.Projection
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.MeshInstance

data class RenderScene(
    val structure: CrystalStructure,
    val expansion: Expansion,
    val objects: List<RenderObject>,
    val camera: Camera = Camera(),
    val projection: Projection = Projection.Orthographic(),
) {
    val atoms: List<AtomInstance> = objects.filterIsInstance<AtomInstance>()
    val bonds: List<BondInstance> = objects.filterIsInstance<BondInstance>()
    val meshes: List<MeshInstance> = objects.filterIsInstance<MeshInstance>()

    init {
        require(objects.map { it.id }.distinct().size == objects.size) { "render object ids must be unique" }
    }
}
