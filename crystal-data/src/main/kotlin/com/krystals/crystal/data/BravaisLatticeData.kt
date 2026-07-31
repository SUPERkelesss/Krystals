package com.krystals.crystal.data

/**
 * Per v0.8.0: 14 Bravais lattice types and their primitive ↔ conventional conversion matrices.
 *
 * The matrices are stored in row form: each entry is `List<List<Double>>` where `rows[i][j]`
 * is the (i, j) element of the transformation matrix P such that:
 *
 *   a' = P[0][0]*a + P[0][1]*b + P[0][2]*c
 *   b' = P[1][0]*a + P[1][1]*b + P[1][2]*c
 *   c' = P[2][0]*a + P[2][1]*b + P[2][2]*c
 *
 * where (a, b, c) are the source lattice vectors and (a', b', c') are the target vectors.
 *
 * For conventional→primitive, P reduces the cell volume by the centering factor.
 * For primitive→conventional, P_inv expands it back.
 */
object BravaisLatticeData {

    enum class CenteringType(val symbol: Char) {
        PRIMITIVE('P'),
        A_CENTERED('A'),
        B_CENTERED('B'),
        C_CENTERED('C'),
        I_CENTERED('I'),
        F_CENTERED('F'),
        R_CENTERED('R'),
    }

    data class BravaisInfo(
        val centering: CenteringType,
        val crystalSystem: String,
        val bravaisName: String,
    )

    /**
     * Conversion matrices keyed by centering type.
     * `conventionalToPrimitive` reduces the cell to its primitive form.
     * `primitiveToConventional` is the inverse, restoring the conventional cell.
     */
    private val conventionalToPrimitive: Map<CenteringType, List<List<Double>>> = mapOf(
        CenteringType.C_CENTERED to listOf(
            listOf(0.5, 0.5, 0.0),
            listOf(-0.5, 0.5, 0.0),
            listOf(0.0, 0.0, 1.0),
        ),
        CenteringType.A_CENTERED to listOf(
            listOf(1.0, 0.0, 0.0),
            listOf(0.0, 0.5, 0.5),
            listOf(0.0, -0.5, 0.5),
        ),
        CenteringType.B_CENTERED to listOf(
            listOf(0.5, 0.0, 0.5),
            listOf(0.0, 1.0, 0.0),
            listOf(-0.5, 0.0, 0.5),
        ),
        CenteringType.I_CENTERED to listOf(
            listOf(-0.5, 0.5, 0.5),
            listOf(0.5, -0.5, 0.5),
            listOf(0.5, 0.5, -0.5),
        ),
        CenteringType.F_CENTERED to listOf(
            listOf(0.0, 0.5, 0.5),
            listOf(0.5, 0.0, 0.5),
            listOf(0.5, 0.5, 0.0),
        ),
        CenteringType.R_CENTERED to listOf(
            listOf(2.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0),
            listOf(-1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0),
            listOf(-1.0 / 3.0, -2.0 / 3.0, 1.0 / 3.0),
        ),
    )

    private val primitiveToConventional: Map<CenteringType, List<List<Double>>> = mapOf(
        CenteringType.C_CENTERED to listOf(
            listOf(1.0, -1.0, 0.0),
            listOf(1.0, 1.0, 0.0),
            listOf(0.0, 0.0, 1.0),
        ),
        CenteringType.A_CENTERED to listOf(
            listOf(1.0, 0.0, 0.0),
            listOf(0.0, 1.0, -1.0),
            listOf(0.0, 1.0, 1.0),
        ),
        CenteringType.B_CENTERED to listOf(
            listOf(1.0, 0.0, -1.0),
            listOf(0.0, 1.0, 0.0),
            listOf(1.0, 0.0, 1.0),
        ),
        CenteringType.I_CENTERED to listOf(
            listOf(0.0, 1.0, 1.0),
            listOf(1.0, 0.0, 1.0),
            listOf(1.0, 1.0, 0.0),
        ),
        CenteringType.F_CENTERED to listOf(
            listOf(-1.0, 1.0, 1.0),
            listOf(1.0, -1.0, 1.0),
            listOf(1.0, 1.0, -1.0),
        ),
        CenteringType.R_CENTERED to listOf(
            listOf(1.0, -1.0, 0.0),
            listOf(0.0, 1.0, -1.0),
            listOf(1.0, 1.0, 1.0),
        ),
    )

    /** Detect the centering type from a space-group symbol (e.g. "Fm-3m" → F, "P21/c" → P). */
    fun centeringFromSymbol(symbol: String): CenteringType {
        val first = symbol.trim().firstOrNull()?.uppercaseChar() ?: 'P'
        return CenteringType.entries.firstOrNull { it.symbol == first } ?: CenteringType.PRIMITIVE
    }

    /** Returns the conventional→primitive matrix for the given centering, or null for P. */
    fun conventionalToPrimitiveMatrix(centering: CenteringType): List<List<Double>>? =
        conventionalToPrimitive[centering]

    /** Returns the primitive→conventional matrix for the given centering, or null for P. */
    fun primitiveToConventionalMatrix(centering: CenteringType): List<List<Double>>? =
        primitiveToConventional[centering]

    /** The centering factor (number of lattice points per conventional cell). */
    fun centeringFactor(centering: CenteringType): Int = when (centering) {
        CenteringType.PRIMITIVE -> 1
        CenteringType.A_CENTERED, CenteringType.B_CENTERED, CenteringType.C_CENTERED,
        CenteringType.I_CENTERED -> 2
        CenteringType.R_CENTERED -> 3
        CenteringType.F_CENTERED -> 4
    }
}
