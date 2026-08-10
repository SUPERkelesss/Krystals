package com.krystals.app

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.editing.SpglibCellData
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.core.model.CrystalStructure

/**
 * Per v0.7.0: spglib supplies ONLY the space-group number for cell conversion.
 * The convert-to-primitive / convert-to-conventional buttons call
 * CrystalEditor.convertToPrimitive / convertToConventional (BravaisLatticeData
 * matrix tables); spglib's role is limited to detecting the true space group
 * of a possibly mislabelled cell (e.g. a primitive CIF whose declared symbol
 * is wrong). The v0.7.0 raw-data path (rebuilding sites from spglib output)
 * was removed — it produced per-species duplicate site ids ("spg:P1" for both
 * Na1 and Cl1) that crashed LazyColumn, and its species handling was unreliable.
 *
 * Every function returns null when spglib fails so callers fall back to the
 * cell's declared space group and the legacy matrix conversion.
 */
object SpglibStructure {
    private const val TAG = "SpglibStructure"

    /** Symmetry tolerance in Ångström; matches SpglibNative. */
    private const val SYMPREC = 1e-3

    /** Native in-place arrays must be capacity-sized >= 8× the input site count.
     *
     *  Per v0.7.0 bug fix: spg_refine_cell needs 4× its input count; the input
     *  (standardize output) can itself be 4× the original (primitive→conventional
     *  centering), so the true worst case is 16n. 32n adds headroom. The old 8n
     *  corrupted the heap on cells that actually conventionalize (e.g. NaCl
     *  primitive 2 sites → refine needs 32 slots, only 16 existed), which the
     *  user saw as "all atoms became one element" after the conversion. */
    private const val CAPACITY_MULTIPLIER = 32

    /** spglib dataset symmetry-op ceiling (cubic space groups return 192). */
    private const val MAX_OPS = 192

    /** spglib primitive cell (no idealization); null on failure. */
    fun toPrimitiveCell(structure: CrystalStructure): SpglibCellData? = convertCell(structure, primitive = true)

    /** spglib conventional cell (standardize + refine); null on failure. */
    fun toConventionalCell(structure: CrystalStructure): SpglibCellData? = convertCell(structure, primitive = false)

    /**
     * Per v0.7.0: resolve an UNRECOGNISED space group via spglib and import the
     * refined conventional cell into Krystals. Called at file-open time when
     * [SpaceGroupCatalog] cannot resolve the declared symbol/number.
     *
     * The cell's symmetry operations (CIF symops, or the catalog's when the CIF
     * carries none) are applied first via [SymmetryExpander.expand]: spglib
     * needs the FULL cell, not the asymmetric unit — feeding it ASU sites makes
     * every atom occur once and spglib degenerates to P1. Verified on
     * cod-1544612.cif (sg '?', 16 symops, 42 ASU sites): unexpanded input ->
     * P1; expanded (608 sites) -> Pban #50. spglib then standardizes to
     * conventional, removes symmetry-equivalent duplicates and refines; the
     * result is imported via CrystalEditor.rebuildFromSpglib.
     *
     * Returns null when spglib fails or finds no symmetry (P1), in which case
     * the caller keeps the parsed structure as-is.
     */
    fun refineUnknownSpaceGroup(structure: CrystalStructure): CrystalStructure? {
        // SymmetryExpander dedupes within each site's orbit, but different sites
        // can generate IDENTICAL coordinates (e.g. two ASU atoms on the same
        // special position). spglib's cell standardization fails (err 4) when fed
        // duplicate atoms, so globally dedupe by species + wrapped position first.
        // Grid 1e3 (not 1e4): two images of the same orbit computed through
        // different ops can differ in the 5th+ decimal while printing identically,
        // and round(1e4) splits such boundary values into different bins.
        val seenGlobal = HashSet<Triple<String, Long, Triple<Int, Int, Int>>>()
        val expanded = SymmetryExpander.expand(structure)
            .filter { atom ->
                val fc = atom.fractionalCoordinate
                val key = Triple(atom.species.symbol, atom.occupancy.toBits(), Triple(
                    Math.round(fc.x * 1e3).toInt(),
                    Math.round(fc.y * 1e3).toInt(),
                    Math.round(fc.z * 1e3).toInt(),
                ))
                seenGlobal.add(key)
            }
        val n = expanded.size
        if (n <= 0) return null
        val capacity = n * CAPACITY_MULTIPLIER
        val lattice = doubleArrayOf(
            structure.lattice.a, structure.lattice.b, structure.lattice.c,
            structure.lattice.alpha, structure.lattice.beta, structure.lattice.gamma,
        )
        val positions = DoubleArray(capacity * 3)
        val numbers = IntArray(capacity)
        // spglib only compares integer types; encode species + occupancy as one type
        // so disordered sites of the same element are not merged and can be restored.
        val typeBySpeciesAndOccupancy = LinkedHashMap<Pair<String, Long>, Int>()
        for (i in 0 until n) {
            val site = expanded[i]
            val typeKey = site.species.symbol to site.occupancy.toBits()
            val type = typeBySpeciesAndOccupancy.getOrPut(typeKey) { typeBySpeciesAndOccupancy.size + 1 }
            positions[i * 3] = site.fractionalCoordinate.x
            positions[i * 3 + 1] = site.fractionalCoordinate.y
            positions[i * 3 + 2] = site.fractionalCoordinate.z
            numbers[i] = type
        }
        val sgOut = intArrayOf(0)
        val opCountOut = intArrayOf(0)
        val rotations = IntArray(MAX_OPS * 9)
        val translations = DoubleArray(MAX_OPS * 3)
        val n2 = SpglibNative.refineToConventional(lattice, positions, numbers, n, SYMPREC, sgOut, rotations, translations, opCountOut)
        if (n2 <= 0) {
            warnLog(TAG) { "spglib refine failed for unknown group: code=$n2 (${SpglibError.message(n2)})" }
            return null
        }
        val sgNumber = sgOut[0]
        if (sgNumber !in 1..230) return null // spglib found no symmetry (P1)
        val symbolMap = typeBySpeciesAndOccupancy.entries.associate { (key, type) -> type to key.first }
        val occupancyMap = typeBySpeciesAndOccupancy.entries.associate { (key, type) -> type to Double.fromBits(key.second) }
        val cell = SpglibCellData(
            latticeParams = lattice,
            positions = positions.copyOf(n2 * 3),
            numbers = numbers.copyOf(n2),
            spaceGroupNumber = sgNumber,
            rotations = rotations,
            translations = translations,
            opCount = 0,
            symbolMap = symbolMap,
            occupancyMap = occupancyMap,
        )
        return CrystalEditor.rebuildFromSpglib(structure, cell)
    }

    private fun convertCell(structure: CrystalStructure, primitive: Boolean): SpglibCellData? {
        // spglib expects the FULL cell (all symmetry images), not the ASU — expand first.
        // (structure.sites is the asymmetric unit; feeding it to spg_find_primitive makes
        //  spglib treat the cell as P1 and the converted cell is wrong.)
        val expanded = SymmetryExpander.expand(structure)
        val n = expanded.size
        if (n <= 0) return null
        val capacity = n * CAPACITY_MULTIPLIER
        val lattice = doubleArrayOf(
            structure.lattice.a, structure.lattice.b, structure.lattice.c,
            structure.lattice.alpha, structure.lattice.beta, structure.lattice.gamma,
        )
        val positions = DoubleArray(capacity * 3)
        val numbers = IntArray(capacity)
        for (i in 0 until n) {
            val site = expanded[i]
            val z = PeriodicTable.symbols.indexOf(site.species.symbol) + 1
            if (z <= 0) return null
            positions[i * 3] = site.fractionalCoordinate.x
            positions[i * 3 + 1] = site.fractionalCoordinate.y
            positions[i * 3 + 2] = site.fractionalCoordinate.z
            numbers[i] = z
        }
        val sgOut = intArrayOf(0)
        val opCountOut = intArrayOf(0)
        val rotations = IntArray(MAX_OPS * 9)
        val translations = DoubleArray(MAX_OPS * 3)
        val n2 = if (primitive) {
            SpglibNative.refineToPrimitive(lattice, positions, numbers, n, SYMPREC, sgOut, rotations, translations, opCountOut)
        } else {
            SpglibNative.refineToConventional(lattice, positions, numbers, n, SYMPREC, sgOut, rotations, translations, opCountOut)
        }
        if (n2 <= 0) {
            warnLog(TAG) { "spglib ${if (primitive) "primitive" else "conventional"} failed: code=$n2 (${SpglibError.message(n2)})" }
            return null
        }
        val ops = opCountOut[0].coerceIn(0, MAX_OPS)
        return SpglibCellData(
            latticeParams = lattice,
            positions = positions.copyOf(n2 * 3),
            numbers = numbers.copyOf(n2),
            spaceGroupNumber = sgOut[0],
            rotations = rotations.copyOf(ops * 9),
            translations = translations.copyOf(ops * 3),
            opCount = ops,
        )
    }
}
