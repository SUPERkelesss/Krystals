package com.krystals.core

object CrystalEngine {
    const val MAX_RENDERED_ATOMS = 100_000
    private const val AVOGADRO = 6.02214076e23

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

    /** Peak number of atoms materialised by [buildScene] = baseSize × shell-cell count (FULL mode). */
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
        estimatePeakAtomCount(expandAsymmetricUnit(structure).size, expansion)

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

    fun buildScene(
        structure: CrystalStructure,
        expansion: Expansion = Expansion(),
        bondRules: List<BondRule> = structure.bondRules,
    ): SceneSnapshot {
        val base = expandAsymmetricUnit(structure)
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
        val primaryAtoms = ArrayList<ExpandedAtom>(primaryCapacity)
        var tempId = 1L
        fun nextTempId() = tempId++
        for (ix in 0 until ex) for (iy in 0 until ey) for (iz in 0 until ez) {
            base.forEach { atom ->
                val offset = Int3(ix, iy, iz)
                val fractional = atom.fractional + Vec3(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
                primaryAtoms += atom.copy(
                    id = nextTempId(),
                    fractional = fractional,
                    cartesian = structure.cell.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = false,
                )
            }
        }

        val shellAtoms = ArrayList<ExpandedAtom>()
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
                val fractional = atom.fractional + Vec3(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
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
                    fractional = fractional,
                    cartesian = structure.cell.toCartesian(fractional),
                    cellOffset = offset,
                    isShell = true,
                    isBoundaryImage = isBoundaryImage,
                )
            }
        }

        val rawBonds = inferPrimaryShellBonds(primaryAtoms, shellAtoms, bondRules, structure.disabledBondPairs)

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
        val finalAtoms = ArrayList<ExpandedAtom>(primaryAtoms.size + keptShellAtoms.size)
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
        return SceneSnapshot(finalAtoms, finalBonds, structure, expansion)
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
        primaryAtoms: List<ExpandedAtom>,
        shellAtoms: List<ExpandedAtom>,
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
                    val isPeriodicSameSite = a.siteId == b.siteId && (a.fractional - b.fractional).isIntegerVector()
                    if (customRule == null && isPeriodicSameSite) continue
                    val rule = customRule ?: BondRule(
                        a.siteId, b.siteId, 0.1,
                        PeriodicTable.covalentRadius(a.element) + PeriodicTable.covalentRadius(b.element) + 0.45,
                        BondRuleSource.AUTO,
                    )
                    val d = distance(a.cartesian, b.cartesian)
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

    fun expandAsymmetricUnit(structure: CrystalStructure): List<ExpandedAtom> {
        val result = mutableListOf<ExpandedAtom>()
        var id = 1L
        structure.sites.forEach { site ->
            val positions = mutableListOf<Vec3>()
            structure.effectiveSymmetryOperations.forEach { operation ->
                val position = operation.apply(site.fractional)
                if (positions.none { it.almostEquals(position) }) positions += position
            }
            positions.forEach { position ->
                result += ExpandedAtom(
                    id++, site.id, site.label, site.element, position,
                    structure.cell.toCartesian(position), site.occupancy, Int3(0, 0, 0),
                )
            }
        }
        return result
    }

    fun inferBonds(atoms: List<ExpandedAtom>, rules: List<BondRule>, disabledPairs: Set<String> = emptySet(), cell: UnitCell): List<Bond> {
        if (atoms.size < 2) return emptyList()
        val custom = rules.associateBy { it.key }
        // Per v0.3.2: minimum-image convention. For each atom pair consider all 27 periodic images
        // (offset in {-1,0,1}^3) of B and take the closest one, so corner/edge neighbour bonds are
        // found without materialising 26 neighbour-cell atoms. O(N^2 * 27) - fine for N up to a few
        // hundred; larger structures can be optimised later with spatial hashing.
        val la = cell.matrix.a // lattice vectors in Cartesian (columns of the cell matrix)
        val lb = cell.matrix.b
        val lc = cell.matrix.c
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
                if (key in disabledPairs) continue
                val rule = custom[key] ?: BondRule(
                    atom.siteId, other.siteId, 0.1,
                    PeriodicTable.covalentRadius(atom.element) + PeriodicTable.covalentRadius(other.element) + 0.45,
                    BondRuleSource.AUTO,
                )
                // Find the closest periodic image of other relative to atom.
                var bestD = Double.POSITIVE_INFINITY
                var bestIdx = -1
                for (k in offsets.indices) {
                    val d = distance(atom.cartesian, other.cartesian + offsets[k])
                    if (d < bestD) { bestD = d; bestIdx = k }
                }
                if (bestD > 0.0 && bestD >= rule.minAngstrom && bestD <= rule.maxAngstrom) {
                    result += Bond(atom.id, other.id, bestD, rule, intOffsets[bestIdx])
                }
            }
        }
        return result
    }

    fun info(structure: CrystalStructure): CrystalInfo {
        val atoms = expandAsymmetricUnit(structure)
        val gramsPerMole = atoms.sumOf { atom -> (PeriodicTable.mass(atom.element) ?: 0.0) * atom.occupancy }
        val density = if (gramsPerMole > 0.0 && structure.cell.volume > 0.0) {
            gramsPerMole / AVOGADRO / (structure.cell.volume * 1e-24)
        } else null
        val counts = atoms.groupBy { it.element }.mapValues { (_, values) -> values.sumOf { it.occupancy } }
        val orderedElements = if ("C" in counts) {
            buildList {
                add("C")
                if ("H" in counts) add("H")
                addAll(counts.keys.filterNot { it == "C" || it == "H" }.sorted())
            }
        } else counts.keys.sorted()
        val composition = orderedElements.joinToString(" ") { element -> "$element ${formatCount(counts.getValue(element))}" }
        return CrystalInfo(atoms.size, structure.spaceGroupName, structure.cell, structure.cell.volume, density, composition)
    }

    private fun formatCount(value: Double): String {
        val rounded = kotlin.math.round(value)
        if (kotlin.math.abs(value - rounded) < 1e-8) return rounded.toLong().toString()
        return "%.4f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
}

private fun Vec3.isIntegerVector(epsilon: Double = 1e-6): Boolean {
    fun isInt(v: Double) = kotlin.math.abs(v - kotlin.math.round(v)) < epsilon
    return isInt(x) && isInt(y) && isInt(z)
}
