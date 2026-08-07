package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.Bond
import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.coordination.CoordinationAnalyzer
import com.krystals.crystal.analysis.polyhedron.PolyhedronHull
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Molecule
import com.krystals.crystal.core.model.MoleculeAtom
import com.krystals.crystal.core.periodic.Int3
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
    // Per v0.8.30: hydrogen-bond appearance (radius Å / opacity 0..1).
    val hbondRadius: Double = 0.05,
    val hbondOpacity: Double = 0.2,
    val bondColorMode: BondColorMode = BondColorMode.BICOLOR,
    val environment: RenderEnvironment = RenderEnvironment(),
    val structuralExpansion: Boolean = false,
    // Per molecule-extend: show whole molecules across the cell. When enabled, external-shell
    // atoms and cross-cell bonds render when their molecule (by MoleculeAtom id == primary
    // AtomImage id) has at least one visible in-cell atom, replacing the per-rule extend flags.
    val moleculeExtend: Boolean = false,
    val molecules: List<Molecule> = emptyList(),
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
        // flag allows it. Per v0.8.44: same-atom periodic self-images never surface their far
        // end — with the default METALS_ONLY extension the Ca-Ca rule extends, and without this
        // guard the outer-shell Ca images beyond the cell would all become visible as an extra
        // ring around the cell (the reached outer-shell C atoms of real Ca-C extensions still
        // appear, which is the intended "show extended bonds" behaviour).
        // Per molecule-extend: skipped entirely in molecule-extend mode — atom visibility there
        // follows molecule ownership instead of extend flags.
        val externallyVisible = if (options.moleculeExtend) emptySet() else analysis.bonds.asSequence()
            .filter { it.rule.key !in options.hiddenBondKeys }
            .mapNotNull { bond ->
                val start = atomById[bond.atomA] ?: return@mapNotNull null
                val end = atomById[bond.atomB] ?: return@mapNotNull null
                if (end.isExternalShell &&
                    !isSameAtomPeriodicImage(start, end) &&
                    bond.rule.shouldExtendAcrossCell(start.siteId, true) &&
                    end.siteId !in options.hiddenSiteIds
                ) end.id else null
            }
            .toSet()

        // Per molecule-extend: ownership maps built once per scene build. MoleculeAtom ids equal
        // the primary (cellOffset == (0,0,0)) AtomImage ids; an external-shell atom belongs to the
        // molecule of its in-cell representative (same siteId + integer fractional translation —
        // the same test as isSameAtomPeriodicImage).
        val moleculeIndexByRepId: Map<Long, Int>
        val moleculeSiteIds: List<Set<String>>
        val repBySiteId: Map<String, List<AtomImage>>
        // 分子原子物理位置(含所有与单胞相交的周期映像),用于外部壳层原子的精确归属:
        // 壳层原子显示 ⟺ 其位置精确落在某可见分子映像的原子物理坐标上(而非仅 rep 归属,
        // 否则相邻分子的边界映像会被误显示为"该分子的一部分")。周期映像覆盖 ±1 晶胞:
        // 分子 M 的映像 M+t 与单胞相交 ⟺ ∃ 分子原子 frac + t ∈ [0,1]³ —— 这些映像都要
        // 完整显示(如尿素分子跨晶胞,(1,0.5)/(0.5,1)/上下底面的部分是相邻映像)。
        val moleculePositions: List<List<Vec3>>
        // 每分子的显示映像原子(MoleculeAtom.id → 映像物理位置),供动态原子创建复用。
        val moleculeImageAtoms: List<List<Pair<Int, Vec3>>>
        // 分子拓扑邻居(原胞原子 id → 分子内邻居 id),用于键的分子归属判定。
        val moleculeNeighbors: List<Map<Int, Set<Int>>>
        if (options.moleculeExtend) {
            val indexByAtomId = HashMap<Int, Int>()
            options.molecules.forEachIndexed { index, m -> m.atoms.forEach { indexByAtomId[it.id] = index } }
            val repAtoms = analysis.atoms.filter { !it.isShell && it.cellOffset == Int3(0, 0, 0) }
            val perMolSiteIds = Array(options.molecules.size) { HashSet<String>() }
            val repIndex = HashMap<Long, Int>()
            for (a in repAtoms) {
                val mol = indexByAtomId[a.id.toInt()] ?: continue
                repIndex[a.id] = mol
                perMolSiteIds[mol].add(a.siteId)
            }
            moleculeIndexByRepId = repIndex
            moleculeSiteIds = perMolSiteIds.map { it.toSet() }
            repBySiteId = repAtoms.groupBy { it.siteId }
            val lattice = structure.lattice
            fun cellShift(t: Int3): Vec3 =
                lattice.toCartesian(FractionalCoordinate(t.x.toDouble(), t.y.toDouble(), t.z.toDouble())).toVec3()
            val images = ArrayList<List<Pair<Int, Vec3>>>(options.molecules.size)
            // 映像与单胞"实质重叠"的阈值(分数坐标):原子中心须距所有晶胞边界 ≥ 该值,
            // 否则视为贴边/无重叠的相邻分子(如尿素沿 c 只伸入 0.03 的映像),不显示。
            // t=0(单胞内分子本身)恒显示,含其跨胞延伸。
            val imageEps = 0.05
            for (m in options.molecules) {
                val fracs = m.atoms.map { lattice.toFractional(it.position) }
                val entries = ArrayList<Pair<Int, Vec3>>()
                for (tx in -1..1) for (ty in -1..1) for (tz in -1..1) {
                    val t = Int3(tx, ty, tz)
                    val intersects = if (tx == 0 && ty == 0 && tz == 0) {
                        true
                    } else {
                        fracs.any { f ->
                            (f.x + tx) in imageEps..(1.0 - imageEps) &&
                                (f.y + ty) in imageEps..(1.0 - imageEps) &&
                                (f.z + tz) in imageEps..(1.0 - imageEps)
                        }
                    }
                    if (!intersects) continue
                    val shift = cellShift(t)
                    for (ma in m.atoms) entries += ma.id to (ma.position.toVec3() + shift)
                }
                images += entries.distinct()
            }
            moleculeImageAtoms = images
            moleculePositions = images.map { it.map { p -> p.second } }
            val neighbors = ArrayList<Map<Int, Set<Int>>>(options.molecules.size)
            for (m in options.molecules) {
                val adj = HashMap<Int, MutableSet<Int>>()
                for (mb in m.bonds) {
                    adj.getOrPut(mb.from) { mutableSetOf() } += mb.to
                    adj.getOrPut(mb.to) { mutableSetOf() } += mb.from
                }
                neighbors += adj
            }
            moleculeNeighbors = neighbors
        } else {
            moleculeIndexByRepId = emptyMap()
            moleculeSiteIds = emptyList()
            repBySiteId = emptyMap()
            moleculePositions = emptyList()
            moleculeImageAtoms = emptyList()
            moleculeNeighbors = emptyList()
        }

        /** 场景原子 → 原胞代表原子 id(分子拓扑节点)。 */
        fun repIdOf(atom: AtomImage): Int? = when {
            !atom.isShell && atom.cellOffset == Int3(0, 0, 0) -> atom.id.toInt()
            else -> repBySiteId[atom.siteId]?.firstOrNull { isSameAtomPeriodicImage(it, atom) }?.id?.toInt()
        }

        /** 场景原子 → 分子索引:原胞原子直查;shell/边界映像经原胞代表(re-same-site + 整数平移)查。 */
        fun moleculeIndexOf(atom: AtomImage): Int? {
            moleculeIndexByRepId[atom.id]?.let { return it }
            if (!atom.isShell) return null
            val rep = repBySiteId[atom.siteId]?.firstOrNull { isSameAtomPeriodicImage(it, atom) } ?: return null
            return moleculeIndexByRepId[rep.id]
        }

        /** 分子内所有原胞原子都被 hiddenSiteIds 覆盖 → 整分子(含其映像与键)隐藏。 */
        fun moleculeFullyHidden(mol: Int): Boolean {
            val siteIds = moleculeSiteIds[mol]
            return siteIds.isNotEmpty() && siteIds.all { it in options.hiddenSiteIds }
        }

        fun atomVisible(atom: AtomImage): Boolean {
            return when {
                atom.siteId in options.hiddenSiteIds -> false
                !options.moleculeExtend -> !atom.isShell || atom.isBoundaryImage || atom.id in externallyVisible
                // 分子展开:单胞内/边界映像按原逻辑(hiddenSites 已过滤)。
                !atom.isShell || atom.isBoundaryImage -> true
                // 分子展开:外部壳层原子显示 ⟺ 其位置精确落在某可见分子的原子物理坐标上
                // (完整分子 = 分子全部原子的物理位置;相邻分子的边界映像不误显示)。
                else -> {
                    val mol = moleculeIndexOf(atom) ?: return false
                    if (moleculeFullyHidden(mol)) return false
                    val pos = atom.cartesianCoordinate.toVec3()
                    moleculePositions[mol].any { distance(it, pos) < 1e-3 }
                }
            }
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
                // Per molecule-extend: 两端归一化到同一未整分子隐藏的分子 → 显示(替代 extend 规则)。
                // 同原子周期自像(如跨晶胞面的 Cl2 分子两半)是分子内键,因此渲染。
                options.moleculeExtend -> {
                    if (isHBond) {
                        // 分子间氢键:不属于任何分子,不沿氢键展开分子;氢键显示跟随两端
                        // 原子可见性(各自分子开关 / hiddenSites),保证只显示可见原子之间的
                        // 合法氢键(键本身由 BondDetector 按距离/角度/per-H 规则生成)。
                        atomVisible(start) && atomVisible(end)
                    } else {
                        // 分子展开:两端必须属于同一分子、拓扑相邻、且端点原子均显示
                        // (拓扑相邻排除同原子自像键等非分子内键)。
                        val mi = moleculeIndexOf(start)
                        val mj = moleculeIndexOf(end)
                        if (mi == null || mi != mj || moleculeFullyHidden(mi)) {
                            false
                        } else {
                            val sRep = repIdOf(start)
                            val eRep = repIdOf(end)
                            sRep != null && eRep != null && sRep != eRep &&
                                eRep in moleculeNeighbors[mi][sRep].orEmpty() &&
                                atomVisible(start) && atomVisible(end)
                        }
                    }
                }
                !start.isShell && !end.isShell -> true
                // Same-atom periodic self-images (an atom bonded to its own periodic image)
                // are never rendered — they are not chemical bonds, and the default METALS_ONLY
                // extension preference would otherwise resurrect the Ca-Ca "bonds" in CaC2.
                isSameAtomPeriodicImage(start, end) -> false
                else -> {
                    if (start.isExternalShell || end.isExternalShell) {
                        // Genuine out-of-cell neighbours stay behind the rule's extend flag.
                        bond.rule.shouldExtendAcrossCell(start.siteId, end.isExternalShell)
                    } else {
                        // Boundary-image keys (one or both ends are displayed face images):
                        // always shown — both ends are displayed atoms, their bonds are part
                        // of the picture (v0.8.42 restored after the v0.8.36 over-filtering).
                        true
                    }
                }
            }
            val visible = options.showBonds && bond.rule.key !in options.hiddenBondKeys && externalAllowed
            // Per v0.8.42: no heteronuclear dedup — every bond between displayed atoms
            // (primary or boundary-image) is rendered, so face images keep ALL their bonds.
            // Same-atom self-images and out-of-cell neighbours were already gated above.
            val finalVisible = visible
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

        // Per molecule-extend: BondDetector 只从 primary/boundary 中心生成键并 materialize
        // 与之成键的 ±1 层壳层 —— 分子伸出更远(如尿素沿 c 跨两个晶胞,末端在 (0,0,2))
        // 的原子缺失,分子显示不完整。补齐两部分:
        //  1) 动态原子:分子原子在其物理位置无场景原子 → 直接从 Molecule 发射合成原子;
        //  2) 键补齐:对每个显示的外部壳层原子与动态原子,补其分子拓扑邻居的最邻近映像键
        //     (任意 cellOffset),并用分子内键长验证,防止误配生成长键。
        if (options.moleculeExtend && options.molecules.isNotEmpty()) {
            // 分子内键长(排序原子对),用于键补齐的键长验证;邻居集合已在归属块构建。
            val bondLengthByPair = HashMap<Pair<Int, Int>, Double>()
            for (m in options.molecules) {
                val posById = m.atoms.associate { it.id to it.position.toVec3() }
                for (mb in m.bonds) {
                    val p1 = posById[mb.from] ?: continue
                    val p2 = posById[mb.to] ?: continue
                    bondLengthByPair[minOf(mb.from, mb.to) to maxOf(mb.from, mb.to)] = distance(p1, p2)
                }
            }
            // 场景原子按原胞代表 id 索引。
            val sceneByRepId = HashMap<Int, MutableList<AtomImage>>()
            for (a in analysis.atoms) {
                val repId = repIdOf(a) ?: continue
                sceneByRepId.getOrPut(repId) { mutableListOf() } += a
            }
            fun sceneAtomAt(repId: Int, position: Vec3): AtomImage? =
                sceneByRepId[repId]?.firstOrNull { distance(it.cartesianCoordinate.toVec3(), position) < 1e-3 }

            // 动态原子:分子映像原子物理位置无场景原子 → 发射合成原子(id 取负避免冲突;
            // isShell=true 使其可见性走分子归属)。覆盖分子与单胞相交的所有周期映像
            // (moleculeImageAtoms,含 ±1 晶胞平移),超出 BondDetector materialize 范围的
            // 分子部分(如尿素末端在 (2,0,0) 层)由此补全。
            val moleculeAtomById = HashMap<Int, MoleculeAtom>()
            options.molecules.forEach { m -> m.atoms.forEach { moleculeAtomById[it.id] = it } }
            val dynamicAtomsByRep = HashMap<Int, MutableList<AtomImage>>()  // MoleculeAtom.id → 动态原子
            val dynamicMolOf = HashMap<Int, Int>()                          // MoleculeAtom.id → 分子索引
            var nextDynId = -1L
            moleculeImageAtoms.forEachIndexed { molIndex, images ->
                if (moleculeFullyHidden(molIndex)) return@forEachIndexed
                for ((maId, pos) in images) {
                    if (sceneAtomAt(maId, pos) != null) continue
                    val ma = moleculeAtomById[maId] ?: continue
                    val dyn = AtomImage(
                        id = nextDynId--,
                        siteId = ma.siteId,
                        siteLabel = ma.label,
                        species = ma.species,
                        fractionalCoordinate = FractionalCoordinate.ZERO,
                        cartesianCoordinate = CartesianCoordinate(pos.x, pos.y, pos.z),
                        occupancy = 1.0,
                        cellOffset = Int3(0, 0, 0),
                        isShell = true,
                        isBoundaryImage = false,
                    )
                    dynamicAtomsByRep.getOrPut(maId) { mutableListOf() } += dyn
                    dynamicMolOf[maId] = molIndex
                    objects += AtomInstance(
                        id = "molatom:$molIndex:$maId:${-nextDynId}",
                        atom = dyn,
                        radius = options.atomRadiusByElement[ma.species.symbol] ?: options.defaultAtomRadius,
                        material = atomMaterial(dyn),
                        visible = ma.siteId !in options.hiddenSiteIds,
                    )
                }
            }

            // 邻居解析:场景最近映像 或 动态原子(同 MoleculeAtom.id),取更近者。
            fun neighborRef(n: Int, fromPos: Vec3): AtomImage? {
                val sceneBest = sceneByRepId[n]?.minByOrNull { distance(fromPos, it.cartesianCoordinate.toVec3()) }
                val dynBest = dynamicAtomsByRep[n]?.minByOrNull { distance(fromPos, it.cartesianCoordinate.toVec3()) }
                return when {
                    sceneBest != null && dynBest == null -> sceneBest
                    sceneBest == null && dynBest != null -> dynBest
                    sceneBest != null && dynBest != null ->
                        if (distance(fromPos, dynBest.cartesianCoordinate.toVec3()) <= distance(fromPos, sceneBest.cartesianCoordinate.toVec3())) dynBest else sceneBest
                    else -> null
                }
            }
            val emittedPairs = HashSet<Pair<Long, Long>>()
            for (b in analysis.bonds) emittedPairs += minOf(b.atomA, b.atomB) to maxOf(b.atomA, b.atomB)
            val moleculeRule = BondRule("A", "B", 0.1, 10.0, BondRuleSource.CUSTOM)
            fun emitMoleculeBond(a: AtomImage, aRepId: Int, n: Int, aPos: Vec3) {
                val best = neighborRef(n, aPos) ?: return
                if (best.id == a.id) return
                val pair = minOf(a.id, best.id) to maxOf(a.id, best.id)
                if (pair in emittedPairs) return
                val bondLen = bondLengthByPair[minOf(aRepId, n) to maxOf(aRepId, n)] ?: return
                val d = distance(aPos, best.cartesianCoordinate.toVec3())
                // 键长验证(±20%):最近映像必须落在分子内键长附近,否则是错误映像。
                if (d > bondLen * 1.2 || d < bondLen * 0.8) return
                emittedPairs += pair
                val key = listOf(a.siteId, best.siteId).sorted().joinToString("\u0000")
                objects += BondInstance(
                    id = "molbond:${a.id}:${best.id}",
                    bond = Bond(a.id, best.id, d, moleculeRule, Int3(0, 0, 0)),
                    start = aPos,
                    end = best.cartesianCoordinate.toVec3(),
                    radius = options.bondRadius,
                    startMaterial = bondMaterial(a),
                    endMaterial = bondMaterial(best),
                    visible = options.showBonds && key !in options.hiddenBondKeys,
                )
            }
            // 显示的外部壳层原子:补分子拓扑邻居键。
            for (a in analysis.atoms) {
                if (!a.isShell || a.isBoundaryImage) continue
                if (!atomVisible(a)) continue
                val mol = moleculeIndexOf(a) ?: continue
                if (moleculeFullyHidden(mol)) continue
                val repId = repIdOf(a) ?: continue
                val aPos = a.cartesianCoordinate.toVec3()
                for (n in moleculeNeighbors[mol][repId].orEmpty()) {
                    emitMoleculeBond(a, repId, n, aPos)
                }
            }
            // 动态原子:补其分子拓扑邻居键(每个映像)。
            for ((maId, dyns) in dynamicAtomsByRep) {
                val mol = dynamicMolOf[maId] ?: continue
                for (dyn in dyns) {
                    val aPos = dyn.cartesianCoordinate.toVec3()
                    for (n in moleculeNeighbors[mol][maId].orEmpty()) {
                        emitMoleculeBond(dyn, maId, n, aPos)
                    }
                }
            }
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
}
