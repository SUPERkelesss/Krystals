package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary

object BondDetector {
    const val MAX_RENDERED_ATOMS = 100_000

    // Per v0.5.3b: shell materialisation is the OOM hot spot. A 2-cell-thick shell covers
    // (ex+4)(ey+4)(ez+4) cells worth of atoms; a 1-cell-thick shell covers (ex+2)(ey+2)(ez+2). The
    // peak atom count (primary + shell) is materialised *before* the MAX_RENDERED_ATOMS check ran,
    // so OOM hit large cells first. These guards choose the shell thickness up front and reject
    // structures that would still overflow.
    const val SHELL_DEGRADE_THRESHOLD = 60_000L
    const val SHELL_HARD_LIMIT = 150_000L

    /** v0.5.3b: how thick the neighbour shell is. FULL = 2 cells (v0.3.44 polyhedron complete);
     *  ONE_CELL = 1 cell (boundary-image centres may miss their outward polyhedron face). */
    enum class ShellMode { FULL, ONE_CELL }

    /** Peak number of atoms materialised by [buildNetwork] = baseSize × shell-cell count (FULL mode). */
    fun estimatePeakAtomCount(baseSize: Int, expansion: Expansion): Long {
        if (baseSize == 0) return 0L
        val ex = expansion.x
        val ey = expansion.y
        val ez = expansion.z
        return baseSize.toLong() * (ex + 4).toLong() * (ey + 4).toLong() * (ez + 4).toLong()
    }

    /** Convenience overload that expands once; prefer the (baseSize, expansion) form when the
     *  caller already has the expanded asymmetric unit. */
    fun estimatePeakAtomCount(structure: CrystalStructure, expansion: Expansion): Long =
        estimatePeakAtomCount(SymmetryExpander.expand(structure).size, expansion)

    /** Choose the shell thickness for a structure. FULL unless its peak overflows
     *  [SHELL_DEGRADE_THRESHOLD]; then ONE_CELL unless that too overflows [SHELL_HARD_LIMIT]
     *  (throws so the caller can report the structure is too large). */
    fun pickShellMode(baseSize: Int, expansion: Expansion): ShellMode {
        if (estimatePeakAtomCount(baseSize, expansion) <= SHELL_DEGRADE_THRESHOLD) return ShellMode.FULL
        val peakOneCell = baseSize.toLong() *
            (expansion.x + 2).toLong() * (expansion.y + 2).toLong() * (expansion.z + 2).toLong()
        require(peakOneCell <= SHELL_HARD_LIMIT) {
            "Structure too large: ~$peakOneCell atoms even with a 1-cell shell (limit $SHELL_HARD_LIMIT). Try a smaller expansion."
        }
        return ShellMode.ONE_CELL
    }

    fun buildNetwork(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        expansion: Expansion = Expansion(),
    ): BondNetwork = buildNetworkGridded(structure, bondConfiguration, expansion)

    /**
     * Per v0.5.3b (Phase 2): gridded scene build. Materialises only the primary region plus the
     * boundary images (atoms on the primary-box faces, ≤ 7×base — the ones the renderer shows by
     * default), then infers bonds by enumerating each centre's ±1 neighbour-cell offsets against a
     * spatial hash of the primary atoms, creating cross-cell shell atoms on demand. This avoids
     * materialising the 2-cell-thick external shell (the OOM source: 124×base for 1×1×1).
     *
     * Correctness rests on: every legacy bond (centre ∈ primary∪boundary, neighbour ∈ centre±1
     * cell) corresponds to a primary atom pair (c, q) at offset off_b = c.cellOffset + delta,
     * delta ∈ {-1,0,1}³ — so a ±1 offset sweep over primary atoms reproduces the full bond set,
     * including a boundary centre's outward ±2 neighbour (delta=+1 on the boundary's face axis →
     * off_b=2). v0.3.44 polyhedron completeness is thereby preserved without ±2 materialisation.
     */
    fun buildNetworkGridded(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        expansion: Expansion = Expansion(),
    ): BondNetwork {
        val base = SymmetryExpander.expand(structure)
        require(base.size.toLong() * expansion.multiplier <= MAX_RENDERED_ATOMS) {
            "Expansion exceeds limit $MAX_RENDERED_ATOMS"
        }
        val ex = expansion.x
        val ey = expansion.y
        val ez = expansion.z
        val boundaryEps = 1e-6

        val primaryAtoms = ArrayList<AtomImage>(base.size * expansion.multiplier)
        var tempId = 1L
        fun nextTempId() = tempId++
        for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
            base.forEach { atom ->
                val offset = Int3(ix, iy, iz)
                val fractional = atom.fractionalCoordinate + offset
                primaryAtoms += atom.copy(
                    id = nextTempId(),
                    fractionalCoordinate = fractional,
                    cartesianCoordinate = structure.lattice.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = false,
                )
            }
        }

        // Boundary images: shell atoms on the primary-box faces, within the [0,ex] closure. These
        // are displayed by default (they complete visible cell edges/faces); genuine external
        // neighbours are created on demand during bonding. Per v0.3.41 the boundary-image test is
        // "image position is on a primary-box face AND within the closure" — uses the raw (unwrapped)
        // fractional, so e.g. an atom at (0,0,0.25) imaged to offset (0,1,0) sits at (0,1,0.25),
        // on the y=1 face and inside [0,1]³, hence a boundary image. A non-zero offset always makes
        // the image distinct from the primary atom (even when its wrapped position coincides, e.g.
        // Cs@(0,0,0) imaged to (1,0,0) — same wrapped position but a different lattice image that
        // must act as a bond centre to reach its ±2 outward neighbour). The offset range is ±1; ±2
        // outward ligands are created on demand by the bond sweep.
        val boundaryImages = ArrayList<AtomImage>()
        for (ix in -1..ex + 1) for (iy in -1..ey + 1) for (iz in -1..ez + 1) {
            if (ix in 0 until ex && iy in 0 until ey && iz in 0 until ez) continue
            base.forEach { atom ->
                val offset = Int3(ix, iy, iz)
                val fractional = atom.fractionalCoordinate + offset
                val inPrimaryBox =
                    fractional.x >= -boundaryEps && fractional.x <= ex + boundaryEps &&
                        fractional.y >= -boundaryEps && fractional.y <= ey + boundaryEps &&
                        fractional.z >= -boundaryEps && fractional.z <= ez + boundaryEps
                val onFace =
                    (fractional.x >= -boundaryEps && fractional.x <= boundaryEps) ||
                        (fractional.x >= ex - boundaryEps && fractional.x <= ex + boundaryEps) ||
                        (fractional.y >= -boundaryEps && fractional.y <= boundaryEps) ||
                        (fractional.y >= ey - boundaryEps && fractional.y <= ey + boundaryEps) ||
                        (fractional.z >= -boundaryEps && fractional.z <= boundaryEps) ||
                        (fractional.z >= ez - boundaryEps && fractional.z <= ez + boundaryEps)
                if (!inPrimaryBox || !onFace) return@forEach
                boundaryImages += atom.copy(
                    id = nextTempId(),
                    fractionalCoordinate = fractional,
                    cartesianCoordinate = structure.lattice.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = true,
                    isBoundaryImage = true,
                )
            }
        }

        val centers = primaryAtoms + boundaryImages
        val custom = bondConfiguration.rules.associateBy { it.key }
        val disabledPairs = bondConfiguration.disabledPairs
        val cellSize = BondRuleMatching.estimateCellSize(structure)
        // Spatial hash of primary atoms by floor(cartesian / cellSize).
        val buckets = HashMap<Int3, MutableList<AtomImage>>()
        for (a in primaryAtoms) {
            val cartesian = a.cartesianCoordinate
            val k = Int3(
                Math.floor(cartesian.x / cellSize).toInt(),
                Math.floor(cartesian.y / cellSize).toInt(),
                Math.floor(cartesian.z / cellSize).toInt(),
            )
            buckets.getOrPut(k) { mutableListOf() }.add(a)
        }
        val la = structure.lattice.matrix.a
        val lb = structure.lattice.matrix.b
        val lc = structure.lattice.matrix.c
        val result = ArrayList<Bond>()
        val seenBonds = HashSet<Pair<Long, Long>>()
        val shellAtoms = ArrayList<AtomImage>()
        // Per v0.5.3b: shell atoms are deduplicated by (primary atom id, image offset). Boundary
        // images pre-materialised above are indexed here too so the bond sweep reuses them instead of
        // spawning duplicates at the same image position (e.g. Cs@(0,0,0) imaged to (1,0,0) is both a
        // boundary image and a bond target — it must be a single atom, or boundary centres would be
        // skipped and their ±2 outward neighbours never reached).
        val shellByKey = HashMap<Pair<Long, Int3>, AtomImage>()
        for (b in boundaryImages) {
            val primaryId = primaryAtoms.firstOrNull {
                it.siteId == b.siteId && it.fractionalCoordinate.almostEquals(
                    FractionalCoordinate(
                        b.fractionalCoordinate.x - b.cellOffset.x,
                        b.fractionalCoordinate.y - b.cellOffset.y,
                        b.fractionalCoordinate.z - b.cellOffset.z,
                    ),
                )
            }?.id
            if (primaryId != null) shellByKey[primaryId to b.cellOffset] = b
        }

        // Per v0.3.41: a shell atom is a boundary image iff its (raw, unwrapped) fractional
        // position lies on a primary-box face AND within the [0,ex] closure. Testing the offset
        // instead would mis-classify e.g. Cl(½,½,½) imaged to (0,0,1) → position (½,½,1.5), which is
        // outside the closure (z=1.5 > 1) and therefore an external shell, not a boundary image —
        // even though the offset (0,0,1) itself sits on the z=1 face.
        fun isBoundaryPosition(fractional: FractionalCoordinate): Boolean {
            val inBox = fractional.x >= -boundaryEps && fractional.x <= ex + boundaryEps &&
                fractional.y >= -boundaryEps && fractional.y <= ey + boundaryEps &&
                fractional.z >= -boundaryEps && fractional.z <= ez + boundaryEps
            if (!inBox) return false
            return (fractional.x >= -boundaryEps && fractional.x <= boundaryEps) ||
                (fractional.x >= ex - boundaryEps && fractional.x <= ex + boundaryEps) ||
                (fractional.y >= -boundaryEps && fractional.y <= boundaryEps) ||
                (fractional.y >= ey - boundaryEps && fractional.y <= ey + boundaryEps) ||
                (fractional.z >= -boundaryEps && fractional.z <= boundaryEps) ||
                (fractional.z >= ez - boundaryEps && fractional.z <= ez + boundaryEps)
        }

        fun getShellAtom(q: AtomImage, off: Int3): AtomImage {
            shellByKey[q.id to off]?.let { return it }
            val frac = q.fractionalCoordinate + off
            val atom = q.copy(
                id = nextTempId(),
                fractionalCoordinate = frac,
                cartesianCoordinate = structure.lattice.toCartesian(frac),
                cellOffset = off,
                isShell = true,
                isBoundaryImage = isBoundaryPosition(frac),
            )
            shellAtoms += atom
            shellByKey[q.id to off] = atom
            return atom
        }

        // For each centre, sweep its ±1 neighbour-cell offsets (delta ∈ {-1,0,1}³). off_b =
        // c.cellOffset + delta; for a boundary centre (offset ±1) delta=+1 reaches off_b=±2, i.e.
        // the outward external neighbour — no ±2 materialisation needed. Candidates q are primary
        // atoms near c.cartesian - lat·off_b (so that |c − (q+off_b)| = |X − q| ≤ cellSize).
        for (c in centers) {
            for (dxx in -1..1) for (dyy in -1..1) for (dzz in -1..1) {
                val offB = Int3(c.cellOffset.x + dxx, c.cellOffset.y + dyy, c.cellOffset.z + dzz)
                val latOff = la * offB.x.toDouble() + lb * offB.y.toDouble() + lc * offB.z.toDouble()
                val cCartesian = c.cartesianCoordinate.toVec3()
                val qx = cCartesian.x - latOff.x
                val qy = cCartesian.y - latOff.y
                val qz = cCartesian.z - latOff.z
                val bix = Math.floor(qx / cellSize).toInt()
                val biy = Math.floor(qy / cellSize).toInt()
                val biz = Math.floor(qz / cellSize).toInt()
                for (bx in bix - 1..bix + 1) for (by in biy - 1..biy + 1) for (bz in biz - 1..biz + 1) {
                    val bucket = buckets[Int3(bx, by, bz)] ?: continue
                    for (q in bucket) {
                        val bCartesian = q.cartesianCoordinate.toVec3() + latOff
                        val d = distance(cCartesian, bCartesian)
                        if (d <= 0.0) continue
                        // Per v0.5.3b: BondRule.key sorts the two site ids and joins with NUL; reuse
                        // it so custom-rule + disabled-pair lookups match the rest of the engine (the
                        // legacy path built the same key inline — a plain-space join would miss rules).
                        val key = if (c.siteId < q.siteId) "${c.siteId} ${q.siteId}" else "${q.siteId} ${c.siteId}"
                        if (key in disabledPairs) continue
                        val customRule = custom[key]
                        val isPeriodicSameSite = c.siteId == q.siteId &&
                            PeriodicBoundary.isIntegerTranslation(c.fractionalCoordinate - (q.fractionalCoordinate + offB))
                        if (customRule == null && isPeriodicSameSite) continue
                        val rule = customRule ?: BondRule(
                            c.siteId, q.siteId, 0.1,
                            PeriodicTable.covalentRadius(c.species.symbol) +
                                PeriodicTable.covalentRadius(q.species.symbol) + 0.45,
                            BondRuleSource.AUTO,
                        )
                        if (d < rule.minAngstrom || d > rule.maxAngstrom) continue
                        val bIsShell = offB != Int3(0, 0, 0)
                        val bAtom = if (bIsShell) getShellAtom(q, offB) else q
                        // Per v0.3.43: orient so the shell atom (when exactly one endpoint is shell) is atomB.
                        val (atomA, atomB) = when {
                            c.isShell && !bAtom.isShell -> bAtom to c
                            bAtom.isShell && !c.isShell -> c to bAtom
                            else -> c to bAtom
                        }
                        val bk = if (atomA.id < atomB.id) atomA.id to atomB.id else atomB.id to atomA.id
                        if (!seenBonds.add(bk)) continue
                        result += Bond(atomA.id, atomB.id, d, rule, Int3(0, 0, 0))
                    }
                }
            }
        }

        // Keep shell atoms referenced by a bond; discard the rest (same filtering as the legacy path).
        val referencedShellIds = HashSet<Long>()
        for (bond in result) {
            referencedShellIds += bond.atomA
            referencedShellIds += bond.atomB
        }
        val keptShell = (boundaryImages + shellAtoms).filter { it.isShell && it.id in referencedShellIds }

        val idMap = HashMap<Long, Long>()
        var finalId = 1L
        val finalAtoms = ArrayList<AtomImage>(primaryAtoms.size + keptShell.size)
        for (atom in primaryAtoms) {
            idMap[atom.id] = finalId
            finalAtoms += atom.copy(id = finalId++)
        }
        for (atom in keptShell) {
            idMap[atom.id] = finalId
            finalAtoms += atom.copy(id = finalId++)
        }
        require(finalAtoms.size <= MAX_RENDERED_ATOMS) {
            "Expansion exceeds limit $MAX_RENDERED_ATOMS"
        }
        val finalBonds = result.map { bond ->
            bond.copy(atomA = idMap.getValue(bond.atomA), atomB = idMap.getValue(bond.atomB))
        }
        return BondNetwork(finalAtoms, finalBonds, structure, expansion)
    }

    @Suppress("unused")
    private fun buildNetworkLegacy(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        expansion: Expansion = Expansion(),
    ): BondNetwork {
        val base = SymmetryExpander.expand(structure)
        // Per v0.5.3b: guard the primary atom count up front (was a post-hoc check on finalAtoms
        // only, which never tripped for large-shell structures because finalAtoms stays small).
        require(base.size.toLong() * expansion.multiplier <= MAX_RENDERED_ATOMS) {
            "Expansion exceeds limit $MAX_RENDERED_ATOMS"
        }
        val shellMode = pickShellMode(base.size, expansion)
        val ex = expansion.x
        val ey = expansion.y
        val ez = expansion.z
        // Per v0.3.4/v0.3.44: materialise a TWO-cell-thick shell of neighbour cells around the
        // primary expansion region. Primary region = [0,ex) × [0,ey) × [0,ez). Shell region covers
        // [-2,ex+2) × [-2,ey+2) × [-2,ez+2) minus the primary region. Bonds are computed from every
        // primary/boundary atom to every atom in the primary+shell region using real Cartesian
        // distances, so cross-cell bonds are real bonds to real shell atoms. The shell must be two
        // cells thick so a boundary-image centre (sitting at offset ±1, on a primary-box face) finds
        // its outward neighbours at offset ±2 materialised — otherwise a corner-atom polyhedron was
        // only complete at the primary (0,0,0) site. Atoms outside the [0,ex] closure are external
        // shell (hidden by default); those referenced by a bond are kept so they can be polyhedron
        // vertices. Shell atoms that are not referenced by any bond are discarded after bonding, and
        // the kept atoms are renumbered with compact ids.
        val primaryCapacity = base.size * expansion.multiplier
        val primaryAtoms = ArrayList<AtomImage>(primaryCapacity)
        var tempId = 1L
        fun nextTempId() = tempId++
        for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
            base.forEach { atom ->
                val offset = Int3(ix, iy, iz)
                val fractional = atom.fractionalCoordinate + offset
                primaryAtoms += atom.copy(
                    id = nextTempId(),
                    fractionalCoordinate = fractional,
                    cartesianCoordinate = structure.lattice.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = false,
                )
            }
        }

        val shellAtoms = ArrayList<AtomImage>()
        val boundaryEps = 1e-6
        // Per v0.3.44: the shell is TWO cells thick so a boundary-image centre's outward neighbours
        // are materialised too. A boundary image sits on the primary-box face (offset ±1); its
        // outward face/edge/corner neighbours land at offset ±2, which a 1-cell shell did not cover —
        // so a corner-atom polyhedron was only complete at the primary (0,0,0) site. Atoms at offset
        // ±2 are external shell (outside the [0,ex] closure): hidden by default, but kept when a bond
        // references them so they can serve as polyhedron vertices.
        // Per v0.5.3b: large cells degrade to a 1-cell shell ([ShellMode.ONE_CELL]) to avoid OOM;
        // boundary-image centres then miss their outward polyhedron face, but the primary centres
        // stay complete and the structure is at least viewable.
        val shellFrom = if (shellMode == ShellMode.FULL) -2 else -1
        val shellUntil = { n: Int -> if (shellMode == ShellMode.FULL) n + 2 else n + 1 }
        for (ix in shellFrom until shellUntil(ex)) for (iy in shellFrom until shellUntil(ey)) for (iz in shellFrom until shellUntil(ez)) {
            if (ix in 0 until ex && iy in 0 until ey && iz in 0 until ez) continue
            base.forEach { atom ->
                val offset = Int3(ix, iy, iz)
                val fractional = atom.fractionalCoordinate + offset
                // Per v0.3.41: a shell atom lying on a face of the primary expansion box is a
                // boundary image (e.g. the (1,0,0) image of a corner atom). Boundary images are
                // displayed by default. Shell atoms not on any face are genuine external neighbours.
                // A boundary image must lie on a face of the primary expansion box *and* be within
                // the closure of that box. This prevents corner/edge atoms from generating extra
                // images outside the visible supercell (e.g. Cs at (0,-1,0) for a 1×1×1 expansion).
                val inPrimaryBox =
                    fractional.x >= -boundaryEps && fractional.x <= ex + boundaryEps &&
                        fractional.y >= -boundaryEps && fractional.y <= ey + boundaryEps &&
                        fractional.z >= -boundaryEps && fractional.z <= ez + boundaryEps
                val onFace =
                    (fractional.x >= -boundaryEps && fractional.x <= boundaryEps) ||
                        (fractional.x >= ex - boundaryEps && fractional.x <= ex + boundaryEps) ||
                        (fractional.y >= -boundaryEps && fractional.y <= boundaryEps) ||
                        (fractional.y >= ey - boundaryEps && fractional.y <= ey + boundaryEps) ||
                        (fractional.z >= -boundaryEps && fractional.z <= boundaryEps) ||
                        (fractional.z >= ez - boundaryEps && fractional.z <= ez + boundaryEps)
                val isBoundaryImage = inPrimaryBox && onFace
                shellAtoms += atom.copy(
                    id = nextTempId(),
                    fractionalCoordinate = fractional,
                    cartesianCoordinate = structure.lattice.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = true,
                    isBoundaryImage = isBoundaryImage,
                )
            }
        }

        val rawBonds = inferPrimaryShellBonds(
            primaryAtoms,
            shellAtoms,
            bondConfiguration.rules,
            bondConfiguration.disabledPairs,
        )

        // Discard shell atoms that are not referenced by any bond. This keeps the SceneSnapshot small
        // while still allowing polyhedra to use every cross-cell ligand (they come from bonds).
        // Per v0.3.41: a shell atom may appear as either atomA (a boundary image acting as a bond
        // centre) or atomB (an external-shell ligand), so both endpoints must be considered.
        val atomById = (primaryAtoms + shellAtoms).associateBy { it.id }
        val referencedShellTempIds = mutableSetOf<Long>()
        rawBonds.forEach { bond ->
            atomById[bond.atomA]?.takeIf { it.isShell }?.let { referencedShellTempIds += it.id }
            atomById[bond.atomB]?.takeIf { it.isShell }?.let { referencedShellTempIds += it.id }
        }
        val keptShellAtoms = shellAtoms.filter { it.id in referencedShellTempIds }

        // Renumber kept atoms compactly so the id space is dense.
        val idMap = mutableMapOf<Long, Long>()
        var finalId = 1L
        val finalAtoms = ArrayList<AtomImage>(primaryAtoms.size + keptShellAtoms.size)
        primaryAtoms.forEach { atom ->
            idMap[atom.id] = finalId
            finalAtoms += atom.copy(id = finalId++)
        }
        keptShellAtoms.forEach { atom ->
            idMap[atom.id] = finalId
            finalAtoms += atom.copy(id = finalId++)
        }
        // Per v0.5.3b: the primary count is already guarded up front; this remains as an
        // invariant assertion (keptShell is bounded by bonds, so finalAtoms stays small).
        require(finalAtoms.size <= MAX_RENDERED_ATOMS) {
            "Expansion exceeds limit $MAX_RENDERED_ATOMS"
        }

        val finalBonds = rawBonds.map { bond ->
            bond.copy(atomA = idMap.getValue(bond.atomA), atomB = idMap.getValue(bond.atomB))
        }
        return BondNetwork(finalAtoms, finalBonds, structure, expansion)
    }

    /**
     * Compute bonds between every primary/boundary atom and atoms in its 3×3×3 neighbouring cells.
     * Per v0.3.44: boundary-image centres bond over the same path as primary centres — this lets a
     * bond whose BOTH endpoints are boundary images (a bond lying in a cell face/edge plane, shared
     * by neighbouring cells) be generated. Same-site integer-translation pairs (periodic images of
     * one atom, e.g. Cs–Cs) are still suppressed unless an explicit rule exists, so CsCl stays clean.
     * Duplicates are avoided with an unordered id-pair set.
     */
    private fun inferPrimaryShellBonds(
        primaryAtoms: List<AtomImage>,
        shellAtoms: List<AtomImage>,
        rules: List<BondRule>,
        disabledPairs: Set<String> = emptySet(),
    ): List<Bond> {
        val centers = primaryAtoms + shellAtoms.filter { it.isBoundaryImage }
        if (centers.isEmpty()) return emptyList()
        val allAtoms = primaryAtoms + shellAtoms
        val atomsByCell = allAtoms.groupBy { it.cellOffset }
        val custom = rules.associateBy { it.key }
        val result = mutableListOf<Bond>()
        val seenBonds = mutableSetOf<Pair<Long, Long>>()
        for (a in centers) {
            val cx = a.cellOffset.x
            val cy = a.cellOffset.y
            val cz = a.cellOffset.z
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val cell = Int3(cx + dx, cy + dy, cz + dz)
                for (b in atomsByCell[cell].orEmpty()) {
                    if (a.id == b.id) continue
                    // Deduplicate by unordered atom-id pair; each physical bond is emitted once.
                    val bondKey = if (a.id < b.id) a.id to b.id else b.id to a.id
                    if (!seenBonds.add(bondKey)) continue
                    val key = listOf(a.siteId, b.siteId).sorted().joinToString(" ")
                    // Per v0.2.3: a pair the user explicitly deleted is not redrawn via the fallback.
                    if (key in disabledPairs) continue
                    val customRule = custom[key]
                    // Per v0.3.42: a same-site integer-translation pair is a periodic image of the
                    // same atom (e.g. Ni(0,0,0)–Ni(1,0,0) along the c axis). The covalent-radius
                    // auto fallback would spuriously bond every like-atom neighbour (Cs–Cs in CsCl),
                    // so it is only allowed when the user defined an explicit rule for the pair.
                    val isPeriodicSameSite = a.siteId == b.siteId &&
                        PeriodicBoundary.isIntegerTranslation(a.fractionalCoordinate - b.fractionalCoordinate)
                    if (customRule == null && isPeriodicSameSite) continue
                    val rule = customRule ?: BondRule(
                        a.siteId, b.siteId, 0.1,
                        PeriodicTable.covalentRadius(a.species.symbol) +
                            PeriodicTable.covalentRadius(b.species.symbol) + 0.45,
                        BondRuleSource.AUTO,
                    )
                    val d = distance(a.cartesianCoordinate.toVec3(), b.cartesianCoordinate.toVec3())
                    if (d > 0.0 && d >= rule.minAngstrom && d <= rule.maxAngstrom) {
                        // Per v0.3.43: orient the bond so the shell atom (when exactly one endpoint is a
                        // shell atom) is atomB. The renderer keys cross-cell visibility on atomB being an
                        // external shell; boundary-image↔primary bonds stay drawn by default.
                        val (atomA, atomB) = when {
                            a.isShell && !b.isShell -> b to a
                            b.isShell && !a.isShell -> a to b
                            else -> a to b
                        }
                        result += Bond(atomA.id, atomB.id, d, rule, Int3(0, 0, 0))
                    }
                }
            }
        }
        return result
    }

    fun detect(
        atoms: List<AtomImage>,
        bondConfiguration: BondConfiguration,
        lattice: Lattice,
    ): List<Bond> {
        if (atoms.size < 2) return emptyList()
        val custom = bondConfiguration.rules.associateBy { it.key }
        // Per v0.3.2: minimum-image convention. For each atom pair consider all 27 periodic images
        // (offset in {-1,0,1}^3) of B and take the closest one, so corner/edge neighbour bonds are
        // found without materialising 26 neighbour-cell atoms. O(N^2 * 27) - fine for N up to a few
        // hundred; larger structures can be optimised later with spatial hashing.
        val la = lattice.matrix.a // lattice vectors in Cartesian (columns of the lattice matrix)
        val lb = lattice.matrix.b
        val lc = lattice.matrix.c
        val offsets = ArrayList<Vec3>(27)
        val intOffsets = ArrayList<Int3>(27)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            intOffsets += Int3(dx, dy, dz)
            offsets += la * dx.toDouble() + lb * dy.toDouble() + lc * dz.toDouble()
        }
        val result = mutableListOf<Bond>()
        for (i in atoms.indices) {
            val atom = atoms[i]
            for (j in i + 1 until atoms.size) {
                val other = atoms[j]
                val key = listOf(atom.siteId, other.siteId).sorted().joinToString("\u0000")
                // Per v0.2.3: a pair the user explicitly deleted is not redrawn via the fallback.
                if (key in bondConfiguration.disabledPairs) continue
                val rule = custom[key] ?: BondRule(
                    atom.siteId, other.siteId, 0.1,
                    PeriodicTable.covalentRadius(atom.species.symbol) +
                        PeriodicTable.covalentRadius(other.species.symbol) + 0.45,
                    BondRuleSource.AUTO,
                )
                // Find the closest periodic image of other relative to atom.
                var bestD = Double.POSITIVE_INFINITY
                var bestIdx = -1
                for (k in offsets.indices) {
                    val d = distance(atom.cartesianCoordinate.toVec3(), other.cartesianCoordinate.toVec3() + offsets[k])
                    if (d < bestD) { bestD = d; bestIdx = k }
                }
                if (bestD > 0.0 && bestD >= rule.minAngstrom && bestD <= rule.maxAngstrom) {
                    result += Bond(atom.id, other.id, bestD, rule, intOffsets[bestIdx])
                }
            }
        }
        return result
    }

}
