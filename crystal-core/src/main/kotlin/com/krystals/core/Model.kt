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
)

enum class BondRuleSource { CUSTOM, EXPLICIT, AUTO }

data class BondRule(
    val siteA: String,
    val siteB: String,
    val minAngstrom: Double,
    val maxAngstrom: Double,
    val source: BondRuleSource = BondRuleSource.CUSTOM,
) {
    init {
        require(minAngstrom >= 0.0 && maxAngstrom >= minAngstrom)
    }
    val key: String get() = listOf(siteA, siteB).sorted().joinToString("\u0000")
}

data class Bond(val atomA: Long, val atomB: Long, val distance: Double, val rule: BondRule)

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
    fun resolveArgb(symbol: String, overrides: Map<String, Long> = emptyMap()) = overrides[symbol] ?: vestaArgb(symbol)
    // Per v0.2.3: per-site color override (key = site id), falling back to the element override
    // then the VESTA palette. Same-element sites share a color by default unless individually set.
    fun resolveSiteArgb(siteId: String, element: String, siteOverrides: Map<String, Long> = emptyMap(), elementOverrides: Map<String, Long> = emptyMap()): Long =
        siteOverrides[siteId] ?: elementOverrides[element] ?: vestaArgb(element)
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
