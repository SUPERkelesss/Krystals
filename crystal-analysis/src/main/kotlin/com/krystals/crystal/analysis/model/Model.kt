package com.krystals.crystal.analysis.model

import com.krystals.crystal.core.Int3
import com.krystals.crystal.core.SpaceGroupCatalog
import com.krystals.crystal.core.SymmetryOperation
import com.krystals.crystal.core.UnitCell
import com.krystals.crystal.core.Vec3
import com.krystals.crystal.data.PeriodicTableData

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
    val isShell: Boolean = false,
    val isBoundaryImage: Boolean = false,
) {
    val isExternalShell: Boolean get() = isShell && !isBoundaryImage
}

enum class BondRuleSource { CUSTOM, EXPLICIT, AUTO }

typealias RadiusSource = com.krystals.crystal.data.RadiusSource
typealias BondValenceParam = com.krystals.crystal.data.BondValenceParam

data class BondRule(
    val siteA: String,
    val siteB: String,
    val minAngstrom: Double,
    val maxAngstrom: Double,
    val source: BondRuleSource = BondRuleSource.CUSTOM,
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
    val disabledBondPairs: Set<String> = emptySet(),
    val siteArgbOverrides: Map<String, Long> = emptyMap(),
) {
    val effectiveSymmetryOperations: List<SymmetryOperation>
        get() = symmetryOperations.ifEmpty { SpaceGroupCatalog.operations(spaceGroupName) }
}

data class Expansion(val x: Int = 1, val y: Int = 1, val z: Int = 1) {
    init {
        require(x > 0 && y > 0 && z > 0)
    }

    val multiplier: Int get() = x * y * z
}

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
    val symbols: List<String> get() = PeriodicTableData.symbols

    fun mass(symbol: String): Double? = PeriodicTableData.mass(symbol)
    fun covalentRadius(symbol: String): Double = PeriodicTableData.covalentRadius(symbol)
    fun normalizeElement(value: String): String = PeriodicTableData.normalizeElement(value)
    fun defaultRadius(symbol: String): Double = PeriodicTableData.defaultRadius(symbol)
    fun radius(symbol: String, source: RadiusSource): Double = PeriodicTableData.radius(symbol, source)
    fun resolveArgb(symbol: String, overrides: Map<String, Long> = emptyMap()): Long =
        PeriodicTableData.resolveArgb(symbol, overrides)

    fun resolveSiteArgb(
        siteId: String,
        element: String,
        siteOverrides: Map<String, Long> = emptyMap(),
        elementOverrides: Map<String, Long> = emptyMap(),
    ): Long = PeriodicTableData.resolveSiteArgb(siteId, element, siteOverrides, elementOverrides)

    fun elementArgb(symbol: String): Long = PeriodicTableData.elementArgb(symbol)
    fun vestaArgb(symbol: String): Long = PeriodicTableData.vestaArgb(symbol)
    fun bondValenceParam(cation: String, cationValence: Int, anion: String, anionValence: Int): BondValenceParam? =
        PeriodicTableData.bondValenceParam(cation, cationValence, anion, anionValence)

    fun cationValences(cation: String): Set<Int> = PeriodicTableData.cationValences(cation)
    fun shannonCrystalRadius(ion: String, charge: Int, cn: Int): Double? =
        PeriodicTableData.shannonCrystalRadius(ion, charge, cn)

    fun anionValence(element: String): Int? = PeriodicTableData.anionValence(element)
    fun isAnion(element: String, other: String): Boolean = PeriodicTableData.isAnion(element, other)
    fun electronegativityPublic(element: String): Double = PeriodicTableData.electronegativityPublic(element)
}
