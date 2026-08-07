package com.krystals.renderer.core.scene

import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.renderer.core.camera.Camera
import com.krystals.renderer.core.camera.Projection
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.HbondInstance
import com.krystals.renderer.core.primitive.MeshInstance
import com.krystals.renderer.core.style.RenderEnvironment

data class RenderScene(
    val structure: CrystalStructure,
    val expansion: Expansion,
    val objects: List<RenderObject>,
    val camera: Camera = Camera(),
    val projection: Projection = Projection.Orthographic(),
    val environment: RenderEnvironment = RenderEnvironment(),
    // Per v0.6.5: true when the expansion is structural (from a 3×3 matrix transform), not a
    // display-only supercell. In SINGLE_CELL frame mode, structural expansion draws the frame
    // around the entire supercell; display expansion draws just one cell.
    val structuralExpansion: Boolean = false,
) {
    val atoms: List<AtomInstance> = objects.filterIsInstance<AtomInstance>()
    val bonds: List<BondInstance> = objects.filterIsInstance<BondInstance>()
    /** 氢键实例(独立通道,与 [bonds] 分离)。 */
    val hbonds: List<HbondInstance> = objects.filterIsInstance<HbondInstance>()
    val meshes: List<MeshInstance> = objects.filterIsInstance<MeshInstance>()

    init {
        require(objects.map { it.id }.distinct().size == objects.size) { "render object ids must be unique" }
    }
}
