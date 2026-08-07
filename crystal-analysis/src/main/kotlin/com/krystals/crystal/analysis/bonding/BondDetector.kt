package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.angleDegrees
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary

object BondDetector {
    const val MAX_RENDERED_ATOMS = 100_000
    /** Shared zero-cell-offset constant, avoids allocating an Int3 per candidate pair. */
    private val ZERO_OFFSET = Int3(0, 0, 0)

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

    /** Convenience overload: expands once; prefer the (baseSize, expansion) form when the caller
     *  already has the expanded asymmetric unit. Uses sites × operations as a safe upper bound for
     *  the expanded count (special positions produce fewer images), avoiding a full expansion just
     *  to count atoms — overestimating only makes the shell-size guard more conservative. */
    fun estimatePeakAtomCount(structure: CrystalStructure, expansion: Expansion): Long =
        estimatePeakAtomCount(structure.sites.size * structure.effectiveSymmetryOperations.size, expansion)

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
        val primaryIdByBaseId = HashMap<Long, Long>(base.size)
        // Index every primary atom by (base atom id, cell offset) so the bond sweep can reuse the
        // existing primary atom when an image lookup lands inside the primary region — otherwise a
        // duplicate shell atom is spawned at the primary's exact position and steals its bonds
        // (which starved original-cell polyhedron centres of ligands in supercells).
        val baseIdByPrimaryId = HashMap<Long, Long>(base.size * expansion.multiplier)
        val primaryByCell = HashMap<Pair<Long, Int3>, AtomImage>(base.size * expansion.multiplier)
        var tempId = 1L
        fun nextTempId() = tempId++
        for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
            base.forEach { atom ->
                val offset = Int3(ix, iy, iz)
                val fractional = atom.fractionalCoordinate + offset
                val primaryId = nextTempId()
                if (ix == 0 && iy == 0 && iz == 0) primaryIdByBaseId[atom.id] = primaryId
                val primary = atom.copy(
                    id = primaryId,
                    fractionalCoordinate = fractional,
                    cartesianCoordinate = structure.lattice.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = false,
                )
                baseIdByPrimaryId[primaryId] = atom.id
                primaryByCell[atom.id to offset] = primary
                primaryAtoms += primary
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
        // Pre-index boundary images by the zero-cell primary atom they were copied from. This keeps
        // getShellAtom's existing key semantics without scanning all primary atoms by coordinates.
        val shellByKey = HashMap<Pair<Long, Int3>, AtomImage>()
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
                val boundaryImage = atom.copy(
                    id = nextTempId(),
                    fractionalCoordinate = fractional,
                    cartesianCoordinate = structure.lattice.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = true,
                    isBoundaryImage = true,
                )
                boundaryImages += boundaryImage
                shellByKey[primaryIdByBaseId.getValue(atom.id) to offset] = boundaryImage
            }
        }

        val centers = primaryAtoms + boundaryImages
        val custom = bondConfiguration.rules.associateBy { it.key }
        // Per v0.8.1: Hbond rules carry a discriminated key ("pair\0hbond") so plain-key
        // lookups below don't find them. Index hbond rules separately and consult them
        // alongside custom when the normal-rule lookup returns null for a pair.
        val hbondByPair = bondConfiguration.rules
            .filter { it.isHBond }
            .associateBy { listOf(it.siteA, it.siteB).sorted().joinToString(" ") }
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
            // q is always a primary atom (the spatial hash holds primaries only). The image this
            // bond points at lives in cell q.cellOffset + off; when that cell lies inside the
            // primary region the image IS the primary atom there — reuse it. The pre-v0.6.x key
            // (q.id, off) never matched the pre-indexed boundary images for q outside the zero
            // cell, so a duplicate was spawned at the primary's position and stole the bond.
            val baseId = baseIdByPrimaryId.getValue(q.id)
            val absoluteOffset = Int3(q.cellOffset.x + off.x, q.cellOffset.y + off.y, q.cellOffset.z + off.z)
            if (absoluteOffset.x in 0 until ex && absoluteOffset.y in 0 until ey && absoluteOffset.z in 0 until ez) {
                return primaryByCell.getValue(baseId to absoluteOffset)
            }
            val key = primaryIdByBaseId.getValue(baseId) to absoluteOffset
            shellByKey[key]?.let { return it }
            val frac = q.fractionalCoordinate + off
            val atom = q.copy(
                id = nextTempId(),
                fractionalCoordinate = frac,
                cartesianCoordinate = structure.lattice.toCartesian(frac),
                cellOffset = absoluteOffset,
                isShell = true,
                isBoundaryImage = isBoundaryPosition(frac),
            )
            shellAtoms += atom
            shellByKey[key] = atom
            return atom
        }

        // For each centre, sweep its ±1 neighbour-cell offsets (delta ∈ {-1,0,1}³). off_b =
        // c.cellOffset + delta; for a boundary centre (offset ±1) delta=+1 reaches off_b=±2, i.e.
        // the outward external neighbour — no ±2 materialisation needed. Candidates q are primary
        // atoms near c.cartesian - lat·off_b (so that |c − (q+off_b)| = |X − q| ≤ cellSize).
        for (c in centers) {
            // Per v0.7.1: the centre's Cartesian vector is loop-invariant across the 27 neighbour-cell
            // offsets; convert once per centre instead of once per (centre, delta) pair.
            val cCartesian = c.cartesianCoordinate.toVec3()
            for (dxx in -1..1) for (dyy in -1..1) for (dzz in -1..1) {
                val offB = Int3(c.cellOffset.x + dxx, c.cellOffset.y + dyy, c.cellOffset.z + dzz)
                val latOff = la * offB.x.toDouble() + lb * offB.y.toDouble() + lc * offB.z.toDouble()
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
                        // Quick reject: a pair beyond the auto covalent-radius window can only bond
                        // through an explicit custom rule, so skip the string-key/map/BondRule
                        // construction that the distance check below would discard anyway. This
                        // avoids the expensive work for the common non-bond pair (typically the
                        // large majority of candidates).
                        val autoMaxD = PeriodicTable.covalentRadius(c.species.symbol) +
                            PeriodicTable.covalentRadius(q.species.symbol) + 0.45
                        if (d > autoMaxD) {
                            val key = if (c.siteId < q.siteId) "${c.siteId}\u0000${q.siteId}" else "${q.siteId}\u0000${c.siteId}"
                            if (key in disabledPairs) continue
// Per v0.8.4: an existing pair rule is authoritative — it is selected regardless of
                            // whether d falls inside its window (the window is enforced below).
                            // v0.8.2's window-gated lookup made customRule null when d was outside
                            // the rule window, which let the covalent auto fallback resurrect
                            // out-of-window pairs (e.g. Al–Al in corundum: rule window 1.52 A,
                            // contact 2.68 A, covalent fallback 2.97 A caught it).
                            val hbondCand = hbondByPair[key]
                            val normalCand = custom[key]
                            val customRule = hbondCand ?: normalCand
                            if (customRule == null) continue
                            // A custom rule extends the window and covers d; fall through to the
                            // normal path below, which re-resolves the same customRule and bonds it.
                        }
                        // Per v0.5.3b: BondRule.key sorts the two site ids and joins with NUL; reuse
                        // it so custom-rule + disabled-pair lookups match the rest of the engine (the
                        // legacy path built the same key inline — a plain-space join would miss rules).
                        val key = if (c.siteId < q.siteId) "${c.siteId}\u0000${q.siteId}" else "${q.siteId}\u0000${c.siteId}"
                        if (key in disabledPairs) continue
// Per v0.8.4: the hbond rule wins when its window covers d (covalent distances still
                            // match the normal rule); otherwise an existing pair rule is
                            // authoritative — the window check below rejects out-of-window
                            // distances instead of letting the covalent auto fallback resurrect
                            // them (v0.8.2 regression: Al–Al in corundum).
                            val hbondCand = hbondByPair[key]
                            val normalCand = custom[key]
                            val customRule = when {
                                hbondCand != null && d >= hbondCand.minAngstrom && d <= hbondCand.maxAngstrom -> hbondCand
                                normalCand != null && d >= normalCand.minAngstrom && d <= normalCand.maxAngstrom -> normalCand
                                hbondCand != null -> hbondCand
                                normalCand != null -> normalCand
                                else -> null
                            }
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
                        val bIsShell = offB != ZERO_OFFSET
                        val bAtom = if (bIsShell) getShellAtom(q, offB) else q
                        // Per v0.3.43: orient so the shell atom (when exactly one endpoint is shell) is atomB.
                        val (atomA, atomB) = when {
                            c.isShell && !bAtom.isShell -> bAtom to c
                            bAtom.isShell && !c.isShell -> c to bAtom
                            else -> c to bAtom
                        }
                        val bk = if (atomA.id < atomB.id) atomA.id to atomB.id else atomB.id to atomA.id
                        if (!seenBonds.add(bk)) continue
                        result += Bond(atomA.id, atomB.id, d, rule, ZERO_OFFSET)
                    }
                }
            }
        }

        // Per v0.8.5: post-filter hbond bonds — rule-level one-hbond-per-proton is per-SITE,
        // but BondDetector materialises a bond for EVERY atom pair inside the window. Re-apply
        // the per-ATOM constraints: angle X-H-Y > 110° and keep only the shortest hbond per H.
        if (result.any { it.rule.isHBond }) {
            val allAtomsById = (primaryAtoms + boundaryImages + shellAtoms).associateBy { it.id }
            // Build covalent-partner lookup from normal (non-hbond) bonds.
            val covalentPartners = HashMap<Long, MutableList<AtomImage>>()
            for (b in result) {
                if (b.rule.isHBond) continue
                val a = allAtomsById[b.atomA] ?: continue
                val p = allAtomsById[b.atomB] ?: continue
                if (a.species.symbol == "H") covalentPartners.getOrPut(b.atomA) { mutableListOf() } += p
                if (p.species.symbol == "H") covalentPartners.getOrPut(b.atomB) { mutableListOf() } += a
            }
            // Angle re-check for each hbond bond.
            val anglePassed = HashSet<Bond>()
            for (b in result) {
                if (!b.rule.isHBond) continue
                val a = allAtomsById[b.atomA] ?: continue
                val c = allAtomsById[b.atomB] ?: continue
                val (h, x) = if (a.species.symbol == "H") a to c else c to a
                if (h.species.symbol != "H") continue
                val partners = covalentPartners[h.id].orEmpty()
                // If H has no covalent partner found from normal bonds (edge case: hbond-only
                // rules in tests), skip the angle check — the hbond passes. In production
                // smartIonic always generates normal rules first, so partners is non-empty.
                val ok = if (partners.isEmpty()) true else {
                    // Per v0.8.17: angle via periodic shortest displacements (same fix as the
                    // rule layer) — a boundary proton's covalent partner sits across the cell
                    // boundary and its main-cell coordinate gives a wrong ~60° angle.
                    val hPos = h.cartesianCoordinate.toVec3()
                    val xPos = x.cartesianCoordinate.toVec3()
                    val lattice = structure.lattice.matrix
                    val toX = periodicDisplacement(hPos, xPos, lattice)
                    partners.any { y ->
                        val toY = periodicDisplacement(hPos, y.cartesianCoordinate.toVec3(), lattice)
                        angleDegrees(toX, com.krystals.crystal.core.math.Vec3(0.0, 0.0, 0.0), toY) > 110.0
                    }
                }
                if (ok) anglePassed += b
            }
            // Per-H shortest distance.
            val bestByH = linkedMapOf<Long, Bond>()
            for (b in anglePassed) {
                val a = allAtomsById[b.atomA] ?: continue
                val hId = if (a.species.symbol == "H") b.atomA else b.atomB
                val existing = bestByH[hId]
                if (existing == null || b.distance < existing.distance) bestByH[hId] = b
            }
            val hbondKeep = bestByH.values.toSet()
            result.removeAll { it.rule.isHBond && it !in hbondKeep }
        }

        // Per hbond-model: separate hbonds from normal bonds at the network boundary.
        // The post-filter above (angle re-check + per-H shortest) still runs on the
        // mixed list — detection behaviour is unchanged; only the output channels split.
        val hbondBonds = ArrayList<Bond>()
        val normalBonds = ArrayList<Bond>()
        for (b in result) {
            if (b.rule.isHBond) hbondBonds += b else normalBonds += b
        }

        // Keep shell atoms referenced by a bond; discard the rest (same filtering as the legacy path).
        // Per v0.6.5: boundary images must always be kept — they complete the visible cell structure
        // (e.g. WC's corner W atoms at (1,0,0), (0,1,0), (1,1,0)) even when not referenced by any bond.
        // Per hbond-model: hbond endpoints must count too, or their shell acceptors would be dropped.
        val referencedShellIds = HashSet<Long>()
        for (bond in normalBonds) {
            referencedShellIds += bond.atomA
            referencedShellIds += bond.atomB
        }
        for (hbond in hbondBonds) {
            referencedShellIds += hbond.atomA
            referencedShellIds += hbond.atomB
        }
        val keptShell = (boundaryImages + shellAtoms).filter { it.isShell && (it.isBoundaryImage || it.id in referencedShellIds) }

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
        val finalBonds = normalBonds.map { bond ->
            bond.copy(atomA = idMap.getValue(bond.atomA), atomB = idMap.getValue(bond.atomB))
        }
        val finalAtomsById = finalAtoms.associateBy { it.id }
        val finalHbonds = hbondBonds.map { bond ->
            bond.copy(atomA = idMap.getValue(bond.atomA), atomB = idMap.getValue(bond.atomB))
                .toHydrogenBond(finalAtomsById)
        }
        return BondNetwork(finalAtoms, finalBonds, finalHbonds, structure, expansion)
    }

    /**
     * Compute bonds between every primary/boundary atom and atoms in its 3×3×3 neighbouring cells.
     * Per v0.3.44: boundary-image centres bond over the same path as primary centres — this lets a
     * bond whose BOTH endpoints are boundary images (a bond lying in a cell face/edge plane, shared
     * by neighbouring cells) be generated. Same-site integer-translation pairs (periodic images of
     * one atom, e.g. Ni–Ni) are still suppressed unless an explicit rule exists, so CsCl stays clean.
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
                    val key = listOf(a.siteId, b.siteId).sorted().joinToString(" ")
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
                        result += Bond(atomA.id, atomB.id, d, rule, ZERO_OFFSET)
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
        // Per v0.8.1: Hbond rules carry a discriminated key ("pair\u0000hbond") so plain-key
        // lookups below don't find them; index them separately alongside custom.
        val hbondByPair = bondConfiguration.rules
            .filter { it.isHBond }
            .associateBy { listOf(it.siteA, it.siteB).sorted().joinToString("\u0000") }
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
                val rule = custom[key] ?: hbondByPair[key] ?: BondRule(
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

    /** Per v0.8.17: shortest periodic displacement from [from] to [to] (cartesian). Used by the
     *  hbond angle re-check so a boundary proton's cross-boundary covalent partner yields the
     *  correct ~1 Å displacement instead of the ~cell-length main-cell difference. */
    private fun periodicDisplacement(from: Vec3, to: Vec3, lattice: Mat3): Vec3 {
        val frac = lattice.inverse() * (to - from)
        val wrapped = Vec3(
            frac.x - kotlin.math.round(frac.x),
            frac.y - kotlin.math.round(frac.y),
            frac.z - kotlin.math.round(frac.z),
        )
        return lattice * wrapped
    }
}
