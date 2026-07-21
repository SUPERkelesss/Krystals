package com.krystals.crystal.analysis.editing

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Mat3
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import kotlin.math.abs

sealed interface EditCommand {
    data class SetLattice(val lattice: Lattice) : EditCommand
    data class SetSpaceGroup(val symbol: String) : EditCommand
    data class AddAtom(
        val species: Species,
        val label: String,
        val fractionalCoordinate: FractionalCoordinate,
        val occupancy: Double,
    ) : EditCommand
    data class UpdateAtom(
        val siteId: String,
        val species: Species,
        val label: String,
        val fractionalCoordinate: FractionalCoordinate,
        val occupancy: Double,
    ) : EditCommand
    data class DeleteAtom(val siteId: String) : EditCommand
    data class SetBondRule(val rule: BondRule) : EditCommand
    data class RemoveBondRule(val key: String) : EditCommand
    data class Transform(val rows: List<List<Int>>, val translation: FractionalCoordinate = FractionalCoordinate.ZERO) : EditCommand
}

data class EditResult(
    val structure: CrystalStructure,
    val bondConfiguration: BondConfiguration,
    val warnings: List<String> = emptyList(),
)

object CrystalEditor {
    const val SMART_IONIC_UNAVAILABLE: String = "smart-ionic-unavailable"
    const val SMART_IONIC_TIMEOUT: String = "smart-ionic-timeout"

    fun apply(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        command: EditCommand,
    ): EditResult = when (command) {
        is EditCommand.SetLattice -> EditResult(
            structure.copy(lattice = constrainLattice(command.lattice, structure.spaceGroup.number)),
            bondConfiguration,
        )
        is EditCommand.SetSpaceGroup -> {
            val group = SpaceGroupCatalog.find(command.symbol) ?: error("Unknown space group: ${command.symbol}")
            val constrained = constrainLattice(structure.lattice, group.number)
            EditResult(
                structure.copy(
                    lattice = constrained,
                    spaceGroup = group,
                    symmetryOperations = SpaceGroupCatalog.operations(group.symbol),
                ),
                bondConfiguration,
            )
        }
        is EditCommand.AddAtom -> {
            val label = uniqueLabel(command.label.ifBlank { command.species.symbol }, structure.sites)
            val site = Site(
                id = "$label#${System.nanoTime()}",
                label = label,
                species = Species(PeriodicTable.normalizeElement(command.species.symbol)),
                fractionalCoordinate = command.fractionalCoordinate.wrapped(),
                occupancy = command.occupancy.coerceIn(0.0, 1.0),
            )
            val withRules = ensureAutoBondRules(structure.copy(sites = structure.sites + site), bondConfiguration)
            withRules.copy(warnings = occupancyWarnings(command.occupancy) + withRules.warnings)
        }
        is EditCommand.UpdateAtom -> {
            require(structure.sites.any { it.id == command.siteId }) { "Atom site not found" }
            val sites = structure.sites.map { site ->
                if (site.id != command.siteId) site else site.copy(
                    label = command.label.ifBlank { site.label },
                    species = Species(PeriodicTable.normalizeElement(command.species.symbol)),
                    fractionalCoordinate = command.fractionalCoordinate.wrapped(),
                    occupancy = command.occupancy.coerceIn(0.0, 1.0),
                )
            }
            EditResult(structure.copy(sites = sites), bondConfiguration, occupancyWarnings(command.occupancy))
        }
        is EditCommand.DeleteAtom -> EditResult(
            structure.copy(sites = structure.sites.filterNot { it.id == command.siteId }),
            bondConfiguration.copy(rules = bondConfiguration.rules.filterNot { it.siteA == command.siteId || it.siteB == command.siteId }),
        )
        is EditCommand.SetBondRule -> EditResult(
            structure,
            bondConfiguration.add(command.rule),
        )
        is EditCommand.RemoveBondRule -> EditResult(
            structure,
            bondConfiguration.copy(
                rules = bondConfiguration.rules.filterNot { it.key == command.key },
                disabledPairs = bondConfiguration.disabledPairs + command.key,
            ),
        )
        is EditCommand.Transform -> transform(structure, bondConfiguration, command.rows, command.translation)
    }

    fun ensureAutoBondRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double = 0.45,
    ): EditResult {
        val existingKeys = bondConfiguration.rules.map { it.key }.toSet()
        val generated = smartOrBondingRules(structure, bondConfiguration, epsilon)
        return EditResult(structure, bondConfiguration.copy(rules = bondConfiguration.rules + generated.filterNot { it.key in existingKeys }))
    }

    fun fromSmartIonicAttempt(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double,
        smartIonic: BondValence.SmartIonicResult?,
    ): EditResult {
        val existingKeys = bondConfiguration.rules.map { it.key }.toSet()
        val sizeGuarded = SymmetryExpander.expand(structure).size > BondValence.SMART_IONIC_ATOM_LIMIT
        var timedOut = false
        val generated = if (!sizeGuarded && smartIonic != null && smartIonic.success) {
            smartIonic.rules
        } else {
            if (!sizeGuarded && smartIonic == null) timedOut = true
            bondingRules(structure, epsilon)
        }
        val warnings = if (timedOut) listOf(SMART_IONIC_TIMEOUT) else emptyList()
        return EditResult(
            structure,
            bondConfiguration.copy(rules = bondConfiguration.rules + generated.filterNot { it.key in existingKeys }),
            warnings,
        )
    }

    private fun smartOrBondingRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double,
    ): List<BondRule> {
        if (SymmetryExpander.expand(structure).size <= BondValence.SMART_IONIC_ATOM_LIMIT) {
            val result = BondValence.smartIonicRules(structure, bondConfiguration, epsilon)
            if (result.success) return result.rules
        }
        return bondingRules(structure, epsilon)
    }

    private fun bondingRules(structure: CrystalStructure, epsilon: Double = 0.45): List<BondRule> =
        structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                if (PeriodicTable.anionValence(siteA.species.symbol) != null &&
                    PeriodicTable.anionValence(siteB.species.symbol) != null
                ) return@mapNotNull null
                BondRule(
                    siteA.id,
                    siteB.id,
                    0.1,
                    PeriodicTable.radius(siteA.species.symbol, RadiusSource.BONDING) +
                        PeriodicTable.radius(siteB.species.symbol, RadiusSource.BONDING) + epsilon,
                    BondRuleSource.CUSTOM,
                )
            }
        }

    fun rebuildBondRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        source: RadiusSource,
        epsilon: Double = 0.45,
    ): EditResult {
        if (source == RadiusSource.SMART_IONIC) {
            val result = BondValence.smartIonicRules(structure, bondConfiguration, epsilon)
            if (result.success) return EditResult(structure, BondConfiguration(result.rules))
            return EditResult(structure, BondConfiguration(bondingRules(structure, epsilon)), listOf(SMART_IONIC_UNAVAILABLE))
        }
        val rules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                if (PeriodicTable.anionValence(siteA.species.symbol) != null &&
                    PeriodicTable.anionValence(siteB.species.symbol) != null
                ) return@mapNotNull null
                BondRule(
                    siteA.id,
                    siteB.id,
                    0.1,
                    PeriodicTable.radius(siteA.species.symbol, source) +
                        PeriodicTable.radius(siteB.species.symbol, source) + epsilon,
                    BondRuleSource.CUSTOM,
                )
            }
        }
        return EditResult(structure, BondConfiguration(rules))
    }

    private fun transform(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        rows: List<List<Int>>,
        translation: FractionalCoordinate,
    ): EditResult {
        require(rows.size == 3 && rows.all { it.size == 3 }) { "Transformation matrix must be 3x3" }
        val transform = Mat3.fromRows(rows)
        val determinant = transform.determinant()
        require(abs(determinant) >= 1.0 - 1e-9) { "Transformation matrix must be non-singular" }
        val multiplicity = abs(determinant).toInt()
        require(multiplicity <= 64) { "Transformation determinant is too large (maximum 64)" }
        val newCellMatrix = structure.lattice.matrix * transform
        val inverse = transform.inverse()
        val sites = mutableListOf<Site>()
        structure.sites.forEach { site ->
            val generated = mutableListOf<FractionalCoordinate>()
            outer@ for (i in 0 until multiplicity) for (j in 0 until multiplicity) for (k in 0 until multiplicity) {
                val candidate = FractionalCoordinate.fromVec3(
                    inverse * (site.fractionalCoordinate.toVec3() + Vec3(i.toDouble(), j.toDouble(), k.toDouble())) + translation.toVec3(),
                ).wrapped()
                if (generated.none { it.almostEquals(candidate) }) generated += candidate
                if (generated.size == multiplicity) break@outer
            }
            generated.forEachIndexed { index, position ->
                val label = if (multiplicity == 1) site.label else "${site.label}_${index + 1}"
                sites += site.copy(id = "${site.id}:T${index + 1}", label = label, fractionalCoordinate = position)
            }
        }
        return EditResult(structure.copy(lattice = Lattice.fromMatrix(newCellMatrix), sites = sites), BondConfiguration())
    }

    fun convertHexRhom(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        toRhombohedral: Boolean,
    ): EditResult {
        val rows = if (toRhombohedral) listOf(listOf(1, 0, 0), listOf(1, 1, 0), listOf(1, 1, 1))
        else listOf(listOf(1, 0, 0), listOf(-1, 1, 0), listOf(0, -1, 1))
        val transform = Mat3.fromRows(rows)
        val inverse = transform.inverse()
        val newCellMatrix = structure.lattice.matrix * transform
        val sites = structure.sites.map { site ->
            site.copy(
                fractionalCoordinate = FractionalCoordinate.fromVec3(
                    (inverse * site.fractionalCoordinate.toVec3()),
                ).wrapped(),
            )
        }
        val newStructure = structure.copy(lattice = Lattice.fromMatrix(newCellMatrix), sites = sites)
        return ensureAutoBondRules(newStructure, BondConfiguration())
    }

    private fun constrainLattice(lattice: Lattice, groupNumber: Int?): Lattice = when (groupNumber ?: 1) {
        in 3..15 -> lattice.copy(alpha = 90.0, gamma = 90.0)
        in 16..74 -> lattice.copy(alpha = 90.0, beta = 90.0, gamma = 90.0)
        in 75..142 -> lattice.copy(b = lattice.a, alpha = 90.0, beta = 90.0, gamma = 90.0)
        in 143..194 -> lattice.copy(b = lattice.a, alpha = 90.0, beta = 90.0, gamma = 120.0)
        in 195..230 -> lattice.copy(b = lattice.a, c = lattice.a, alpha = 90.0, beta = 90.0, gamma = 90.0)
        else -> lattice
    }

    private fun uniqueLabel(base: String, sites: List<Site>): String {
        if (sites.none { it.label == base }) return base
        var suffix = 2
        while (sites.any { it.label == "$base$suffix" }) suffix++
        return "$base$suffix"
    }

    private fun occupancyWarnings(value: Double) = if (value in 0.0..1.0) emptyList() else listOf("Occupancy was clamped to 0-1")
}
