package com.krystals.core

object CrystalEngine {
    const val MAX_RENDERED_ATOMS = 100_000
    private const val AVOGADRO = 6.02214076e23

    fun buildScene(
        structure: CrystalStructure,
        expansion: Expansion = Expansion(),
        bondRules: List<BondRule> = structure.bondRules,
    ): SceneSnapshot {
        val base = expandAsymmetricUnit(structure)
        val ex = expansion.x
        val ey = expansion.y
        val ez = expansion.z
        // Per v0.3.4: materialise a one-cell-thick shell of neighbour cells around the primary
        // expansion region. Primary region = [0,ex) × [0,ey) × [0,ez). Shell region = [-1,ex+1) ×
        // [-1,ey+1) × [-1,ez+1) minus the primary region. Bonds are computed from every primary atom
        // to every atom in the primary+shell region using real Cartesian distances, so cross-cell
        // bonds are real bonds to real shell atoms instead of minimum-image offsets. For expansion=1
        // this is the classic 3×3×3 neighbourhood (26 shell cells); for 2×2×2 it gives the 56-cell
        // shell described in v0.3.4 (4 cells per face, 2 per edge, 1 per vertex of the supercell).
        //
        // To avoid storing thousands of unused shell atoms in the SceneSnapshot, shell atoms that are
        // not referenced by any bond are discarded after bonding, and the kept atoms are renumbered
        // with compact ids.
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
        for (ix in -1 until ex + 1) for (iy in -1 until ey + 1) for (iz in -1 until ez + 1) {
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
     * Primary atoms bond to all neighbouring atoms (primary, boundary images, and external shell).
     * Boundary images are extra bond centres only for outward bonds to genuine external shell atoms;
     * this prevents corner/edge atoms from creating spurious bonds back to primary atoms or along
     * cell edges to other boundary images. Duplicates are avoided with an unordered id-pair set.
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
                    // Boundary-image centres only bond outward to genuine external shell atoms.
                    // Bonds to primary atoms and other boundary images are already handled by the
                    // primary atom centres, and allowing them here would create spurious bonds
                    // between corner/edge images of the same site.
                    if (a.isBoundaryImage && !b.isExternalShell) continue
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
                        result += Bond(a.id, b.id, d, rule, Int3(0, 0, 0))
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
