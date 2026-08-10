package com.krystals.app

import android.util.Log
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import org.json.JSONObject

/**
 * JNI bridge to the prebuilt spglib library (libspglib.so, linked by the
 * `spglib_bridge` CMake target in src/main/cpp).
 *
 * The native call chain is: standardize to the conventional cell (idealized),
 * then refine (spg_refine_cell), so downstream Krystals computation/rendering
 * sees a symmetrized conventional cell with exact special positions.
 */
object SpglibNative {
    private const val TAG = "SpglibNative"

    /** Symmetry tolerance in Ångström. 1e-3 absorbs PBE-relaxation noise in MP
     *  structures while still recognizing exact special positions. */
    private const val SYMPREC = 1e-3

    /** spglib dataset symmetry-op ceiling (cubic space groups return 192). */
    private const val MAX_OPS = 192

    /** Conventionalization + refinement can increase the site count; capacity
     *  must be >= 8× the input count for the native in-place arrays.
     *
     *  Per v0.7.0 bug fix: spglib's spg_refine_cell requires the arrays to hold
     *  4× its INPUT atom count (docs: "arrays are require to have 4 times larger
     *  memory space those of input cell"). The input to refine is the standardize
     *  output, which itself grows up to 4× (primitive → conventional centering,
     *  e.g. F/I/R). Worst case is therefore 4 × (4×n) = 16n; 32n adds headroom
     *  for the dedupe/write-back slack. The old 8n under-allocated — e.g. NaCl
     *  primitive 2 sites: standardize → 8 sites, refine needs 4×8 = 32 slots but
     *  only 8×2 = 16 existed → heap corruption, which surfaced as scrambled
     *  species ("all atoms became one element") and wrong coordinates. */
    private const val CAPACITY_MULTIPLIER = 32

    init {
        System.loadLibrary("spglib_bridge")
    }

    /**
     * Standardize to conventional (idealized) then refine the cell in place.
     *
     * @param lattice in/out [a, b, c, alpha, beta, gamma] (degrees)
     * @param positions in/out fractional coordinates, flattened 3×N; caller must
     *   pass capacity >= 3×8×inputCount
     * @param atomicNumbers in/out per-site atomic number (Z); caller must pass
     *   capacity >= 8×inputCount
     * @param numAtoms real site count (arrays are capacity-sized)
     * @param symprec symmetry tolerance (Å)
     * @param sgOut out[0]: space-group number re-determined from the refined cell
     * @return new site count on success; negative spglib error code on failure
     */
    external fun refineToConventional(
        lattice: DoubleArray,
        positions: DoubleArray,
        atomicNumbers: IntArray,
        numAtoms: Int,
        symprec: Double,
        sgOut: IntArray,
        rotationsOut: IntArray,
        translationsOut: DoubleArray,
        opCountOut: IntArray,
    ): Int

    /**
     * Find the primitive cell (spg_find_primitive, no idealization) and re-determine
     * the space-group number from it. Same array/capacity contract as
     * [refineToConventional]; returns new site count or a negative spglib error code.
     */
    external fun refineToPrimitive(
        lattice: DoubleArray,
        positions: DoubleArray,
        atomicNumbers: IntArray,
        numAtoms: Int,
        symprec: Double,
        sgOut: IntArray,
        rotationsOut: IntArray,
        translationsOut: DoubleArray,
        opCountOut: IntArray,
    ): Int

    /**
     * Apply spglib (conventional + refine) to a next-gen MP `structure` JSON
     * (pymatgen format: lattice.{a,b,c,alpha,beta,gamma}, sites[].{species,abc}).
     * Returns a rebuilt structure JSON carrying the refined cell, or null when
     * the native call fails (caller then falls back to the raw structure).
     */
    fun refineStructureJson(structure: JSONObject): JSONObject? {
        val latticeObj = structure.optJSONObject("lattice") ?: return null
        val sites = structure.optJSONArray("sites") ?: return null
        val n = sites.length()
        if (n <= 0) return null

        val lattice = doubleArrayOf(
            latticeObj.optDouble("a"), latticeObj.optDouble("b"), latticeObj.optDouble("c"),
            latticeObj.optDouble("alpha"), latticeObj.optDouble("beta"), latticeObj.optDouble("gamma"),
        )
        if (lattice.any { !it.isFinite() || it <= 0.0 }) return null

        val capacity = n * CAPACITY_MULTIPLIER
        val positions = DoubleArray(capacity * 3)
        val numbers = IntArray(capacity)
        for (i in 0 until n) {
            val site = sites.optJSONObject(i) ?: return null
            val abc = site.optJSONArray("abc") ?: return null
            positions[i * 3] = abc.optDouble(0)
            positions[i * 3 + 1] = abc.optDouble(1)
            positions[i * 3 + 2] = abc.optDouble(2)
            val element = site.optJSONArray("species")
                ?.optJSONObject(0)?.optString("element")?.takeIf { it.isNotBlank() } ?: return null
            val z = PeriodicTable.symbols.indexOf(element) + 1
            if (z <= 0) return null
            numbers[i] = z
        }

        val sgOut = intArrayOf(0)
        val opCountOut = intArrayOf(0)
        val rotations = IntArray(MAX_OPS * 9)
        val translations = DoubleArray(MAX_OPS * 3)
        val n2 = refineToConventional(lattice, positions, numbers, n, SYMPREC, sgOut, rotations, translations, opCountOut)
        if (n2 <= 0) {
            warnLog(TAG) { "refineToConventional failed: code=$n2 (spglib error ${SpglibError.message(n2)})" }
            return null
        }

        val outLattice = JSONObject()
        outLattice.put("a", lattice[0]).put("b", lattice[1]).put("c", lattice[2])
        outLattice.put("alpha", lattice[3]).put("beta", lattice[4]).put("gamma", lattice[5])

        val outSites = org.json.JSONArray()
        for (i in 0 until n2) {
            val site = JSONObject()
            val species = org.json.JSONArray()
            val spec = JSONObject()
            val element = PeriodicTable.symbols.getOrNull(numbers[i] - 1) ?: continue
            spec.put("element", element)
            spec.put("occu", 1.0)
            species.put(spec)
            site.put("species", species)
            site.put("abc", org.json.JSONArray(listOf(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])))
            outSites.put(site)
        }

        val out = JSONObject()
        out.put("lattice", outLattice)
        out.put("sites", outSites)
        out.put("spglib_spacegroup_number", sgOut[0])
        debugLog(TAG) { "refined $n -> $n2 sites, sg=${sgOut[0]} (${SpaceGroupCatalog.all.getOrNull(sgOut[0] - 1)?.symbol ?: "?"})" }
        return out
    }
}

/** SpglibError codes (spglib.h) mirrored for diagnostics. Bridge returns the
 *  negative of the spglib enum value on failure. */
internal object SpglibError {
    private val messages = mapOf(
        1 to "SPGERR_SPACEGROUP_SEARCH_FAILED",
        2 to "SPGERR_CELL_STANDARDIZATION_FAILED",
        3 to "SPGERR_SYMMETRY_OPERATION_SEARCH_FAILED",
        4 to "SPGERR_ATOMS_TOO_CLOSE",
        5 to "SPGERR_POINTGROUP_NOT_FOUND",
        6 to "SPGERR_NIGGLI_FAILED",
        7 to "SPGERR_DELAUNAY_FAILED",
        8 to "SPGERR_ARRAY_SIZE_SHORTAGE",
        9 to "SPGERR_NONE",
    )

    fun message(code: Int): String {
        val abs = kotlin.math.abs(code)
        return messages[abs] ?: "code $code"
    }
}
