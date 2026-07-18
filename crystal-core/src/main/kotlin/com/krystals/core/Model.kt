package com.krystals.core

import kotlin.math.roundToInt

data class SymmetryOperation(
    val rotation: Mat3,
    val translation: Vec3,
    val source: String,
) {
    fun apply(value: Vec3) = (rotation * value + translation).wrapped()

    companion object {
        val IDENTITY = SymmetryOperation(Mat3.IDENTITY, Vec3.ZERO, "x,y,z")

        fun parse(source: String): SymmetryOperation {
            val clean = source.trim().trim('\'', '"')
            val expressions = clean.split(',')
            require(expressions.size == 3) { "Invalid symmetry operation: $source" }
            val parsed = expressions.map(::parseLinearExpression)
            val rotation = Mat3(
                Vec3(parsed[0].first.x, parsed[1].first.x, parsed[2].first.x),
                Vec3(parsed[0].first.y, parsed[1].first.y, parsed[2].first.y),
                Vec3(parsed[0].first.z, parsed[1].first.z, parsed[2].first.z),
            )
            return SymmetryOperation(rotation, Vec3(parsed[0].second, parsed[1].second, parsed[2].second), clean)
        }

        private fun parseLinearExpression(expression: String): Pair<Vec3, Double> {
            val normalized = expression.replace(" ", "").replace("-", "+-")
            var coefficients = Vec3.ZERO
            var offset = 0.0
            normalized.split('+').filter { it.isNotBlank() }.forEach { raw ->
                val term = raw.replace("*", "")
                val variable = term.lastOrNull()?.lowercaseChar()
                if (variable == 'x' || variable == 'y' || variable == 'z') {
                    val prefix = term.dropLast(1)
                    val coefficient = when (prefix) {
                        "", "+" -> 1.0
                        "-" -> -1.0
                        else -> parseFraction(prefix)
                    }
                    coefficients = when (variable) {
                        'x' -> coefficients.copy(x = coefficients.x + coefficient)
                        'y' -> coefficients.copy(y = coefficients.y + coefficient)
                        else -> coefficients.copy(z = coefficients.z + coefficient)
                    }
                } else {
                    offset += parseFraction(term)
                }
            }
            return coefficients to offset
        }
    }
}

fun parseFraction(value: String): Double {
    val clean = value.trim().trim('\'', '"')
    if ('/' !in clean) return clean.toDouble()
    val parts = clean.split('/', limit = 2)
    return parts[0].toDouble() / parts[1].toDouble()
}

data class AtomSite(
    val id: String,
    val label: String,
    val element: String,
    val fractional: Vec3,
    val occupancy: Double = 1.0,
)

data class ExpandedAtom(
    val id: Long,
    val siteId: String,
    val siteLabel: String,
    val element: String,
    val fractional: Vec3,
    val cartesian: Vec3,
    val occupancy: Double,
    val cellOffset: Int3,
    // Per v0.3.4: true for atoms in the surrounding shell of neighbour cells that exist only to
    // compute cross-cell bonds and complete coordination polyhedra. Shell atoms are hidden unless
    // a bond rule explicitly extends across the cell boundary.
    val isShell: Boolean = false,
    // Per v0.3.41: true for shell atoms that lie on the face of the primary expansion region.
    // Boundary images are displayed by default (they complete the visible unit-cell edges/faces),
    // whereas external shell atoms remain hidden unless extendAcrossCell is set.
    val isBoundaryImage: Boolean = false,
) {
    /** True for shell atoms that are not boundary images, i.e. genuine external neighbours. */
    val isExternalShell: Boolean get() = isShell && !isBoundaryImage
}

enum class BondRuleSource { CUSTOM, EXPLICIT, AUTO }

// Per v0.4.1: radius source for bond-rule generation. BONDING (键合半径) is the default used by
// ensureAutoBondRules and the "自动应用半径" (auto-apply radii) button in the bond editor; VDW is
// the alternative. BONDING looks up the bonding-radius table first and falls back to the covalent
// single-bond table for elements it lacks (H, noble gases, Pm, Po–Ac, Pa, Am, Cm, …). The
// bonding-radius data is licensed CC BY-SA 4.0, arXiv:2601.02017v1 [cond-mat.mtrl-sci] 05 Jan 2026.
enum class RadiusSource { BONDING, VDW }

data class BondRule(
    val siteA: String,
    val siteB: String,
    val minAngstrom: Double,
    val maxAngstrom: Double,
    val source: BondRuleSource = BondRuleSource.CUSTOM,
    // Per v0.3.0: when true, bonds of this rule are detected across unit-cell boundaries (between
    // an atom and its periodic image / a neighbour cell). Default false so only in-cell bonds draw.
    val extendAcrossCell: Boolean = false,
) {
    init {
        require(minAngstrom >= 0.0 && maxAngstrom >= minAngstrom)
    }
    val key: String get() = listOf(siteA, siteB).sorted().joinToString("\u0000")
}

data class Bond(
    val atomA: Long,
    val atomB: Long,
    val distance: Double,
    val rule: BondRule,
    // Per v0.3.4: cross-cell bonds are represented by an actual shell atom in atomB, so the offset
    // is always zero. The field is kept for binary compatibility with saved snapshots/tests.
    val offsetB: Int3 = Int3(0, 0, 0),
)

data class CrystalStructure(
    val blockName: String,
    val cell: UnitCell,
    val spaceGroupName: String,
    val spaceGroupNumber: Int?,
    val symmetryOperations: List<SymmetryOperation>,
    val sites: List<AtomSite>,
    val bondRules: List<BondRule> = emptyList(),
    val elementArgbOverrides: Map<String, Long> = emptyMap(),
    // Per v0.2.3: site pairs the user has explicitly deleted a bond rule for. These pairs are
    // skipped by inferBonds so the covalent-radius fallback does not silently redraw the bond.
    val disabledBondPairs: Set<String> = emptySet(),
    // Per v0.2.3: per-site color overrides (key = site id). Falls back to elementArgbOverrides.
    val siteArgbOverrides: Map<String, Long> = emptyMap(),
) {
    val effectiveSymmetryOperations: List<SymmetryOperation>
        get() = symmetryOperations.ifEmpty { SpaceGroupCatalog.operations(spaceGroupName) }
}

data class Expansion(val x: Int = 1, val y: Int = 1, val z: Int = 1) {
    init { require(x > 0 && y > 0 && z > 0) }
    val multiplier: Int get() = x * y * z
}

enum class FrameMode { NONE, SINGLE_CELL, ALL_CELLS }
enum class LineStyle { SOLID, DASHED }
enum class BondColorMode { BICOLOR, UNICOLOR }

/** Coordinate system drawn by the on-screen axis indicator. */
enum class AxisMode { ABC, XYZ }

data class ViewerAppearance(
    val backgroundArgb: Long = 0xFF101014,
    val reflectionEnabled: Boolean = true,
    val lightAzimuth: Float = 35f,
    val lightElevation: Float = 45f,
    val lightIntensity: Float = 0.8f,
    val diffusion: Float = 0.35f,
    val atomOpacity: Float = 1.0f,
    val frameMode: FrameMode = FrameMode.SINGLE_CELL,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val bondRadius: Float = 0.20f,
    val bondOpacity: Float = 1.0f,
    val bondColorMode: BondColorMode = BondColorMode.BICOLOR,
    val uniformBondArgb: Long = 0xFF9A90A0,
    val bondReflectionEnabled: Boolean = true,
    val polyhedronEnabled: Boolean = true,
    val polyhedronOpacity: Float = 0.5f,
    val polyhedronReflectionEnabled: Boolean = true,
    val showAxes: Boolean = true,
    val axisMode: AxisMode = AxisMode.ABC,
)

data class SceneSnapshot(
    val atoms: List<ExpandedAtom>,
    val bonds: List<Bond>,
    val structure: CrystalStructure,
    val expansion: Expansion,
    val elementArgbOverrides: Map<String, Long> = structure.elementArgbOverrides,
)

data class CrystalInfo(
    val atomCount: Int,
    val spaceGroup: String,
    val cell: UnitCell,
    val volume: Double,
    val density: Double?,
    val composition: String,
)

object PeriodicTable {
    val symbols = "H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am Cm Bk Cf Es Fm Md No Lr Rf Db Sg Bh Hs Mt Ds Rg Cn Nh Fl Mc Lv Ts Og".split(' ')
    private val masses = mapOf(
        "H" to 1.008, "He" to 4.0026, "Li" to 6.94, "Be" to 9.0122, "B" to 10.81,
        "C" to 12.011, "N" to 14.007, "O" to 15.999, "F" to 18.998, "Ne" to 20.180,
        "Na" to 22.990, "Mg" to 24.305, "Al" to 26.982, "Si" to 28.085, "P" to 30.974,
        "S" to 32.06, "Cl" to 35.45, "Ar" to 39.948, "K" to 39.098, "Ca" to 40.078,
        "Sc" to 44.956, "Ti" to 47.867, "V" to 50.942, "Cr" to 51.996, "Mn" to 54.938,
        "Fe" to 55.845, "Co" to 58.933, "Ni" to 58.693, "Cu" to 63.546, "Zn" to 65.38,
        "Ga" to 69.723, "Ge" to 72.630, "As" to 74.922, "Se" to 78.971, "Br" to 79.904,
        "Kr" to 83.798, "Rb" to 85.468, "Sr" to 87.62, "Y" to 88.906, "Zr" to 91.224,
        "Nb" to 92.906, "Mo" to 95.95, "Ru" to 101.07, "Rh" to 102.91, "Pd" to 106.42,
        "Ag" to 107.87, "Cd" to 112.41, "In" to 114.82, "Sn" to 118.71, "Sb" to 121.76,
        "Te" to 127.60, "I" to 126.90, "Xe" to 131.29, "Cs" to 132.91, "Ba" to 137.33,
        "La" to 138.91, "Ce" to 140.12, "Pr" to 140.91, "Nd" to 144.24, "Sm" to 150.36,
        "Eu" to 151.96, "Gd" to 157.25, "Tb" to 158.93, "Dy" to 162.50, "Ho" to 164.93,
        "Er" to 167.26, "Tm" to 168.93, "Yb" to 173.05, "Lu" to 174.97, "Hf" to 178.49,
        "Ta" to 180.95, "W" to 183.84, "Re" to 186.21, "Os" to 190.23, "Ir" to 192.22,
        "Pt" to 195.08, "Au" to 196.97, "Hg" to 200.59, "Tl" to 204.38, "Pb" to 207.2,
        "Bi" to 208.98, "Th" to 232.04, "Pa" to 231.04, "U" to 238.03,
    )
    // Covalent radii in Å, sourced from the v0.2.1 specification (single-bond covalent radii,
    // original table in pm, divided by 100). Elements absent from the source table
    // (Fr, Bk…Og) fall back to [covalentRadius]'s default.
    private val radii = mapOf(
        "H" to 0.32, "He" to 0.46, "Li" to 1.33, "Be" to 1.02, "B" to 0.85, "C" to 0.75,
        "N" to 0.71, "O" to 0.63, "F" to 0.64, "Ne" to 0.67, "Na" to 1.55, "Mg" to 1.39,
        "Al" to 1.26, "Si" to 1.16, "P" to 1.11, "S" to 1.03, "Cl" to 0.99, "Ar" to 0.96,
        "K" to 1.96, "Ca" to 1.71, "Sc" to 1.48, "Ti" to 1.36, "V" to 1.34, "Cr" to 1.22,
        "Mn" to 1.19, "Fe" to 1.16, "Co" to 1.11, "Ni" to 1.10, "Cu" to 1.12, "Zn" to 1.18,
        "Ga" to 1.24, "Ge" to 1.21, "As" to 1.21, "Se" to 1.16, "Br" to 1.14, "Kr" to 1.17,
        "Rb" to 2.10, "Sr" to 1.85, "Y" to 1.63, "Zr" to 1.54, "Nb" to 1.47, "Mo" to 1.38,
        "Tc" to 1.28, "Ru" to 1.25, "Rh" to 1.25, "Pd" to 1.20, "Ag" to 1.28, "Cd" to 1.36,
        "In" to 1.42, "Sn" to 1.40, "Sb" to 1.40, "Te" to 1.36, "I" to 1.33, "Xe" to 1.31,
        "Cs" to 2.32, "Ba" to 1.96, "La" to 1.80, "Ce" to 1.63, "Pr" to 1.76, "Nd" to 1.74,
        "Pm" to 1.73, "Sm" to 1.72, "Eu" to 1.68, "Gd" to 1.69, "Tb" to 1.68, "Dy" to 1.67,
        "Ho" to 1.66, "Er" to 1.65, "Tm" to 1.64, "Yb" to 1.70, "Lu" to 1.62, "Hf" to 1.52,
        "Ta" to 1.46, "W" to 1.37, "Re" to 1.31, "Os" to 1.29, "Ir" to 1.22, "Pt" to 1.23,
        "Au" to 1.24, "Hg" to 1.33, "Tl" to 1.44, "Pb" to 1.44, "Bi" to 1.51, "Po" to 1.45,
        "At" to 1.47, "Rn" to 1.42, "Ra" to 2.01, "Ac" to 1.86, "Th" to 1.75, "Pa" to 1.69,
        "U" to 1.70, "Np" to 1.71, "Pu" to 1.72, "Am" to 1.66, "Cm" to 1.66,
    )

    fun mass(symbol: String) = masses[symbol] ?: symbols.indexOf(symbol).takeIf { it >= 0 }?.let { (it + 1) * 2.25 }
    fun covalentRadius(symbol: String) = radii[symbol] ?: 1.25
    fun normalizeElement(value: String): String {
        val match = Regex("[A-Z][a-z]?").find(value.trim()) ?: return "X"
        return match.value.takeIf { it in symbols } ?: "X"
    }
    fun defaultRadius(symbol: String) = (covalentRadius(symbol) * 0.42).coerceIn(0.22, 0.85)

    // Per v0.4.1: two radius sources. The auto-apply-radii button cycles between them; the default
    // bond-rule generator uses BONDING (键合半径). BONDING falls back to the covalent single-bond
    // table below for elements it lacks, then to [covalentRadius]'s default for elements neither
    // table covers (D, the XX placeholder, Fr, and 97+ actinides) so bond generation never silently
    // drops a pair.
    //
    // BONDING radii (键合半径) in Å. License: CC BY-SA 4.0; arXiv:2601.02017v1 [cond-mat.mtrl-sci] 05 Jan 2026.
    private val bondingRadii = mapOf(
        "Li" to 1.28, "Be" to 1.02, "B" to 0.88, "C" to 0.76, "N" to 0.68, "O" to 0.69,
        "F" to 0.59, "Na" to 1.70, "Mg" to 1.42, "Al" to 1.27, "Si" to 1.09, "P" to 1.02,
        "S" to 0.98, "Cl" to 0.95, "K" to 2.01, "Ca" to 1.71, "Sc" to 1.60, "Ti" to 1.39,
        "V" to 1.49, "Cr" to 1.33, "Mn" to 1.41, "Fe" to 1.30, "Co" to 1.30, "Ni" to 1.33,
        "Cu" to 1.27, "Zn" to 1.43, "Ga" to 1.42, "Ge" to 1.26, "As" to 1.21, "Se" to 1.17,
        "Br" to 1.18, "Kr" to 2.10, "Rb" to 2.13, "Sr" to 1.94, "Y" to 1.68, "Zr" to 1.76,
        "Nb" to 1.34, "Mo" to 1.52, "Tc" to 1.38, "Ru" to 1.37, "Rh" to 1.34, "Pd" to 1.32,
        "Ag" to 1.58, "Cd" to 1.59, "In" to 1.47, "Sn" to 1.45, "Sb" to 1.40, "Te" to 1.45,
        "I" to 1.38, "Xe" to 1.25, "Cs" to 2.31, "Ba" to 2.05, "La" to 1.85, "Ce" to 1.83,
        "Pr" to 1.84, "Nd" to 1.84, "Sm" to 1.85, "Eu" to 1.74, "Gd" to 1.73, "Tb" to 1.71,
        "Dy" to 1.58, "Ho" to 1.70, "Er" to 1.70, "Tm" to 1.70, "Yb" to 1.75, "Lu" to 1.63,
        "Hf" to 1.62, "Ta" to 1.45, "W" to 0.64, "Re" to 1.34, "Os" to 1.41, "Ir" to 1.39,
        "Pt" to 1.30, "Au" to 1.31, "Hg" to 1.37, "Tl" to 1.52, "Pb" to 1.76, "Bi" to 1.60,
        "Th" to 1.77, "U" to 1.08, "Np" to 1.15, "Pu" to 1.04,
    )
    // vdW radii in Å, sourced from .todos/atomic_radii.md (Wikipedia "Atomic radii of the elements"
    // data page, vdW column). Elements with no tabulated vdW value (most transition metals and
    // lanthanides; see the source table) fall back to [covalentRadius] at lookup time.
    private val vdwRadii = mapOf(
        "H" to 1.20, "He" to 1.40, "Li" to 1.82, "Be" to 1.53, "B" to 1.92, "C" to 1.70,
        "N" to 1.55, "O" to 1.52, "F" to 1.47, "Ne" to 1.54, "Na" to 2.27, "Mg" to 1.73,
        "Al" to 1.84, "Si" to 2.10, "P" to 1.80, "S" to 1.80, "Cl" to 1.75, "Ar" to 1.88,
        "K" to 2.75, "Ca" to 2.31, "Sc" to 2.11, "Ni" to 1.63, "Cu" to 1.40, "Zn" to 1.39,
        "Ga" to 1.87, "Ge" to 2.11, "As" to 1.85, "Se" to 1.90, "Br" to 1.85, "Kr" to 2.02,
        "Rb" to 3.03, "Sr" to 2.49, "Pd" to 1.63, "Ag" to 1.72, "Cd" to 1.58, "In" to 1.93,
        "Sn" to 2.17, "Sb" to 2.06, "Te" to 2.06, "I" to 1.98, "Xe" to 2.16, "Cs" to 3.43,
        "Ba" to 2.68, "Pt" to 1.75, "Au" to 1.66, "Hg" to 1.55, "Tl" to 1.96, "Pb" to 2.02,
        "Bi" to 2.07, "Po" to 1.97, "At" to 2.02, "Rn" to 2.20, "Fr" to 3.48, "Ra" to 2.83,
        "U" to 1.86,
    )
    // Covalent (single-bond) radii in Å, sourced from .todos/atomic_radii.md (Wikipedia "Atomic
    // radii of the elements" data page, Covalant(single bond) column). Serves as the BONDING
    // fallback for elements the bonding-radius table lacks (H, He, Ne, Ar, Pm, Po–Ac, Pa, Am, Cm).
    // Elements with no tabulated single-bond value (Fr, Bk…Og) fall back to [covalentRadius].
    private val iniCovalentRadii = mapOf(
        "H" to 0.32, "He" to 0.46, "Li" to 1.33, "Be" to 1.02, "B" to 0.85, "C" to 0.75,
        "N" to 0.71, "O" to 0.63, "F" to 0.64, "Ne" to 0.67, "Na" to 1.55, "Mg" to 1.39,
        "Al" to 1.26, "Si" to 1.16, "P" to 1.11, "S" to 1.03, "Cl" to 0.99, "Ar" to 0.96,
        "K" to 1.96, "Ca" to 1.71, "Sc" to 1.48, "Ti" to 1.36, "V" to 1.34, "Cr" to 1.22,
        "Mn" to 1.19, "Fe" to 1.16, "Co" to 1.11, "Ni" to 1.10, "Cu" to 1.12, "Zn" to 1.18,
        "Ga" to 1.24, "Ge" to 1.21, "As" to 1.21, "Se" to 1.16, "Br" to 1.14, "Kr" to 1.17,
        "Rb" to 2.10, "Sr" to 1.85, "Y" to 1.63, "Zr" to 1.54, "Nb" to 1.47, "Mo" to 1.38,
        "Tc" to 1.28, "Ru" to 1.25, "Rh" to 1.25, "Pd" to 1.20, "Ag" to 1.28, "Cd" to 1.36,
        "In" to 1.42, "Sn" to 1.40, "Sb" to 1.40, "Te" to 1.36, "I" to 1.33, "Xe" to 1.31,
        "Cs" to 2.32, "Ba" to 1.96, "La" to 1.80, "Ce" to 1.63, "Pr" to 1.76, "Nd" to 1.74,
        "Pm" to 1.73, "Sm" to 1.72, "Eu" to 1.68, "Gd" to 1.69, "Tb" to 1.68, "Dy" to 1.67,
        "Ho" to 1.66, "Er" to 1.65, "Tm" to 1.64, "Yb" to 1.70, "Lu" to 1.62, "Hf" to 1.52,
        "Ta" to 1.46, "W" to 1.37, "Re" to 1.31, "Os" to 1.29, "Ir" to 1.22, "Pt" to 1.23,
        "Au" to 1.24, "Hg" to 1.33, "Tl" to 1.44, "Pb" to 1.44, "Bi" to 1.51, "Po" to 1.45,
        "At" to 1.47, "Rn" to 1.42, "Ra" to 2.01, "Ac" to 1.86, "Th" to 1.75, "Pa" to 1.69,
        "U" to 1.70, "Np" to 1.71, "Pu" to 1.72, "Am" to 1.66, "Cm" to 1.66,
    )

    /** Radius for bond-rule generation under [source]. BONDING uses the bonding-radius table, then
     *  the covalent single-bond table, then [covalentRadius]; VDW uses the vdW table then
     *  [covalentRadius]. Elements covered by none (D, the XX placeholder, Fr, 97+ actinides) always
     *  reach the [covalentRadius] fallback so bond generation never silently drops a pair. */
    fun radius(symbol: String, source: RadiusSource): Double = when (source) {
        RadiusSource.BONDING -> bondingRadii[symbol] ?: iniCovalentRadii[symbol] ?: covalentRadius(symbol)
        RadiusSource.VDW -> vdwRadii[symbol] ?: covalentRadius(symbol)
    }

    fun resolveArgb(symbol: String, overrides: Map<String, Long> = emptyMap()) = overrides[symbol] ?: elementArgb(symbol)
    // Per v0.2.3: per-site color override (key = site id), falling back to the element override
    // then the VESTA palette. Same-element sites share a color by default unless individually set.
    fun resolveSiteArgb(siteId: String, element: String, siteOverrides: Map<String, Long> = emptyMap(), elementOverrides: Map<String, Long> = emptyMap()): Long =
        siteOverrides[siteId] ?: elementOverrides[element] ?: elementArgb(element)

    // Per v0.4.1: default element color from elements.ini's last three columns (RGB). Elements not
    // covered there (D, XX, and 96+ actinides) fall back to the VESTA palette below.
    private val elementColors = mapOf(
        "H" to 0xFFFFCCCCL, "He" to 0xFFFCE9CFL, "Li" to 0xFF86E074L, "Be" to 0xFF5FD87BL,
        "B" to 0xFF20A20FL, "C" to 0xFF814929L, "N" to 0xFFB0BAE6L, "O" to 0xFFFF0300L,
        "F" to 0xFFB0BAE6L, "Ne" to 0xFFFF38B5L, "Na" to 0xFFFADD3DL, "Mg" to 0xFFFC7C16L,
        "Al" to 0xFF81B3D6L, "Si" to 0xFF1B3BFAL, "P" to 0xFFC19CC3L, "S" to 0xFFFFFA00L,
        "Cl" to 0xFF32FC03L, "Ar" to 0xFFCFFEC5L, "K" to 0xFFA122F7L, "Ca" to 0xFF5B96BEL,
        "Sc" to 0xFFB663ACL, "Ti" to 0xFF78CAFFL, "V" to 0xFFE61A00L, "Cr" to 0xFF00009EL,
        "Mn" to 0xFFA9099EL, "Fe" to 0xFFB57200L, "Co" to 0xFF0000AFL, "Ni" to 0xFFB8BCBEL,
        "Cu" to 0xFF2247DDL, "Zn" to 0xFF8F9082L, "Ga" to 0xFF9FE474L, "Ge" to 0xFF7E6FA6L,
        "As" to 0xFF75D057L, "Se" to 0xFF9AEF10L, "Br" to 0xFF7F3103L, "Kr" to 0xFFFAC1F3L,
        "Rb" to 0xFFFF0099L, "Sr" to 0xFF00FF27L, "Y" to 0xFF67988EL, "Zr" to 0xFF00FF00L,
        "Nb" to 0xFF4CB376L, "Mo" to 0xFFB486B0L, "Tc" to 0xFFCDAFCBL, "Ru" to 0xFFCFB8AEL,
        "Rh" to 0xFFCED2ABL, "Pd" to 0xFFC2C4B9L, "Ag" to 0xFFB8BCBEL, "Cd" to 0xFFF31FDCL,
        "In" to 0xFFD781BBL, "Sn" to 0xFF9B8FBAL, "Sb" to 0xFFD88350L, "Te" to 0xFFADA252L,
        "I" to 0xFF8F1F8BL, "Xe" to 0xFF9BA1F8L, "Cs" to 0xFF0FFFB9L, "Ba" to 0xFF1EF02DL,
        "La" to 0xFF5AC449L, "Ce" to 0xFFD1FD06L, "Pr" to 0xFFFDE206L, "Nd" to 0xFFFC8E07L,
        "Pm" to 0xFF0000F5L, "Sm" to 0xFFFD067DL, "Eu" to 0xFFFB08D5L, "Gd" to 0xFFC004FFL,
        "Tb" to 0xFF7104FEL, "Dy" to 0xFF3106FDL, "Ho" to 0xFF0742FBL, "Er" to 0xFF49733BL,
        "Tm" to 0xFF0000E0L, "Yb" to 0xFF27FDF4L, "Lu" to 0xFF26FDB5L, "Hf" to 0xFFB4B459L,
        "Ta" to 0xFFB79B56L, "W" to 0xFF8E8A80L, "Re" to 0xFFB3B18EL, "Os" to 0xFFC9B179L,
        "Ir" to 0xFFC9CF73L, "Pt" to 0xFFCCC6BFL, "Au" to 0xFFFEB338L, "Hg" to 0xFFD3B8CCL,
        "Tl" to 0xFF96896DL, "Pb" to 0xFF53535BL, "Bi" to 0xFFD230F8L, "Po" to 0xFF0000FFL,
        "At" to 0xFF0000FFL, "Rn" to 0xFFFFFF00L, "Fr" to 0xFF000000L, "Ra" to 0xFF6EAA59L,
        "Ac" to 0xFF649E73L, "Th" to 0xFF26FE78L, "Pa" to 0xFF29FB35L, "U" to 0xFF7AA2AAL,
        "Np" to 0xFF4D4D4DL, "Pu" to 0xFF4D4D4DL, "Am" to 0xFF4D4D4DL,
    )
    fun elementArgb(symbol: String): Long = elementColors[symbol] ?: vestaArgb(symbol)
    fun vestaArgb(symbol: String): Long = when (symbol) {
        "H" -> 0xFFF4F4F4; "C" -> 0xFF505050; "N" -> 0xFF3050F8; "O" -> 0xFFFF0D0D
        "F", "Cl" -> 0xFF90E050; "Br" -> 0xFFA62929; "I" -> 0xFF940094; "S" -> 0xFFFFFF30
        "P" -> 0xFFFF8000; "Si" -> 0xFFF0C8A0; "B" -> 0xFFFFB5B5; "Li" -> 0xFFCC80FF
        "Na" -> 0xFFAB5CF2; "K" -> 0xFF8F40D4; "Cs" -> 0xFF57178F; "Mg" -> 0xFF8AFF00
        "Ca" -> 0xFF3DFF00; "Ti" -> 0xFFBFC2C7; "Fe" -> 0xFFE06633; "Co" -> 0xFFF090A0
        "Ni" -> 0xFF50D050; "Cu" -> 0xFFC88033; "Zn" -> 0xFF7D80B0; "Ag" -> 0xFFC0C0C0
        "Au" -> 0xFFFFD123; "Hg" -> 0xFFB8B8D0; "Al" -> 0xFFBFA6A6; "Pb" -> 0xFF575961
        else -> {
            val i = symbols.indexOf(symbol).coerceAtLeast(0)
            val hue = (i * 137.508).roundToInt() % 360
            hsvToArgb(hue.toDouble(), 0.55, 0.88)
        }
    }

    private fun hsvToArgb(h: Double, s: Double, v: Double): Long {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when (h.toInt() / 60) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        return (0xFFL shl 24) or (((r + m) * 255).roundToInt().toLong() shl 16) or
            (((g + m) * 255).roundToInt().toLong() shl 8) or ((b + m) * 255).roundToInt().toLong()
    }
}
