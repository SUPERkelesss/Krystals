package com.krystals.renderer.core.style

import com.krystals.crystal.data.PeriodicTableData
import kotlin.math.roundToInt

enum class FrameMode { NONE, SINGLE_CELL, ALL_CELLS }
enum class LineStyle { SOLID, DASHED }
enum class BondColorMode { BICOLOR, UNICOLOR }
enum class AxisMode { ABC, XYZ }

/**
 * Scene lighting: a constant ambient term plus a camera-anchored sun (directional)
 * term. The total light is split AMBIENT_RATIO (60%) ambient / SUN_RATIO (40%) sun.
 *
 * Sun parameters (v0.8.29 semantic rename):
 *  - [azimuthDegrees]  sun azimuth (light angle, former lightAzimuth)
 *  - [elevationDegrees] sun elevation (light height, former lightElevation)
 *  - [intensity]        sun strength (former reflection intensity)
 *  - [diffusion]        sun angular radius / highlight spread (former reflection diffusion)
 *
 * The sun is anchored to the camera (view-space fixed): rotating the crystal never
 * changes the sun-to-camera relationship.
 */
data class WorldLight(
    val azimuthDegrees: Float = 35f,
    val elevationDegrees: Float = 60f,
    val intensity: Float = 0.5f,
    val diffusion: Float = 0.5f,
) {
    init {
        require(elevationDegrees in 0f..90f) { "light elevation must be between 0 and 90 degrees" }
        require(intensity in 0f..1f) { "light intensity must be between 0 and 1" }
        require(diffusion in 0f..1f) { "light diffusion must be between 0 and 1" }
    }

    companion object {
        /** Ambient share of the total light: 50% (v0.8.42, was 60%). */
        const val AMBIENT_RATIO = 0.5f
        /** Sun (directional) share of the total light: 50% (v0.8.42, was 40%). */
        const val SUN_RATIO = 0.5f
    }
}

data class DepthCueing(
    val enabled: Boolean = true,
    val near: Float = -0.5f,
    val far: Float = -4.5f,
) {
    init {
        require(near >= far) { "depth cueing near must be greater than or equal to far" }
    }
}

data class AtomStyle(
    val opacity: Float = 1f,
    val reflective: Boolean = true,
)

data class BondStyle(
    val radius: Float = 0.15f,
    val opacity: Float = 1f,
    val colorMode: BondColorMode = BondColorMode.BICOLOR,
    val uniformArgb: Long = 0xFF9A90A0,
    val reflective: Boolean = true,
)

data class PolyhedronStyle(
    val enabled: Boolean = true,
    val opacity: Float = 0.5f,
    val reflective: Boolean = true,
)

data class FrameStyle(
    val mode: FrameMode = FrameMode.SINGLE_CELL,
    val lineStyle: LineStyle = LineStyle.SOLID,
)

data class AxisStyle(
    val visible: Boolean = true,
    val mode: AxisMode = AxisMode.ABC,
    val offsetX: Float = 0.08f,
    val offsetY: Float = 0.08f,
)

data class RenderEnvironment(
    val backgroundArgb: Long = 0xFF101014,
    val worldLight: WorldLight = WorldLight(),
    val depthCueing: DepthCueing = DepthCueing(),
    val atoms: AtomStyle = AtomStyle(),
    val bonds: BondStyle = BondStyle(),
    val polyhedra: PolyhedronStyle = PolyhedronStyle(),
    val frame: FrameStyle = FrameStyle(),
    val axes: AxisStyle = AxisStyle(),
) {
    init {
        require((backgroundArgb ushr 32) == 0L) { "background color must be a 32-bit ARGB value" }
        require(atoms.opacity in 0f..1f) { "atom opacity must be between 0 and 1" }
        require(bonds.radius > 0f) { "bond radius must be positive" }
        require(bonds.opacity in 0f..1f) { "bond opacity must be between 0 and 1" }
        require(polyhedra.opacity in 0f..1f) { "polyhedron opacity must be between 0 and 1" }
    }
}

data class ViewerAppearance(
    val backgroundArgb: Long = 0xFF101014,
    val reflectionEnabled: Boolean = true,
    val lightAzimuth: Float = 150f,
    val lightElevation: Float = 45f,
    val lightIntensity: Float = 0.4f,
    val diffusion: Float = 0.5f,
    val atomOpacity: Float = 1.0f,
    val frameMode: FrameMode = FrameMode.SINGLE_CELL,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val bondRadius: Float = 0.075f,
    val bondOpacity: Float = 1.0f,
    val bondColorMode: BondColorMode = BondColorMode.BICOLOR,
    val uniformBondArgb: Long = 0xFF9A90A0,
    val bondReflectionEnabled: Boolean = true,
    val polyhedronEnabled: Boolean = true,
    val polyhedronOpacity: Float = 0.5f,
    val polyhedronReflectionEnabled: Boolean = true,
    // Per v0.8.30: hydrogen-bond appearance (radius in Å, opacity 0..1).
    val hbondRadius: Float = 0.05f,
    val hbondOpacity: Float = 0.2f,
    val showAxes: Boolean = true,
    val axisMode: AxisMode = AxisMode.ABC,
    val axisOffsetX: Float = 0.08f,
    val axisOffsetY: Float = 0.08f,
    val depthOfFieldEnabled: Boolean = true,
    val dofNear: Float = -0.5f,
    val dofFar: Float = -4.5f,
) {
    init {
        require(atomOpacity in 0f..1f) { "atom opacity must be between 0 and 1" }
        require(bondOpacity in 0f..1f) { "bond opacity must be between 0 and 1" }
        require(polyhedronOpacity in 0f..1f) { "polyhedron opacity must be between 0 and 1" }
        require(hbondOpacity in 0f..1f) { "h-bond opacity must be between 0 and 1" }
        require(hbondRadius in 0.01f..0.15f) { "h-bond radius must be between 0.01 and 0.15" }
    }

    fun toEnvironment() = RenderEnvironment(
        backgroundArgb = backgroundArgb,
        worldLight = WorldLight(lightAzimuth, lightElevation, lightIntensity, diffusion),
        depthCueing = DepthCueing(depthOfFieldEnabled, dofNear, dofFar),
        atoms = AtomStyle(atomOpacity, reflectionEnabled),
        bonds = BondStyle(bondRadius, bondOpacity, bondColorMode, uniformBondArgb, bondReflectionEnabled),
        polyhedra = PolyhedronStyle(polyhedronEnabled, polyhedronOpacity, polyhedronReflectionEnabled),
        frame = FrameStyle(frameMode, lineStyle),
        axes = AxisStyle(showAxes, axisMode, axisOffsetX, axisOffsetY),
    )
}

data class RenderConfiguration(
    val elementArgbOverrides: Map<String, Long> = emptyMap(),
    val siteArgbOverrides: Map<String, Long> = emptyMap(),
)

object RenderPalette {
    fun defaultRadius(symbol: String): Double =
        (PeriodicTableData.covalentRadius(symbol) * 0.42).coerceIn(0.22, 0.85)

    fun elementArgb(symbol: String): Long = PeriodicTableData.elementArgbValue(symbol) ?: vestaArgb(symbol)

    fun resolveArgb(symbol: String, configuration: RenderConfiguration = RenderConfiguration()): Long =
        configuration.elementArgbOverrides[symbol] ?: elementArgb(symbol)

    fun resolveSiteArgb(siteId: String, symbol: String, configuration: RenderConfiguration): Long =
        configuration.siteArgbOverrides[siteId]
            ?: configuration.elementArgbOverrides[symbol]
            ?: elementArgb(symbol)

    fun vestaArgb(symbol: String): Long = when (symbol) {
        "H" -> 0xFFF4F4F4; "C" -> 0xFF505050; "N" -> 0xFF3050F8; "O" -> 0xFFFF0D0D
        "F", "Cl" -> 0xFF90E050; "Br" -> 0xFFA62929; "I" -> 0xFF940094; "S" -> 0xFFFFFF30
        "P" -> 0xFFFF8000; "Si" -> 0xFFF0C8A0; "B" -> 0xFFFFB5B5; "Li" -> 0xFFCC80FF
        "Na" -> 0xFFAB5CF2; "K" -> 0xFF8F40D4; "Cs" -> 0xFF57178F; "Mg" -> 0xFF8AFF00
        "Ca" -> 0xFF3DFF00; "Ti" -> 0xFFBFC2C7; "Fe" -> 0xFFE06633; "Co" -> 0xFFF090A0
        "Ni" -> 0xFF50D050; "Cu" -> 0xFFC88033; "Zn" -> 0xFF7D80B0; "Ag" -> 0xFFC0C0C0
        "Au" -> 0xFFFFD123; "Hg" -> 0xFFB8B8D0; "Al" -> 0xFFBFA6A6; "Pb" -> 0xFF575961
        else -> {
            val index = PeriodicTableData.symbols.indexOf(symbol).coerceAtLeast(0)
            hsvToArgb((index * 137.508).roundToInt() % 360, 0.55, 0.88)
        }
    }

    private fun hsvToArgb(h: Int, s: Double, v: Double): Long {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when (h / 60) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        return (0xFFL shl 24) or (((r + m) * 255).roundToInt().toLong() shl 16) or
            (((g + m) * 255).roundToInt().toLong() shl 8) or ((b + m) * 255).roundToInt().toLong()
    }
}
