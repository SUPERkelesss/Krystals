package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.coordination.CoordinationAnalyzer
import com.krystals.crystal.analysis.polyhedron.PolyhedronHull
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.GatheredAtomInstance
import com.krystals.renderer.core.primitive.MeshInstance
import com.krystals.renderer.core.primitive.MeshKind
import com.krystals.renderer.core.scene.GatheredAtomGrouper
import com.krystals.renderer.core.scene.RenderObject
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.style.BondColorMode
import com.krystals.renderer.core.style.HbondPattern
import com.krystals.renderer.core.style.RenderEnvironment

data class SceneBuildOptions(
    val hiddenSiteIds: Set<String> = emptySet(),
    val hiddenBondKeys: Set<String> = emptySet(),
    val showBonds: Boolean = true,
    val polyhedronSiteIds: Set<String> = emptySet(),
    val atomRadiusByElement: Map<String, Double> = emptyMap(),
    val atomMaterialBySite: Map<String, Material> = emptyMap(),
    val bondMaterialBySite: Map<String, Material> = emptyMap(),
    val polyhedronMaterialBySite: Map<String, Material> = emptyMap(),
    val defaultAtomMaterial: Material = Material(0xFFB8B8B8L),
    val defaultBondMaterial: Material = Material(0xFF9A90A0L),
    val defaultPolyhedronMaterial: Material = Material(0x809A90A0L, opacity = 0.5, doubleSided = true),
    val defaultAtomRadius: Double = 0.35,
    val bondRadius: Double = 0.15,
    val bondColorMode: BondColorMode = BondColorMode.BICOLOR,
    val environment: RenderEnvironment = RenderEnvironment(),
    val structuralExpansion: Boolean = false,
) {
    init {
        require(defaultAtomRadius > 0.0) { "default atom radius must be positive" }
        require(bondRadius > 0.0) { "bond radius must be positive" }
    }
}

class CrystalSceneBuilder {
    fun build(
        structure: CrystalStructure,
        analysis: BondNetwork,
        options: SceneBuildOptions = SceneBuildOptions(),
    ): RenderScene {
        require(analysis.structure == structure) { "analysis result belongs to a different crystal structure" }

        val atomById = analysis.atoms.associateBy { it.id }
        // Per v0.6.5: only make an external-shell atom visible if the bond's directional extend
        // flag allows it.
        val externallyVisible = analysis.bonds.asSequence()
            .filter { it.rule.key !in options.hiddenBondKeys }
            .mapNotNull { bond ->
                val start = atomById[bond.atomA] ?: return@mapNotNull null
                val end = atomById[bond.atomB] ?: return@mapNotNull null
                if (end.isExternalShell &&
                    bond.rule.shouldExtendAcrossCell(start.siteId, true) &&
                    end.siteId !in options.hiddenSiteIds
                ) end.id else null
            }
            .toSet()

        fun atomVisible(atom: AtomImage): Boolean = when {
            atom.siteId in options.hiddenSiteIds -> false
            !atom.isShell || atom.isBoundaryImage -> true
            else -> atom.id in externallyVisible
        }

        fun atomMaterial(atom: AtomImage): Material =
            options.atomMaterialBySite[atom.siteId] ?: options.defaultAtomMaterial

        fun bondMaterial(atom: AtomImage): Material = when (options.bondColorMode) {
            BondColorMode.BICOLOR -> options.bondMaterialBySite[atom.siteId] ?: options.defaultBondMaterial
            BondColorMode.UNICOLOR -> options.defaultBondMaterial
        }

        // Per v0.8.2: build gathered-atom groups for co-located atoms of different sites.
        val colorBySite = options.atomMaterialBySite.mapValues { it.value.argb }
        val groups = GatheredAtomGrouper.group(analysis.atoms, colorBySite)
        val groupByMemberId = GatheredAtomGrouper.groupByAtomId(analysis.atoms, colorBySite)

        val objects = mutableListOf<RenderObject>()

        // Atom pass: emit plain AtomInstance for ungrouped visible atoms; skip grouped members.
        val groupCenterById = linkedMapOf<String, Vec3>()
        for (g in groups) {
            val maxRadius = g.memberAtomIds.mapNotNull { id -> atomById[id]?.let { options.atomRadiusByElement[it.species.symbol] ?: options.defaultAtomRadius } }.maxOrNull() ?: options.defaultAtomRadius
            val anyVisible = g.memberAtomIds.any { id -> atomById[id]?.let { atomVisible(it) } ?: false }
            val remainderMat = Material(argb = g.mixedColor, opacity = 0.25, reflective = false)
            val gatheredId = "gathered:${g.memberAtomIds.sorted().joinToString(",")}"
            groupCenterById[gatheredId] = g.center
            objects += GatheredAtomInstance(
                id = gatheredId,
                gathered = g,
                radius = maxRadius,
                remainderMaterial = remainderMat,
                visible = anyVisible,
            )
        }
        val groupAtomIds = groupByMemberId.keys
        for (atom in analysis.atoms) {
            if (atom.id in groupAtomIds) continue  // handled by GatheredAtomInstance
            objects += AtomInstance(
                id = "atom:${atom.id}",
                atom = atom,
                radius = options.atomRadiusByElement[atom.species.symbol] ?: options.defaultAtomRadius,
                material = atomMaterial(atom),
                visible = atomVisible(atom),
            )
        }

        // Bond pass: drop intra-group bonds, remap positions, dedupe per (groupKey|atomId, groupKey|atomId, offsetB).
        val seenBondKeys = mutableSetOf<Triple<Any, Any, Triple<Int, Int, Int>>>()

        analysis.bonds.forEachIndexed { index, bond ->
            val start = atomById[bond.atomA]
                ?: error("bond ${bond.atomA}-${bond.atomB} references missing atom ${bond.atomA}")
            val end = atomById[bond.atomB]
                ?: error("bond ${bond.atomA}-${bond.atomB} references missing atom ${bond.atomB}")

            val startGroup = groupByMemberId[bond.atomA]
            val endGroup = groupByMemberId[bond.atomB]

            // Drop intra-group bonds.
            if (startGroup != null && endGroup != null && startGroup == endGroup) return@forEachIndexed

            val startKey: Any = startGroup?.let { "gathered:${it.memberAtomIds.sorted().joinToString(",")}" } ?: bond.atomA
            val endKey: Any = endGroup?.let { "gathered:${it.memberAtomIds.sorted().joinToString(",")}" } ?: bond.atomB
            val dedupeKey = Triple(startKey, endKey, Triple(bond.offsetB.x, bond.offsetB.y, bond.offsetB.z))
            if (!seenBondKeys.add(dedupeKey)) return@forEachIndexed

            val startPos = startGroup?.center ?: start.cartesianCoordinate.toVec3()
            val endPos = endGroup?.center ?: end.cartesianCoordinate.toVec3()
            val externalAllowed = !end.isExternalShell || bond.rule.shouldExtendAcrossCell(start.siteId, end.isExternalShell)
            val isHBond = bond.rule.isHBond
            objects += BondInstance(
                id = "bond:${bond.atomA}:${bond.atomB}:${bond.offsetB.x}:${bond.offsetB.y}:${bond.offsetB.z}:$index",
                bond = bond,
                start = startPos,
                end = endPos,
                radius = if (isHBond) HbondPattern.RADIUS else options.bondRadius,
                startMaterial = if (isHBond) HbondPattern.material() else bondMaterial(start),
                endMaterial = if (isHBond) HbondPattern.material() else bondMaterial(end),
                visible = options.showBonds && bond.rule.key !in options.hiddenBondKeys && externalAllowed,
            )
        }

        // Polyhedra pass (unchanged).
        val neighbors = if (options.polyhedronSiteIds.isEmpty()) {
            emptyMap()
        } else {
            CoordinationAnalyzer.neighbors(analysis, showBonds = true)
        }
        analysis.atoms.asSequence()
            .filter { (!it.isShell || it.isBoundaryImage) && it.siteId in options.polyhedronSiteIds }
            .forEach { center ->
                val ligands = neighbors[center.id].orEmpty()
                val byPosition = ligands.groupBy { it.cartesianCoordinate.toVec3() }
                val faces = PolyhedronHull.faces(
                    center.cartesianCoordinate.toVec3(),
                    ligands.map { it.cartesianCoordinate.toVec3() },
                )
                faces.forEachIndexed { faceIndex, vertices ->
                    val vertexAtoms = vertices.map { position ->
                        byPosition[position]?.firstOrNull()
                            ?: error("polyhedron face contains an unknown ligand position")
                    }
                    val normal = outwardNormal(center.cartesianCoordinate.toVec3(), vertices)
                        ?: return@forEachIndexed
                    objects += MeshInstance(
                        id = "mesh:polyhedron:${center.id}:$faceIndex",
                        kind = MeshKind.POLYHEDRON_FACE,
                        sourceAtomId = center.id,
                        vertices = vertices.toList(),
                        vertexAtomIds = vertexAtoms.map { it.id },
                        triangleIndices = triangleFan(vertices.size),
                        normal = normal,
                        material = options.polyhedronMaterialBySite[center.siteId]
                            ?: options.defaultPolyhedronMaterial,
                    )
                }
            }

        return RenderScene(
            structure = structure,
            expansion = analysis.expansion,
            objects = objects.toList(),
            environment = options.environment,
            structuralExpansion = options.structuralExpansion,
        )
    }

    private fun triangleFan(vertexCount: Int): List<Int> = buildList {
        for (index in 1 until vertexCount - 1) {
            add(0)
            add(index)
            add(index + 1)
        }
    }

    private fun outwardNormal(center: Vec3, vertices: List<Vec3>): Vec3? {
        if (vertices.size < 3) return null
        val first = vertices.first()
        var normal = (vertices[1] - first).cross(vertices[2] - first)
        if (normal.lengthSquared() < 1e-18) return null
        if (normal.dot(center - first) > 0.0) normal = normal * -1.0
        return normal.normalized()
    }
}
