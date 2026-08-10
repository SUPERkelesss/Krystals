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
import com.krystals.crystal.core.math.angleDegrees
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.HydrogenBond
import com.krystals.crystal.core.model.Molecule
import com.krystals.crystal.core.model.MoleculeAtom
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary
import kotlin.math.ceil
import kotlin.math.floor
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.primitive.GatheredAtomInstance
import com.krystals.renderer.core.scene.GatheredAtom
import com.krystals.renderer.core.primitive.HbondInstance
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
    // Per v0.7.0: hydrogen-bond appearance (radius Å / opacity 0..1).
    val hbondRadius: Double = 0.05,
    val hbondOpacity: Double = 0.2,
    // Per v0.7.0: 氢键角度阈值(D–H···A 夹角,度)。角度 ≤ 阈值的氢键不显示。
    // 默认 110° 与检测层一致;阈值 ≤ 0 时不过滤。
    val hbondAngleThreshold: Double = 110.0,
    val bondColorMode: BondColorMode = BondColorMode.BICOLOR,
    val environment: RenderEnvironment = RenderEnvironment(),
    val structuralExpansion: Boolean = false,
    // Per molecule-extend: any molecule image intersecting the display region [0,ex]³ — in any
    // direction, negative sides included — is rendered whole (atoms, dynamic atoms and molecule
    // bonds). Hydrogen bonds render when both endpoint spheres are visible; they never trigger
    // molecule expansion (the molecule topology channel contains no hbonds).
    val moleculeExtend: Boolean = false,
    val molecules: List<Molecule> = emptyList(),
    // Show bonds between atoms that are already visible through first-order per-rule extension.
    // This never makes another shell atom visible and is ignored by molecule-extend mode.
    val showSecondaryExtendBonds: Boolean = true,
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
        // flag allows it. Per v0.7.0: same-atom periodic self-images never surface their far
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
        // 分子原子物理位置(含任一方向与显示区相交的周期映像),用于外部壳层原子的精确
        // 归属:壳层原子显示 ⟺ 其位置精确落在某可见分子映像的原子物理坐标上(而非仅 rep
        // 归属,否则相邻分子的边界映像会被误显示为"该分子的一部分")。映像 M+t 与显示区
        // [0,ex]³ 相交 ⟺ min+t ≤ ex 且 max+t ≥ 0,不限正负 —— 这些映像都要完整显示
        // (如尿素分子跨晶胞,(1,0.5)/(0.5,1)/上下底面的部分是相邻映像;低对称晶胞的
        // 负侧钻入映像同样补全)。分子展开归属/显示范围数据(见归属块)。
        var displayEx = Int3(1, 1, 1)
        val moleculePositions: List<List<Vec3>>
        // 每分子的显示映像原子(MoleculeAtom.id → 映像物理位置),供动态原子创建复用。
        val moleculeImageAtoms: List<List<Pair<Int, Vec3>>>
        // 分子拓扑邻居(原胞原子 id → 分子内邻居 id),用于键的分子归属判定。
        val moleculeNeighbors: List<Map<Int, Set<Int>>>
        // 分子原子按 id 索引(补全映像的平移基点:原胞代表 → 分子内物理位置)。
        val moleculeAtomById: HashMap<Int, MoleculeAtom>
        if (options.moleculeExtend) {
            val indexByAtomId = HashMap<Int, Int>()
            options.molecules.forEachIndexed { index, m -> m.atoms.forEach { indexByAtomId[it.id] = index } }
            moleculeAtomById = HashMap()
            options.molecules.forEach { m -> m.atoms.forEach { moleculeAtomById[it.id] = it } }
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
            // 显示范围边界:[0,ex]×[0,ey]×[0,ez] 闭区间(单胞 = [0,1]³;显示用超胞 =
            // [0,N]³)。primary 的 cellOffset ∈ [0,ex)³,最大值 +1 即闭区间上界;结构性
            // 超胞(晶胞本身经 3×3 变换扩大)的 primary 仍 ∈ [0,1)³ → (1,1,1)。
            // 必须先于映像生成计算:全方向 t 范围公式依赖 [0,ex] 上界。
            displayEx = Int3(
                (analysis.atoms.maxOfOrNull { if (it.isShell) 0 else it.cellOffset.x } ?: 0) + 1,
                (analysis.atoms.maxOfOrNull { if (it.isShell) 0 else it.cellOffset.y } ?: 0) + 1,
                (analysis.atoms.maxOfOrNull { if (it.isShell) 0 else it.cellOffset.z } ?: 0) + 1,
            )
            val images = options.molecules.map { m ->
                // 按连通分量(物理分子/笼)分组:合并条目(如 C60 的 240 原子 = 4 笼)必须
                // 逐笼生成映像,否则 4 笼被捆成一个整体生成 8 个整体映像 → 4×8=32 个笼
                // (用户只看到 8 顶点 + 6 面心 = 14 个)。
                val atomById = m.atoms.associateBy { it.id }
                val adj = HashMap<Int, MutableList<Int>>()
                for (b in m.bonds) {
                    adj.getOrPut(b.from) { mutableListOf() } += b.to
                    adj.getOrPut(b.to) { mutableListOf() } += b.from
                }
                val visited = HashSet<Int>()
                val components = mutableListOf<List<MoleculeAtom>>()
                for (a in m.atoms) {
                    if (!visited.add(a.id)) continue
                    val comp = mutableListOf<MoleculeAtom>()
                    val stack = ArrayDeque<Int>()
                    stack.add(a.id)
                    while (stack.isNotEmpty()) {
                        val id = stack.removeLast()
                        comp += atomById.getValue(id)
                        for (n in adj[id].orEmpty()) if (visited.add(n)) stack.add(n)
                    }
                    components += comp
                }
                // 每个分量(物理笼)独立计算与 [0,ex] 显示区相交的全方向周期映像。
                components.flatMap { comp ->
                    val fr = comp.map {
                        it.id to structure.lattice.toFractional(CartesianCoordinate(it.position.x, it.position.y, it.position.z))
                    }
                    val minX = fr.minOf { it.second.x }; val maxX = fr.maxOf { it.second.x }
                    val minY = fr.minOf { it.second.y }; val maxY = fr.maxOf { it.second.y }
                    val minZ = fr.minOf { it.second.z }; val maxZ = fr.maxOf { it.second.z }
                    // 全方向相交映像:映像 M+t 与 [0,ex] 显示区相交 ⟺ min+t ≤ ex 且
                    // max+t ≥ 0 → t ∈ [ceil(-max), floor(ex-min)],不限正负(低对称
                    // 晶胞的负侧钻入映像由此补全;C60 角笼/面心笼 t∈{0,1}³ 不变)。
                    // 全方向相交映像:映像 M+t 与 [0,ex] 显示区相交 ⟺ min+t ≤ ex 且
                    // max+t ≥ 0 → t ∈ [ceil(-max), floor(ex-min)],不限正负(低对称
                    // 晶胞的负侧钻入映像由此补全;C60 角笼/面心笼 t∈{0,1}³ 不变)。
                    // ε 取 +/− 对称方向:分量恰在 0 面(min=0)时 t=0 必须保留,
                    // 恰在 ex 面(max=ex)时 t=0..1 必须保留 —— ceil(-max−ε) 对
                    // max=0 得 0(旧式 +ε 会得 1 而清空 t 范围,I2 0 面分子整簇丢失)。
                    val txRange = ceil(-maxX - 1e-6).toInt()..floor(displayEx.x - minX + 1e-6).toInt()
                    val tyRange = ceil(-maxY - 1e-6).toInt()..floor(displayEx.y - minY + 1e-6).toInt()
                    val tzRange = ceil(-maxZ - 1e-6).toInt()..floor(displayEx.z - minZ + 1e-6).toInt()
                    val result = mutableListOf<Pair<Int, Vec3>>()
                    for (tx in txRange) for (ty in tyRange) for (tz in tzRange) {
                        for ((id, f) in fr) {
                            val shifted = structure.lattice.toCartesian(
                                FractionalCoordinate(f.x + tx, f.y + ty, f.z + tz),
                            )
                            result += id to Vec3(shifted.x, shifted.y, shifted.z)
                        }
                    }
                    result
                }.distinct()
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
            moleculeAtomById = HashMap()
        }

        /** 场景原子 → 原胞代表原子 id(分子拓扑节点)。 */
        fun repIdOf(atom: AtomImage): Int? = when {
            !atom.isShell && atom.cellOffset == Int3(0, 0, 0) -> atom.id.toInt()
            else -> repBySiteId[atom.siteId]?.firstOrNull { isSameAtomPeriodicImage(it, atom) }?.id?.toInt()
        }

        /** 场景原子 → 分子索引:原胞原子直查;shell/边界映像经原胞代表(re-same-site + 整数平移)查。 */
        fun moleculeIndexOf(atom: AtomImage): Int? {
            moleculeIndexByRepId[atom.id]?.let { return it }
            // Per 2026-08-09: 原实现对非 shell 原子直接返回 null —— 扩展晶胞下的
            // primary(cellOffset ≠ 0,不在 rep 表)因此归属失败,跨子晶胞面的分子内键
            // 在普通键通道走 mi==null 分支被隐藏。超胞 primary 与外部壳层一样,经
            // siteId + 整数平移找回原胞代表再查分子。
            val rep = repBySiteId[atom.siteId]?.firstOrNull { isSameAtomPeriodicImage(it, atom) } ?: return null
            return moleculeIndexByRepId[rep.id]
        }

        /** 分子内所有原胞原子都被 hiddenSiteIds 覆盖 → 整分子(含其映像与键)隐藏。 */
        fun moleculeFullyHidden(mol: Int): Boolean {
            val siteIds = moleculeSiteIds[mol]
            return siteIds.isNotEmpty() && siteIds.all { it in options.hiddenSiteIds }
        }

        /** 原子是否落在其所属分子的原子物理坐标上(跨胞键按此判定,端点球可能隐藏)。 */
        fun atMoleculePosition(atom: AtomImage): Boolean {
            val mol = moleculeIndexOf(atom) ?: return false
            if (moleculeFullyHidden(mol)) return false
            val pos = atom.cartesianCoordinate.toVec3()
            return moleculePositions[mol].any { distance(it, pos) < 1e-3 }
        }

        fun atomVisible(atom: AtomImage): Boolean {
            if (atom.siteId in options.hiddenSiteIds) return false
            if (!options.moleculeExtend) {
                return !atom.isShell || atom.isBoundaryImage || atom.id in externallyVisible
            }
            // 分子展开(全方向完整映像):原胞 primary 与边界映像恒显 —— 其位置必被某
            // 相交映像覆盖(含负侧),不再有"孤球"问题,包裹副本特判随之取消。
            if (!atom.isShell || atom.isBoundaryImage) return true
            val mol = moleculeIndexOf(atom)
            if (mol == null) {
                // 非分子壳层:[0,ex] 闭区间内显示(顶面 frac=1 规则,5722d2e)。
                return atom.fractionalCoordinate.let { f ->
                    f.x in -1e-6..(displayEx.x + 1e-6) &&
                        f.y in -1e-6..(displayEx.y + 1e-6) &&
                        f.z in -1e-6..(displayEx.z + 1e-6)
                }
            }
            // 分子壳层:位置落在某可见分子映像的原子物理坐标上即显示
            // (atMoleculePosition 内部已查 moleculeFullyHidden)。
            return atMoleculePosition(atom)
        }

        fun atomMaterial(atom: AtomImage): Material =
            options.atomMaterialBySite[atom.siteId] ?: options.defaultAtomMaterial

        fun bondMaterial(atom: AtomImage): Material = when (options.bondColorMode) {
            BondColorMode.BICOLOR -> options.bondMaterialBySite[atom.siteId] ?: options.defaultBondMaterial
            BondColorMode.UNICOLOR -> options.defaultBondMaterial
        }

        // Per v0.7.0: build gathered-atom groups for co-located atoms of different sites.
        val colorBySite = options.atomMaterialBySite.mapValues { it.value.argb }
        val gathered = GatheredAtomGrouper.groupWithIndex(analysis.atoms, colorBySite)
        val groups = gathered.groups
        val groupByMemberId = gathered.byMemberId

        val objects = mutableListOf<RenderObject>()

        // Per v0.7.0: emit all atoms as AtomInstances (backward-compat for BondNetwork adapter,
        // picking, info windows), then additionally emit GatheredAtomInstances for groups.
        // Backends that understand GatheredAtomInstance skip drawing individual member atoms.
        val groupCenterById = linkedMapOf<String, Vec3>()
        val groupRadiusById = linkedMapOf<String, Double>()  // for surface-anchored bonds
        // Per 2026-08-09: 动态 gathered 组(分子映像补全合成)的成员索引 —— emitMoleculeBond
        // 判断端点是否属于动态组,把键锚定到组球面(与场景组共用 groupRadiusById)。
        val dynGroupByMember = HashMap<Long, GatheredAtom>()
        for (g in groups) {
            val maxRadius = g.memberAtomIds.mapNotNull { id -> atomById[id]?.let { options.atomRadiusByElement[it.species.symbol] ?: options.defaultAtomRadius } }.maxOrNull() ?: options.defaultAtomRadius
            // Per v0.7.0: group visibility follows ANY member's atom visibility, including
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
        // Per v0.7.0: boundary-image (shell) atoms whose cartesian position coincides with a
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
        // Per v0.7.0: groups anchor bonds at the sphere SURFACE; duplicate member→same-target
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
            bondMembersByKey.getOrPut(dedupeKey) { mutableListOf() } += Triple(bond.atomA, bondMaterial(start), bondMaterial(end))
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

            // Per v0.7.0: anchor bonds at sphere SURFACE, not center.
            val rawStart = startGroup?.center ?: start.cartesianCoordinate.toVec3()
            val rawEnd = endGroup?.center ?: end.cartesianCoordinate.toVec3()
            val dir = (rawEnd - rawStart).normalized()
            val startRadius = if (startGroup != null) (groupRadiusById[startKey] ?: 0.0) else 0.0
            val endRadius = if (endGroup != null) (groupRadiusById[endKey] ?: 0.0) else 0.0
            val startPos = rawStart + dir * startRadius
            val endPos = rawEnd - dir * endRadius

            // Per v0.7.0 item 3: blended material for duplicate member→same-target bonds.
            val members = bondMembersByKey[dedupeKey].orEmpty()
            val (startMat, endMat) = if (members.size >= 2 && endGroup != null) {
                // Group end: occ-weighted mixedColor of bonding members ↔ target color.
                val groupMembers = members.map { (memberId, sm, _) -> memberId to sm }.distinct()
                val totalOcc = groupMembers.sumOf { (mid, _) -> atomById[mid]?.occupancy ?: 1.0 }
                val mixedStart = if (startGroup != null && groupMembers.size >= 2) {
                    blendMaterials(groupMembers, totalOcc)
                } else bondMaterial(start)
                val targetEnd = bondMaterial(end)
                mixedStart to targetEnd
            } else if (members.size >= 2 && startGroup != null) {
                val groupMembers = members.map { (memberId, _, em) -> memberId to em }.distinct()
                val totalOcc = groupMembers.sumOf { (mid, _) -> atomById[mid]?.occupancy ?: 1.0 }
                val targetStart = bondMaterial(start)
                val mixedEnd = blendMaterials(groupMembers, totalOcc)
                targetStart to mixedEnd
            } else {
                bondMaterial(start) to bondMaterial(end)
            }

            // Per v0.7.0: single-cell ("no extension") bond visibility. The old rule only gated
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
                // (分子间氢键不在此通道 —— 见下方氢键 pass 的 moleculeExtend 分支。)
                options.moleculeExtend -> {
                    // 分子展开:两端必须属于同一分子、拓扑相邻、且端点原子均显示
                    // (拓扑相邻排除同原子自像键等非分子内键)。
                    val mi = moleculeIndexOf(start)
                    val mj = moleculeIndexOf(end)
                    if (mi == null && mj == null) {
                        // 两端都不属于任何分子(如超胞里原胞之外的独立原子):它们是
                        // 显示范围内的真实原子,键按端点可见性显示 —— 不是"相邻分子
                        // 的映像",也不属于任何分子的共价键。同原子周期自像仍隐藏。
                        if (isSameAtomPeriodicImage(start, end)) {
                            false
                        } else {
                            atomVisible(start) && atomVisible(end)
                        }
                    } else if (mi == null || mi != mj || moleculeFullyHidden(mi)) {
                        false
                    } else {
                        val sRep = repIdOf(start)
                        val eRep = repIdOf(end)
                        // 分子内键:拓扑相邻 + 两端"原子球可见或位置落在分子物理坐标上"
                        // (primary 恒显、外部壳层由位置匹配承载 —— 跨胞键以壳层坐标
                        // 穿过边界绘制,如 Cl1@0.05 ↔ Cl2 的 (1,0,0) 映像 1.95)。
                        val sOk = atomVisible(start) || atMoleculePosition(start)
                        val eOk = atomVisible(end) || atMoleculePosition(end)
                        sRep != null && eRep != null && sRep != eRep &&
                            eRep in moleculeNeighbors[mi][sRep].orEmpty() &&
                            sOk && eOk
                    }
                }
                !start.isShell && !end.isShell -> true
                // Same-atom periodic self-images (an atom bonded to its own periodic image)
                // are never rendered — they are not chemical bonds, and the default METALS_ONLY
                // extension preference would otherwise resurrect the Ca-Ca "bonds" in CaC2.
                isSameAtomPeriodicImage(start, end) -> false
                else -> {
                    if (start.isExternalShell || end.isExternalShell) {
                        // The rule flag owns first-order extension. Secondary extension only fills
                        // bonds whose endpoints were already made visible by other first-order
                        // bonds; it never changes atom visibility.
                        bond.rule.shouldExtendAcrossCell(start.siteId, end.isExternalShell) ||
                            options.showSecondaryExtendBonds && atomVisible(start) && atomVisible(end)
                    } else {
                        // Boundary-image keys (one or both ends are displayed face images):
                        // always shown — both ends are displayed atoms, their bonds are part
                        // of the picture (v0.7.0 restored after the v0.7.0 over-filtering).
                        true
                    }
                }
            }
            val visible = options.showBonds && bond.rule.key !in options.hiddenBondKeys && externalAllowed
            // Per v0.7.0: no heteronuclear dedup — every bond between displayed atoms
            // (primary or boundary-image) is rendered, so face images keep ALL their bonds.
            // Same-atom self-images and out-of-cell neighbours were already gated above.
            val finalVisible = visible
            objects += BondInstance(
                id = "bond:${bond.atomA}:${bond.atomB}:${bond.offsetB.x}:${bond.offsetB.y}:${bond.offsetB.z}:$index",
                bond = bond,
                start = startPos,
                end = endPos,
                radius = options.bondRadius,
                startMaterial = startMat,
                endMaterial = endMat,
                visible = finalVisible,
            )
        }

        // BondDetector materialises external atoms on demand from primary/boundary centres, so a
        // bond whose two ends are external may be absent from analysis.bonds even when both atoms
        // were made visible by first-order extension. Reapply the existing periodic bond topology
        // to visible images and emit only missing endpoint pairs. The visible atom set is fixed
        // before this pass, which prevents secondary bonds from recursively extending the shell.
        if (options.showSecondaryExtendBonds && !options.moleculeExtend && options.showBonds && externallyVisible.isNotEmpty()) {
            val zero = Int3(0, 0, 0)
            val representativesBySite = analysis.atoms.asSequence()
                .filter { !it.isShell && it.cellOffset == zero }
                .groupBy { it.siteId }

            fun representativeOf(atom: AtomImage): AtomImage? {
                if (!atom.isShell && atom.cellOffset == zero) return atom
                return representativesBySite[atom.siteId]
                    ?.firstOrNull { representative -> isSameAtomPeriodicImage(representative, atom) }
            }

            fun imageShift(representative: AtomImage, atom: AtomImage): Int3? {
                val dx = atom.fractionalCoordinate.x - representative.fractionalCoordinate.x
                val dy = atom.fractionalCoordinate.y - representative.fractionalCoordinate.y
                val dz = atom.fractionalCoordinate.z - representative.fractionalCoordinate.z
                val rx = kotlin.math.round(dx).toInt()
                val ry = kotlin.math.round(dy).toInt()
                val rz = kotlin.math.round(dz).toInt()
                if (kotlin.math.abs(dx - rx) >= 1e-4 || kotlin.math.abs(dy - ry) >= 1e-4 || kotlin.math.abs(dz - rz) >= 1e-4) return null
                return Int3(rx, ry, rz)
            }

            fun plus(a: Int3, b: Int3) = Int3(a.x + b.x, a.y + b.y, a.z + b.z)
            fun minus(a: Int3, b: Int3) = Int3(a.x - b.x, a.y - b.y, a.z - b.z)

            data class TopologyTemplate(
                val repA: Long,
                val repB: Long,
                val shiftBFromA: Int3,
                val rule: BondRule,
                val distance: Double,
            )

            val representativeByAtomId = HashMap<Long, AtomImage>()
            val shiftByAtomId = HashMap<Long, Int3>()
            for (atom in analysis.atoms) {
                val representative = representativeOf(atom) ?: continue
                val shift = imageShift(representative, atom) ?: continue
                representativeByAtomId[atom.id] = representative
                shiftByAtomId[atom.id] = shift
            }

            val templates = linkedMapOf<Triple<Long, Long, Int3>, TopologyTemplate>()
            for (bond in analysis.bonds) {
                val startRep = representativeByAtomId[bond.atomA] ?: continue
                val endRep = representativeByAtomId[bond.atomB] ?: continue
                if (startRep.id == endRep.id) continue
                val startShift = shiftByAtomId[bond.atomA] ?: continue
                val endShift = shiftByAtomId[bond.atomB] ?: continue
                val template = if (startRep.id < endRep.id) {
                    TopologyTemplate(startRep.id, endRep.id, minus(endShift, startShift), bond.rule, bond.distance)
                } else {
                    TopologyTemplate(endRep.id, startRep.id, minus(startShift, endShift), bond.rule, bond.distance)
                }
                templates.putIfAbsent(Triple(template.repA, template.repB, template.shiftBFromA), template)
            }

            val visibleByPeriodicKey = HashMap<Pair<Long, Int3>, AtomImage>()
            val visibleByRepresentative = HashMap<Long, MutableList<AtomImage>>()
            for (atom in analysis.atoms) {
                if (!atomVisible(atom)) continue
                val representative = representativeByAtomId[atom.id] ?: continue
                val shift = shiftByAtomId[atom.id] ?: continue
                visibleByPeriodicKey.putIfAbsent(representative.id to shift, atom)
                visibleByRepresentative.getOrPut(representative.id) { mutableListOf() } += atom
            }

            fun displayKey(atom: AtomImage): String = groupByMemberId[atom.id]
                ?.let { group -> "g:${group.memberAtomIds.sorted().joinToString(",")}" }
                ?: "a:${atom.id}"

            fun displayPair(a: AtomImage, b: AtomImage): Pair<String, String> {
                val ka = displayKey(a)
                val kb = displayKey(b)
                return if (ka <= kb) ka to kb else kb to ka
            }

            val occupiedDisplayPairs = HashSet<Pair<String, String>>()
            for (bond in analysis.bonds) {
                val start = atomById[bond.atomA] ?: continue
                val end = atomById[bond.atomB] ?: continue
                occupiedDisplayPairs += displayPair(start, end)
            }

            var secondaryIndex = 0
            for (template in templates.values) {
                val imagesA = visibleByRepresentative[template.repA].orEmpty()
                for (start in imagesA) {
                    val startShift = shiftByAtomId[start.id] ?: continue
                    val end = visibleByPeriodicKey[template.repB to plus(startShift, template.shiftBFromA)] ?: continue
                    if (!start.isExternalShell && !end.isExternalShell) continue
                    if (isSameAtomPeriodicImage(start, end)) continue
                    if (template.rule.key in options.hiddenBondKeys) continue

                    val pair = displayPair(start, end)
                    if (pair in occupiedDisplayPairs) continue

                    val startGroup = groupByMemberId[start.id]
                    val endGroup = groupByMemberId[end.id]
                    if (startGroup != null && endGroup != null && startGroup == endGroup) continue
                    val rawStart = startGroup?.center ?: start.cartesianCoordinate.toVec3()
                    val rawEnd = endGroup?.center ?: end.cartesianCoordinate.toVec3()
                    val actualDistance = distance(start.cartesianCoordinate.toVec3(), end.cartesianCoordinate.toVec3())
                    if (kotlin.math.abs(actualDistance - template.distance) > 1e-3) continue
                    occupiedDisplayPairs += pair
                    val direction = (rawEnd - rawStart).normalized()
                    val startRadius = startGroup?.let {
                        groupRadiusById["gathered:${it.memberAtomIds.sorted().joinToString(",")}"] ?: 0.0
                    } ?: 0.0
                    val endRadius = endGroup?.let {
                        groupRadiusById["gathered:${it.memberAtomIds.sorted().joinToString(",")}"] ?: 0.0
                    } ?: 0.0
                    val syntheticBond = Bond(
                        atomA = start.id,
                        atomB = end.id,
                        distance = actualDistance,
                        rule = template.rule,
                        offsetB = template.shiftBFromA,
                    )
                    objects += BondInstance(
                        id = "secondary-bond:${start.id}:${end.id}:${secondaryIndex++}",
                        bond = syntheticBond,
                        start = rawStart + direction * startRadius,
                        end = rawEnd - direction * endRadius,
                        radius = options.bondRadius,
                        startMaterial = bondMaterial(start),
                        endMaterial = bondMaterial(end),
                        visible = true,
                    )
                }
            }
        }

        // Hydrogen-bond pass (per hbond-model): hbonds are a separate channel and are no longer
        // part of analysis.bonds. Visibility and anchoring mirror the pre-separation behaviour:
        //  - moleculeExtend: show iff both endpoint atoms are visible (hbonds are intermolecular,
        //    never molecule-internal; the bond itself is validated by BondDetector);
        //  - otherwise the same externalAllowed rules as normal bonds, consulting the rule's
        //    extend flags for external shells (production hbond rules never extend, so
        //    external-shell hbonds stay hidden as before);
        //  - dedupe by (endKey pair, offsetB). Overlap with the normal-bond dedupe keys cannot
        //    occur: the hbond window starts at the covalent max and the keys embed atom ids.
        // Per v0.7.0: covalent-partner map for the D–H···A angle filter — H atom id → its
        // covalently bonded atoms (same cut as HbondChecking's covalentPartners: bonds from
        // analysis.bonds). Built once per scene build.
        val covalentPartnersByAtom = HashMap<Long, MutableList<AtomImage>>()
        for (bond in analysis.bonds) {
            val a = atomById[bond.atomA] ?: continue
            val b = atomById[bond.atomB] ?: continue
            if (a.species.symbol == "H") covalentPartnersByAtom.getOrPut(a.id) { mutableListOf() } += b
            if (b.species.symbol == "H") covalentPartnersByAtom.getOrPut(b.id) { mutableListOf() } += a
        }
        val seenHbondKeys = mutableSetOf<Triple<Any, Any, Triple<Int, Int, Int>>>()
        // Per molecule-extend(决策 2):氢键端点位置有可见球才显示。可见球位置 =
        // 可见场景原子球 ∪ 显示带内、位点未隐藏、分子未整隐的映像原子位置
        // (映像位置必有球 —— 场景原子或动态原子承载;动态原子在后方补全块发射,
        // 此处仅借其位置做端点判定,无需调整 pass 顺序)。
        val visibleBallPositions: List<Vec3> = if (options.moleculeExtend) {
            val fromScene = objects.filterIsInstance<AtomInstance>().filter { it.visible }
                .map { it.atom.cartesianCoordinate.toVec3() }
            val fromImages = moleculeImageAtoms.flatMapIndexed { mol, imgs ->
                if (moleculeFullyHidden(mol)) emptyList()
                else imgs.filter { (maId, _) ->
                    moleculeAtomById[maId]?.siteId !in options.hiddenSiteIds
                }.map { it.second }
            }.filter { p ->
                val f = structure.lattice.toFractional(CartesianCoordinate(p.x, p.y, p.z))
                f.x in -1.0 - 1e-6..(displayEx.x + 1.0 + 1e-6) &&
                    f.y in -1.0 - 1e-6..(displayEx.y + 1.0 + 1e-6) &&
                    f.z in -1.0 - 1e-6..(displayEx.z + 1.0 + 1e-6)
            }
            fromScene + fromImages
        } else emptyList()
        fun hasVisibleBallAt(p: Vec3): Boolean = visibleBallPositions.any { distance(it, p) < 1e-3 }
        analysis.hbonds.forEachIndexed { index, hbond ->
            val start = atomById[hbond.donorId]
                ?: error("hbond ${hbond.donorId}-${hbond.acceptorId} references missing atom ${hbond.donorId}")
            val end = atomById[hbond.acceptorId]
                ?: error("hbond ${hbond.donorId}-${hbond.acceptorId} references missing atom ${hbond.acceptorId}")

            val startGroup = groupByMemberId[hbond.donorId]
            val endGroup = groupByMemberId[hbond.acceptorId]

            // Drop intra-group hbonds (both ends inside the same gathered group).
            if (startGroup != null && endGroup != null && startGroup == endGroup) return@forEachIndexed

            val startKey: Any = startGroup?.let { "gathered:${it.memberAtomIds.sorted().joinToString(",")}" } ?: hbond.donorId
            val endKey: Any = endGroup?.let { "gathered:${it.memberAtomIds.sorted().joinToString(",")}" } ?: hbond.acceptorId
            val dedupeKey = Triple(startKey, endKey, Triple(hbond.offsetB.x, hbond.offsetB.y, hbond.offsetB.z))
            if (!seenHbondKeys.add(dedupeKey)) return@forEachIndexed

            // Per v0.7.0: anchor bonds at sphere SURFACE, not center (same as normal bonds).
            val rawStart = startGroup?.center ?: start.cartesianCoordinate.toVec3()
            val rawEnd = endGroup?.center ?: end.cartesianCoordinate.toVec3()
            val dir = (rawEnd - rawStart).normalized()
            val startRadius = if (startGroup != null) (groupRadiusById[startKey] ?: 0.0) else 0.0
            val endRadius = if (endGroup != null) (groupRadiusById[endKey] ?: 0.0) else 0.0
            val startPos = rawStart + dir * startRadius
            val endPos = rawEnd - dir * endRadius

            val externalAllowed = when {
                // 分子展开:两端球都可见才显示(不漏 —— 分子完整化后胞内分子与其映像
                // 间的氢键两端必有球;不悬空 —— 指向未显示胞外原子的氢键不画;
                // 氢键永不触发分子展开 —— 需求 2,分子拓扑通道本就不含氢键)。
                options.moleculeExtend ->
                    hasVisibleBallAt(start.cartesianCoordinate.toVec3()) &&
                        hasVisibleBallAt(end.cartesianCoordinate.toVec3())
                !start.isShell && !end.isShell -> true
                // Same-atom periodic self-images are never rendered (see normal-bond pass).
                isSameAtomPeriodicImage(start, end) -> false
                else -> {
                    // Pre-separation the bond sweep's centre (start) was never an external
                    // shell, so `start.isExternalShell || end.isExternalShell` implied the
                    // external end was `end`; with donor→acceptor orientation the external
                    // end can be either side. Resolve it explicitly and consult the rule's
                    // extend flag from the INSIDE end's site (identical semantics).
                    when {
                        start.isExternalShell && end.isExternalShell -> false
                        start.isExternalShell -> hbond.shouldExtendAcrossCell(end.siteId, true)
                        end.isExternalShell -> hbond.shouldExtendAcrossCell(start.siteId, true)
                        else -> true
                    }
                }
            }
            // D–H···A display threshold. The network contains concrete periodic images, so the
            // angle must use their actual vectors; minimum-image wrapping here would make distinct
            // O images share a direction. Auto-detected hbonds without a D-H partner stay hidden;
            // custom rules retain their explicit user-authored behaviour when the donor bond is absent.
            val partners = covalentPartnersByAtom[start.id].orEmpty()
            val angle = hbondAngleDegrees(start, end, partners)
            val angleOk = when {
                angle != null -> options.hbondAngleThreshold <= 0.0 || angle > options.hbondAngleThreshold
                else -> !hbond.isAutoDetected
            }
            val visible = options.showBonds && hbond.ruleKey !in options.hiddenBondKeys && externalAllowed && angleOk
            objects += HbondInstance(
                id = "hbond:${hbond.donorId}:${hbond.acceptorId}:${hbond.offsetB.x}:${hbond.offsetB.y}:${hbond.offsetB.z}:$index",
                hbond = hbond,
                start = startPos,
                end = endPos,
                radius = options.hbondRadius,
                material = HbondPattern.material(options.hbondOpacity.toFloat()),
                visible = visible,
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
            val dynamicAtomsByRep = HashMap<Int, MutableList<AtomImage>>()  // MoleculeAtom.id → 动态原子
            val dynamicMolOf = HashMap<Int, Int>()                          // MoleculeAtom.id → 分子索引
            var nextDynId = -1L
            moleculeImageAtoms.forEachIndexed { molIndex, images ->
                if (moleculeFullyHidden(molIndex)) return@forEachIndexed
                for ((maId, pos) in images) {
                    if (sceneAtomAt(maId, pos) != null) continue
                    val ma = moleculeAtomById[maId] ?: continue
                    // 动态球只渲染在显示范围扩展带 [-1, ex+1]³ 内(映像原子球:8 顶点 +
                    // 6 面心等);超出范围的分子延伸(如 20.8 格的尿素末端)不渲染球,
                    // 仅作键端点。
                    val posFrac = structure.lattice.toFractional(CartesianCoordinate(pos.x, pos.y, pos.z))
                    val inDisplayBand = posFrac.x in -1.0 - 1e-6..(displayEx.x + 1.0 + 1e-6) &&
                        posFrac.y in -1.0 - 1e-6..(displayEx.y + 1.0 + 1e-6) &&
                        posFrac.z in -1.0 - 1e-6..(displayEx.z + 1.0 + 1e-6)
                    val dyn = AtomImage(
                        id = nextDynId--,
                        siteId = ma.siteId,
                        siteLabel = ma.label,
                        species = ma.species,
                        // Per 2026-08-09: 动态原子必须用真实分数坐标(不是 (0,0,0))——
                        // 信息面板/坐标读取显示真实位置;isSameAtomPeriodicImage 的
                        // 整数平移判定(分子归属)依赖 frac,修复前全部失败并误落
                        // "非分子壳层 [0,ex]" 可见性分支。
                        fractionalCoordinate = posFrac,
                        cartesianCoordinate = CartesianCoordinate(pos.x, pos.y, pos.z),
                        // Per 2026-08-09: 动态原子必须继承网络原子的真实 occupancy
                        // (MoleculeAtom 不携带)——否则共位动态原子的 gathered 切片比例
                        // 失真(如 K3 occ0.5 + Na occ0.3 会按 1.0+1.0 归一化成 0.5/0.5,
                        // 而 t=0 副本是 0.625/0.375 + 20% 背景,周期副本外观不一致)。
                        occupancy = atomById[maId.toLong()]?.occupancy ?: 1.0,
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
                        visible = ma.siteId !in options.hiddenSiteIds && inDisplayBand,
                    )
                }
            }

            // Per 2026-08-09: 动态原子同样按共位分组 —— 分子映像的周期副本里,共位原子
            // (不同 siteId 同位置,如 K3+Na 混合占位)逐 MoleculeAtom 各生成一个实心球,
            // 在 t≠0 副本位置(无场景原子)会叠成两个纯色球,而 t=0 副本是 gathered pie,
            // 周期副本外观不一致(用户报告"按分子拓展时 gatheredAtom 显示错误的材质")。
            // 对动态原子做 GatheredAtomGrouper 分组并发射 GatheredAtomInstance:成员 id
            // 为负,InstanceManager 的 gatheredByMemberId 据此跳过成员球,pie 材质一致。
            val dynamicAtoms = dynamicAtomsByRep.values.flatten()
            if (dynamicAtoms.size >= 2) {
                val dynById = dynamicAtoms.associateBy { it.id }
                for (g in GatheredAtomGrouper.group(dynamicAtoms, colorBySite)) {
                    val maxRadius = g.memberAtomIds.mapNotNull { id ->
                        dynById[id]?.let { options.atomRadiusByElement[it.species.symbol] ?: options.defaultAtomRadius }
                    }.maxOrNull() ?: options.defaultAtomRadius
                    val anyVisible = g.memberAtomIds.any { id ->
                        objects.filterIsInstance<AtomInstance>().any { it.atom.id == id && it.visible }
                    }
                    val remainderMat = Material(argb = g.mixedColor, opacity = 0.25, reflective = false)
                    // Per 2026-08-09: 动态组与场景组共用 groupRadiusById/groupCenterById ——
                    // 分子补全键(emitMoleculeBond)与二级键据此把端点锚定到组球面,
                    // 获得与普通球一致的 0.99*radius 键缩短观感(否则键从球心穿出 pie)。
                    val gatheredId = "gathered:${g.memberAtomIds.sorted().joinToString(",")}"
                    groupCenterById[gatheredId] = g.center
                    groupRadiusById[gatheredId] = maxRadius
                    g.memberAtomIds.forEach { dynGroupByMember[it] = g }
                    objects += GatheredAtomInstance(
                        id = gatheredId,
                        gathered = g,
                        radius = maxRadius,
                        remainderMaterial = remainderMat,
                        visible = anyVisible,
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
                val bestPos = best.cartesianCoordinate.toVec3()
                val d = distance(aPos, bestPos)
                // 键长验证(±20%):最近映像必须落在分子内键长附近,否则是错误映像。
                if (d > bondLen * 1.2 || d < bondLen * 0.8) return
                emittedPairs += pair
                val key = listOf(a.siteId, best.siteId).sorted().joinToString("\u0000")
                // Per 2026-08-09: gathered 端点(场景组或动态组)的分子补全键锚定到组球面,
                // 与普通键通道/二级键一致 —— 否则键从球心穿出 pie,没有普通球
                // "0.99*radius 缩进"的紧贴观感(InstanceManager 对 gathered 成员跳过
                // clip,锚定必须在 builder 侧完成)。
                val dir = (bestPos - aPos).normalized()
                val aGroup = dynGroupByMember[a.id] ?: groupByMemberId[a.id]
                val bGroup = dynGroupByMember[best.id] ?: groupByMemberId[best.id]
                fun groupRadius(g: GatheredAtom?): Double =
                    g?.let { groupRadiusById["gathered:${it.memberAtomIds.sorted().joinToString(",")}"] ?: 0.0 } ?: 0.0
                val startPos = aPos + dir * groupRadius(aGroup)
                val endPos = bestPos - dir * groupRadius(bGroup)
                objects += BondInstance(
                    id = "molbond:${a.id}:${best.id}",
                    bond = Bond(a.id, best.id, d, moleculeRule, Int3(0, 0, 0)),
                    start = startPos,
                    end = endPos,
                    radius = options.bondRadius,
                    startMaterial = bondMaterial(a),
                    endMaterial = bondMaterial(best),
                    visible = options.showBonds && key !in options.hiddenBondKeys,
                )
            }
            // 显示原子(含 primary)或位置匹配的原子(外部壳层不渲染球体,但其坐标是
            // 分子内键的端点,仍需补其拓扑邻居键):BondDetector 从包裹中心扫描时距离
            // 膨胀,漏掉两端都包裹的分子内键(如角笼包裹八分体内部边)与壳-壳键 ——
            // 此处按分子拓扑 + 键长验证补齐,键延伸式显示;emittedPairs 复用
            // analysis.bonds 种子去重,已存在的网键不重复发射。
            for (a in analysis.atoms) {
                if (a.isBoundaryImage) continue
                if (!atomVisible(a) && !atMoleculePosition(a)) continue
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

            // Per v0.7.0 (4.2): hbond 映像补全 —— 动态原子(本块合成的显示原子,不在
            // BondNetwork 中)无法被 BondDetector 生成氢键,导致"晶胞外的两个可见原子
            // 形成的氢键"不渲染。网络氢键模板的周期副本平移不改变 D–H···A 几何
            // (键长/角度均为平移不变量),故对每个模板,在"可见供体 H 球"与"可见受体
            // 球"中匹配:位置差与模板偏移 ℤ³ 同余(1e-4)且距离一致(±1e-2)→ 补发
            // HbondInstance。两端球都可见才显示(决策 2:不悬空、不把未显示原子拉进来);
            // 每 H 球取最短(与检测层 per-H 最短口径一致);角度沿用模板的显示过滤结果
            // (平移不变,副本同角)。
            if (options.showBonds && analysis.hbonds.isNotEmpty()) {
                val visibleBallsBySite = HashMap<String, MutableList<Vec3>>()
                objects.filterIsInstance<AtomInstance>().filter { it.visible }
                    .forEach { visibleBallsBySite.getOrPut(it.atom.siteId) { mutableListOf() } += it.atom.cartesianCoordinate.toVec3() }
                val occupiedHbondPairs = HashSet<Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>>()
                fun ballKey(p: Vec3) = Triple((p.x * 1000).toInt(), (p.y * 1000).toInt(), (p.z * 1000).toInt())
                // 占位键用端点原子中心(而非主 pass 的球面锚定端点):分组原子的锚定
                // 端与中心相差 ~0.35Å,直接按实例端点键会漏判 → 同一物理氢键被重复发射。
                objects.filterIsInstance<HbondInstance>().forEach { inst ->
                    val d = atomById[inst.hbond.donorId]?.cartesianCoordinate?.toVec3()
                    val a = atomById[inst.hbond.acceptorId]?.cartesianCoordinate?.toVec3()
                    if (d != null && a != null) occupiedHbondPairs += ballKey(d) to ballKey(a)
                }
                fun nearInt(v: Double) = kotlin.math.abs(v - kotlin.math.round(v)) < 1e-4
                // H 球位置键 → (模板, 供体球位置, 受体球位置, 距离):跨模板去重,每 H 球取最短。
                val bestCopyPerHBall = HashMap<Triple<Int, Int, Int>, Pair<HydrogenBond, Triple<Vec3, Vec3, Double>>>()
                analysis.hbonds.forEach { hbond ->
                    if (hbond.ruleKey in options.hiddenBondKeys) return@forEach
                    val donor = atomById[hbond.donorId] ?: return@forEach
                    val acceptor = atomById[hbond.acceptorId] ?: return@forEach
                    val partners = covalentPartnersByAtom[donor.id].orEmpty()
                    val angleOk = options.hbondAngleThreshold <= 0.0 ||
                        hbondAngleDegrees(donor, acceptor, partners)
                            ?.let { it > options.hbondAngleThreshold } ?: true
                    if (!angleOk) return@forEach
                    val donorBalls = visibleBallsBySite[donor.siteId].orEmpty()
                    val acceptorBalls = visibleBallsBySite[acceptor.siteId].orEmpty()
                    if (donorBalls.isEmpty() || acceptorBalls.isEmpty()) return@forEach
                    val shiftX = acceptor.fractionalCoordinate.x - donor.fractionalCoordinate.x
                    val shiftY = acceptor.fractionalCoordinate.y - donor.fractionalCoordinate.y
                    val shiftZ = acceptor.fractionalCoordinate.z - donor.fractionalCoordinate.z
                    for (p in donorBalls) {
                        val pFrac = structure.lattice.toFractional(CartesianCoordinate(p.x, p.y, p.z))
                        var best: Triple<Vec3, Vec3, Double>? = null
                        for (q in acceptorBalls) {
                            val qFrac = structure.lattice.toFractional(CartesianCoordinate(q.x, q.y, q.z))
                            if (!nearInt((qFrac.x - pFrac.x) - shiftX) ||
                                !nearInt((qFrac.y - pFrac.y) - shiftY) ||
                                !nearInt((qFrac.z - pFrac.z) - shiftZ)
                            ) continue
                            val d = distance(p, q)
                            if (kotlin.math.abs(d - hbond.distance) > 1e-2) continue
                            if (best == null || d < best.third) best = Triple(p, q, d)
                        }
                        val pk = ballKey(p)
                        val existing = bestCopyPerHBall[pk]
                        if (best != null && (existing == null || best.third < existing.second.third)) {
                            bestCopyPerHBall[pk] = hbond to best
                        }
                    }
                }
                for ((pk, cand) in bestCopyPerHBall) {
                    val (template, geo) = cand
                    val (p, q, _) = geo
                    val qk = ballKey(q)
                    if (!occupiedHbondPairs.add(pk to qk)) continue
                    objects += HbondInstance(
                        id = "hbond-copy:${pk.first},${pk.second},${pk.third}:${qk.first},${qk.second},${qk.third}",
                        hbond = template,
                        start = p,
                        end = q,
                        radius = options.hbondRadius,
                        material = HbondPattern.material(options.hbondOpacity.toFloat()),
                        visible = true,
                    )
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

    /** Per v0.7.0: 1e-4-quantized cartesian key for exact-coincidence dedupe (boundary-image
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

    /** Per v0.7.0: D–H···A angle in degrees for an hbond whose donor is [h] and acceptor is [a]
     *  (vertex at H, evaluated at the origin — translation-invariant). The atoms are concrete
     *  materialised images, so their actual Cartesian displacements preserve which O/H image is
     *  being drawn. Returns null when [h] has no covalent donor partner. */
    private fun hbondAngleDegrees(h: AtomImage, a: AtomImage, partners: List<AtomImage>): Double? {
        val hPos = h.cartesianCoordinate.toVec3()
        val toA = a.cartesianCoordinate.toVec3() - hPos
        return partners.map { p ->
            val toP = p.cartesianCoordinate.toVec3() - hPos
            angleDegrees(toP, Vec3(0.0, 0.0, 0.0), toA)
        }.maxOrNull()
    }

    /** Per v0.7.0: true when [b] is a periodic image of the same atom as [a] — the fractional
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
