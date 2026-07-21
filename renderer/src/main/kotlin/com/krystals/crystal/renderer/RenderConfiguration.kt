package com.krystals.crystal.renderer

import com.krystals.crystal.data.PeriodicTableData
import kotlin.math.roundToInt

enum class FrameMode { NONE, SINGLE_CELL, ALL_CELLS }
enum class LineStyle { SOLID, DASHED }
enum class BondColorMode { BICOLOR, UNICOLOR }
enum class AxisMode { ABC, XYZ }

data class ViewerAppearance(
    val backgroundArgb: Long = 0xFF101014,
    val reflectionEnabled: Boolean = true,
    val lightAzimuth: Float = 25f,
    val lightElevation: Float = 45f,
    val lightIntensity: Float = 0.4f,
    val diffusion: Float = 0.7f,
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
    val depthOfFieldEnabled: Boolean = true,
    val dofNear: Float = 0.5f,
    val dofFar: Float = -4.5f,
)

data class RenderConfiguration(
    val elementArgbOverrides: Map<String, Long> = emptyMap(),
    val siteArgbOverrides: Map<String, Long> = emptyMap(),
)

object RenderPalette {
    fun defaultRadius(symbol: String): Double =
        (PeriodicTableData.covalentRadius(symbol) * 0.42).coerceIn(0.22, 0.85)

    fun elementArgb(symbol: String): Long = PeriodicTableData.elementArgbValue(symbol) ?: vestaArgb(symbol)

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
            val hue = (index * 137.508).roundToInt() % 360
            hsvToArgb(hue.toDouble(), 0.55, 0.88)
        }
    }
    fun resolveArgb(symbol: String, configuration: RenderConfiguration = RenderConfiguration()): Long =
        configuration.elementArgbOverrides[symbol] ?: elementArgb(symbol)

    fun resolveSiteArgb(siteId: String, symbol: String, configuration: RenderConfiguration): Long =
        configuration.siteArgbOverrides[siteId]
            ?: configuration.elementArgbOverrides[symbol]
            ?: elementArgb(symbol)

    private fun hsvToArgb(h: Double, s: Double, v: Double): Long {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when (h.toInt() / 60) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return (0xFFL shl 24) or (((r + m) * 255).roundToInt().toLong() shl 16) or
            (((g + m) * 255).roundToInt().toLong() shl 8) or ((b + m) * 255).roundToInt().toLong()
    }
}
