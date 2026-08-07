package com.krystals.crystal.analysis.editing

/**
 * Per v0.8.40: spglib contributes ONLY the space-group number for the
 * convert-to-primitive / convert-to-conventional buttons; the matrix conversion
 * itself runs through the legacy CrystalEditor.convertToConventional /
 * convertToPrimitive (BravaisLatticeData tables). The v0.8.39 raw-data path
 * (rebuilding sites from this cell's positions/numbers) was removed — its
 * per-species site ids collided ("spg:P1" for both Na1 and Cl1) and crashed
 * LazyColumn. The lattice/positions/numbers fields remain populated by
 * [com.krystals.app.SpglibStructure] but only spaceGroupNumber is consumed.
 *
 * Arrays are row-major like the spglib C API:
 *  - positions: n × 3 (fractional coordinates)
 *  - rotations: opCount × 9 (3×3 integer matrices, row-major)
 *  - translations: opCount × 3
 */
data class SpglibCellData(
    val latticeParams: DoubleArray, // a, b, c, alpha, beta, gamma
    val positions: DoubleArray,     // n * 3 fractional coordinates
    val numbers: IntArray,          // n atomic numbers
    val spaceGroupNumber: Int,      // spglib space-group number (0 = unknown)
    val rotations: IntArray,        // opCount * 9 row-major 3x3 rotation matrices
    val translations: DoubleArray,  // opCount * 3 translation vectors
    val opCount: Int,
) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
