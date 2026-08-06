package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.Bond
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

/** Per v0.8.36: non-metal elements (same set as the analysis module's bond classification). */
private val NON_METALS: Set<String> = setOf(
    "H", "He", "B", "C", "N", "O", "F", "Ne",
    "Si", "P", "S", "Cl", "Ar",
    "Ge", "As", "Se", "Br", "Kr",
    "Sb", "Te", "I", "Xe",
    "At", "Rn", "Po",
)

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
    // Per v0.8.30: hydrogen-bond appearance (radius Å / opacity 0..1).
    val hbondRadius: Double = 0.05,
    val hbondOpacity: Double = 0.2,
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

        // Per v0.8.2: emit all atoms as AtomInstances (backward-compat for BondNetwork adapter,
        // picking, info windows), then additionally emit GatheredAtomInstances for groups.
        // Backends that understand GatheredAtomInstance skip drawing individual member atoms.
        val groupCenterById = linkedMapOf<String, Vec3>()
        val groupRadiusById = linkedMapOf<String, Double>()  // for surface-anchored bonds
        for (g in groups) {
            val maxRadius = g.memberAtomIds.mapNotNull { id -> atomById[id]?.let { options.atomRadiusByElement[it.species.symbol] ?: options.defaultAtomRadius } }.maxOrNull() ?: options.defaultAtomRadius
            // Per v0.8.15: group visibility follows ANY member's atom visibility, including
            // boundary-image members. Boundary-image groups (e.g. the +z images of (0,0,1))
            // render the SAME pie as the in-cell group — the user requires (0,0,1)-type
            // positions to look identical to (0,0,0). External-shell groups stay hidden
            // (atomVisible is false unless referenced by an extending bond).
            val anyVisible = g.memberAtomIds.any { id -> atomById[id]?.let { a -> atomVisible(a) } ?: false }
            val remainderMat = Material(argb = g.mixedColor, opacity = 0.25, reflective = false)
            val gatheredId = "gathered:${g.memberAtomIds.sorted().joinToString(",")}"
            groupCenterById[gatheredId] = g.center
            groupRadiusById[gatheredId] = maxRadius
            objects += GatheredAtomInstance(
                id = gatheredId,
                gathered = g,
                radius = maxRadius,
                remainderMaterial = remainderMat,
                visible = anyVisible,
            )
        }
        // Per v0.8.13: boundary-image (shell) atoms whose cartesian position coincides with a
        // PRIMARY (in-cell) atom are exact periodic duplicates — rendering both produces two
        // overlapping atoms at cell faces/corners (e.g. (0,0,1)). Skip the shell duplicate; the
        // in-cell atom already represents that position. Shell atoms at positions with no
        // primary (real cross-cell neighbors) still render.
        val primaryPositions = analysis.atoms.asSequence()
            .filter { !it.isShell }
            .map { AtomKey(it) }
            .toHashSet()

        for (atom in analysis.atoms) {
            if (atom.isShell && AtomKey(atom) in primaryPositions) continue
            objects += AtomInstance(
                id = "atom:${atom.id}",
                atom = atom,
                radius = options.atomRadiusByElement[atom.species.symbol] ?: options.defaultAtomRadius,
                material = atomMaterial(atom),
                visible = atomVisible(atom),
            )
        }
        // Bond pass: drop intra-group bonds, remap positions, dedupe per (groupKey|atomId, groupKey|atomId, offsetB).
        // Per v0.8.5: groups anchor bonds at the sphere SURFACE; duplicate member→same-target
        // bonds collapse with occ-weighted mixedColor at the group end.
        val seenBondKeys = mutableSetOf<Triple<Any, Any, Triple<Int, Int, Int>>>()
        // Collect per-dedupe-key bonding members for mixed-color collapse (amendment B.3).
        val bondMembersByKey = linkedMapOf<Triple<Any, Any, Triple<Int, Int, Int>>, MutableList<Triple<Long, Material, Material>>>()

        // First pass: collect all bonds, track dedupe membership.
        val bondEntries = mutableListOf<Triple<Int, Bond, Triple<Any, Any, Triple<Int, Int, Int>>>>() // (index, bond, dedupeKey)
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
            val isHBond = bond.rule.isHBond
            if (!isHBond) {
                bondMembersByKey.getOrPut(dedupeKey) { mutableListOf() } += Triple(bond.atomA, bondMaterial(start), bondMaterial(end))
            }
            bondEntries += Triple(index, bond, dedupeKey)
        }

        // Second pass: emit one BondInstance per dedupeKey, with surface anchoring + blended materials.
        val emittedKeys = mutableSetOf<Triple<Any, Any, Triple<Int, Int, Int>>>()
        // Per v0.8.36: heteronuclear pairs already emitted (wrapped in-cell image identity).
        val emittedHeteroPairs = mutableSetOf<Pair<Int, Int>>()
        for ((index, bond, dedupeKey) in bondEntries) {
            if (!emittedKeys.add(dedupeKey)) continue // skip duplicates

            val start = atomById[bond.atomA] ?: continue
            val end = atomById[bond.atomB] ?: continue
            val startGroup = groupByMemberId[bond.atomA]
            val endGroup = groupByMemberId[bond.atomB]
            val startKey = dedupeKey.first
            val endKey = dedupeKey.second
            val isHBond = bond.rule.isHBond

            // Per v0.8.5: anchor bonds at sphere SURFACE, not center.
            val rawStart = startGroup?.center ?: start.cartesianCoordinate.toVec3()
            val rawEnd = endGroup?.center ?: end.cartesianCoordinate.toVec3()
            val dir = (rawEnd - rawStart).normalized()
            val startRadius = if (startGroup != null) (groupRadiusById[startKey] ?: 0.0) else 0.0
            val endRadius = if (endGroup != null) (groupRadiusById[endKey] ?: 0.0) else 0.0
            val startPos = rawStart + dir * startRadius
            val endPos = rawEnd - dir * endRadius

            // Per v0.8.5 item 3: blended material for duplicate member→same-target bonds.
            val members = bondMembersByKey[dedupeKey].orEmpty()
            val (startMat, endMat) = if (!isHBond && members.size >= 2 && endGroup != null) {
                // Group end: occ-weighted mixedColor of bonding members ↔ target color.
                val groupMembers = members.map { (memberId, sm, _) -> memberId to sm }.distinct()
                val totalOcc = groupMembers.sumOf { (mid, _) -> atomById[mid]?.occupancy ?: 1.0 }
                val mixedStart = if (startGroup != null && groupMembers.size >= 2) {
                    blendMaterials(groupMembers, totalOcc)
                } else bondMaterial(start)
                val targetEnd = bondMaterial(end)
                mixedStart to targetEnd
            } else if (!isHBond && members.size >= 2 && startGroup != null) {
                val groupMembers = members.map { (memberId, _, em) -> memberId to em }.distinct()
                val totalOcc = groupMembers.sumOf { (mid, _) -> atomById[mid]?.occupancy ?: 1.0 }
                val targetStart = bondMaterial(start)
                val mixedEnd = blendMaterials(groupMembers, totalOcc)
                targetStart to mixedEnd
            } else {
                bondMaterial(start) to bondMaterial(end)
            }

            // Per v0.8.36: single-cell ("no extension") bond visibility. The old rule only gated
            // external shells, so same-atom periodic images on the cell faces (e.g. Ca1-Ca1 metal
            // bonds at 3.87 A in CaC2) leaked into the non-extended view, and boundary-image
            // copies of in-cell coordination bonds were duplicated. Rules:
            //  - primary-primary bonds: always shown;
            //  - same-atom periodic self-images (Ca-Ca): only when the rule extends the cell;
            //  - anything else touching a shell atom: shown only when the NON-METAL end is a
            //    primary atom (the in-cell coordination of that atom) or when both ends are
            //    non-metals (e.g. C-C dumbbells — real bonds of the displayed images).
            val externalAllowed = when {
                !start.isShell && !end.isShell -> true
                // Same-atom periodic self-images are always across the cell wall, so pass
                // outsideAtomIsExternal=true (a boundary image would otherwise short-circuit
                // shouldExtendAcrossCell to true and leak the bond into the non-extended view).
                isSameAtomPeriodicImage(start, end) ->
                    bond.rule.shouldExtendAcrossCell(start.siteId, true)
                else -> {
                    if (start.isExternalShell || end.isExternalShell) {
                        // Genuine out-of-cell neighbours stay behind the rule's extend flag.
                        bond.rule.shouldExtendAcrossCell(start.siteId, end.isExternalShell)
                    } else {
                        // Boundary-image keys: keep the bond only when the NON-METAL end is a
                        // primary atom (in-cell coordination) or both ends are non-metals.
                        val startNonMetal = start.species.symbol in NON_METALS
                        val endNonMetal = end.species.symbol in NON_METALS
                        when {
                            startNonMetal && endNonMetal -> true
                            startNonMetal -> !start.isShell
                            endNonMetal -> !end.isShell
                            else -> false
                        }
                    }
                }
            }
            val visible = options.showBonds && bond.rule.key !in options.hiddenBondKeys && externalAllowed
            // Per v0.8.36: heteronuclear bonds are deduplicated to their in-cell image so each
            // primary atom pair renders exactly once — CaC2's Ca-C coordination bonds (8) instead
            // of their boundary-image copies (26). Homopolar bonds (C-C dumbbells) keep every
            // displayed image (the face dumbbells are real bonds of the shown images). H-bonds
            // are exempt too: every proton-acceptor contact is a distinct bond (ice-Ih's 21
            // visible H-bonds include their periodic equivalents). The first instance wins;
            // BondDetector orders bonds by (atomA, atomB, offset), so the in-cell (0,0,0)-offset
            // image comes first and is the shortest of the pair.
            val finalVisible = if (visible && start.species.symbol != end.species.symbol && !isHBond) {
                emittedHeteroPairs.add(heteroPairKey(start, end))
            } else {
                visible
            }
            objects += BondInstance(
                id = "bond:${bond.atomA}:${bond.atomB}:${bond.offsetB.x}:${bond.offsetB.y}:${bond.offsetB.z}:$index",
                bond = bond,
                start = startPos,
                end = endPos,
                radius = if (isHBond) options.hbondRadius else options.bondRadius,
                startMaterial = if (isHBond) HbondPattern.material(options.hbondOpacity.toFloat()) else startMat,
                endMaterial = if (isHBond) HbondPattern.material(options.hbondOpacity.toFloat()) else endMat,
                visible = finalVisible,
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

    /** Per v0.8.13: 1e-4-quantized cartesian key for exact-coincidence dedupe (boundary-image
     *  atoms that duplicate an in-cell atom's position). */
    private fun AtomKey(a: AtomImage): Triple<Int, Int, Int> {
        val p = a.cartesianCoordinate.toVec3()
        return Triple((p.x / 1e-4).toInt(), (p.y / 1e-4).toInt(), (p.z / 1e-4).toInt())
    }

    private fun outwardNormal(center: Vec3, vertices: List<Vec3>): Vec3? {
        if (vertices.size < 3) return null
        val first = vertices.first()
        var normal = (vertices[1] - first).cross(vertices[2] - first)
        if (normal.lengthSquared() < 1e-18) return null
        if (normal.dot(center - first) > 0.0) normal = normal * -1.0
        return normal.normalized()
    }

    /** Occ-weighted ARGB blend of member materials. */
    private fun blendMaterials(members: List<Pair<Long, Material>>, totalOcc: Double): Material {
        if (members.isEmpty()) return Material(0xFF808080L)
        var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0; var w = 0.0
        for ((_, mat) in members) {
            val wt = 1.0 / members.size // equal weight per member
            val argb = mat.argb
            r += ((argb ushr 16) and 0xFF).toDouble() * wt
            g += ((argb ushr 8) and 0xFF).toDouble() * wt
            b += (argb and 0xFF).toDouble() * wt
            a += ((argb ushr 24) and 0xFF).toDouble() * wt
            w += wt
        }
        val iw = if (w > 0.0) 1.0 / w else 1.0
        val ir = (r * iw).toInt().coerceIn(0, 255)
        val ig = (g * iw).toInt().coerceIn(0, 255)
        val ib = (b * iw).toInt().coerceIn(0, 255)
        val ia = (a * iw).toInt().coerceIn(0, 255)
        return Material(argb = (ia.toLong() shl 24) or (ir.toLong() shl 16) or (ig.toLong() shl 8) or ib.toLong(), reflective = false)
    }

    /** Per v0.8.36: true when [b] is a periodic image of the same atom as [a] — the fractional
     *  difference is an integer lattice translation (within float tolerance). Used to gate
     *  same-atom self-image bonds (e.g. Ca1-Ca1 metal bonds across the CaC2 cell face) behind
     *  the rule's extend flag, while leaving different-atom image pairs (C-C dumbbells) alone. */
    private fun isSameAtomPeriodicImage(a: AtomImage, b: AtomImage): Boolean {
        val dx = (b.fractionalCoordinate.x - a.fractionalCoordinate.x)
        val dy = (b.fractionalCoordinate.y - a.fractionalCoordinate.y)
        val dz = (b.fractionalCoordinate.z - a.fractionalCoordinate.z)
        fun nearInt(v: Double) = kotlin.math.abs(v - kotlin.math.round(v)) < 1e-4
        return nearInt(dx) && nearInt(dy) && nearInt(dz)
    }

    /** Per v0.8.36: identity of the in-cell image of an atom — its fractional coordinate wrapped
     *  into [0,1) and quantised to 1e-4 (same tolerance as the gathered-atom grouper). */
    private fun inCellKey(a: AtomImage): Int {
        fun wrap(v: Double): Int {
            val w = v - kotlin.math.floor(v)
            return (w * 1e4).toInt().coerceIn(0, 9999)
        }
        return (wrap(a.fractionalCoordinate.x) * 100_000_000) +
            (wrap(a.fractionalCoordinate.y) * 10_000) +
            wrap(a.fractionalCoordinate.z)
    }

    /** Per v0.8.36: canonical key of a heteronuclear bond's primary pair (sorted in-cell keys). */
    private fun heteroPairKey(a: AtomImage, b: AtomImage): Pair<Int, Int> {
        val ka = inCellKey(a)
        val kb = inCellKey(b)
        return if (ka <= kb) ka to kb else kb to ka
    }
}
