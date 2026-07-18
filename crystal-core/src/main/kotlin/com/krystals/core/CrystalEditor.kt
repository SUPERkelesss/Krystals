package com.krystals.core

import kotlin.math.abs

sealed interface EditCommand {
    data class SetCell(val cell: UnitCell) : EditCommand
    data class SetSpaceGroup(val name: String) : EditCommand
    data class AddAtom(val element: String, val label: String, val fractional: Vec3, val occupancy: Double) : EditCommand
    data class UpdateAtom(val siteId: String, val element: String, val label: String, val fractional: Vec3, val occupancy: Double) : EditCommand
    data class DeleteAtom(val siteId: String) : EditCommand
    data class SetBondRule(val rule: BondRule) : EditCommand
    data class RemoveBondRule(val key: String) : EditCommand
    data class Transform(val rows: List<List<Int>>, val translation: Vec3 = Vec3.ZERO) : EditCommand
}

data class EditResult(val structure: CrystalStructure, val warnings: List<String> = emptyList())

object CrystalEditor {

    /** Sentinel placed in [EditResult.warnings] when a manual smart-ionic rebuild could not analyse the structure. */
    const val SMART_IONIC_UNAVAILABLE: String = "smart-ionic-unavailable"
    fun apply(structure: CrystalStructure, command: EditCommand): EditResult = when (command) {
        is EditCommand.SetCell -> EditResult(structure.copy(cell = constrainCell(command.cell, structure.spaceGroupNumber)))
        is EditCommand.SetSpaceGroup -> {
            val group = SpaceGroupCatalog.find(command.name) ?: error("Unknown space group: ${command.name}")
            val constrained = constrainCell(structure.cell, group.number)
            EditResult(structure.copy(
                cell = constrained, spaceGroupName = group.symbol, spaceGroupNumber = group.number,
                symmetryOperations = SpaceGroupCatalog.operations(group.symbol),
            ))
        }
        is EditCommand.AddAtom -> {
            val label = uniqueLabel(command.label.ifBlank { command.element }, structure.sites)
            val site = AtomSite(
                id = "$label#${System.nanoTime()}", label = label,
                element = PeriodicTable.normalizeElement(command.element), fractional = command.fractional.wrapped(),
                occupancy = command.occupancy.coerceIn(0.0, 1.0),
            )
            // Per v0.2: creating an atom checks its distance to every other atom and adds matching bond rules.
            val withRules = ensureAutoBondRules(structure.copy(sites = structure.sites + site))
            withRules.copy(warnings = occupancyWarnings(command.occupancy) + withRules.warnings)
        }
        is EditCommand.UpdateAtom -> {
            require(structure.sites.any { it.id == command.siteId }) { "Atom site not found" }
            val sites = structure.sites.map { site ->
                if (site.id != command.siteId) site else site.copy(
                    label = command.label.ifBlank { site.label }, element = PeriodicTable.normalizeElement(command.element),
                    fractional = command.fractional.wrapped(), occupancy = command.occupancy.coerceIn(0.0, 1.0),
                )
            }
            EditResult(structure.copy(sites = sites), occupancyWarnings(command.occupancy))
        }
        is EditCommand.DeleteAtom -> EditResult(structure.copy(
            sites = structure.sites.filterNot { it.id == command.siteId },
            bondRules = structure.bondRules.filterNot { it.siteA == command.siteId || it.siteB == command.siteId },
        ))
        is EditCommand.SetBondRule -> {
            // Per v0.3.44: update the matching rule in place to preserve the bondRules list order,
            // so toggling extendAcrossCell does not reshuffle the display order of bond rules.
            val rules = structure.bondRules.map { if (it.key == command.rule.key) command.rule else it }
            // Re-adding a rule for a previously disabled pair re-enables it.
            EditResult(structure.copy(bondRules = rules, disabledBondPairs = structure.disabledBondPairs - command.rule.key))
        }
        // Per v0.2.3: removing a rule disables the pair so the covalent-radius fallback won't
        // redraw the bond. The rule is dropped from bondRules (hidden from lists) AND recorded.
        is EditCommand.RemoveBondRule -> EditResult(structure.copy(
            bondRules = structure.bondRules.filterNot { it.key == command.key },
            disabledBondPairs = structure.disabledBondPairs + command.key,
        ))
        is EditCommand.Transform -> transform(structure, command.rows, command.translation)
    }

    fun ensureAutoBondRules(structure: CrystalStructure): EditResult {
        val existingKeys = structure.bondRules.map { it.key }.toSet()
        val generated = smartOrBondingRules(structure)
        val newRules = generated.filterNot { it.key in existingKeys }
        return EditResult(structure.copy(bondRules = structure.bondRules + newRules))
    }

    /**
     * Per v0.5.0: the default rule set is "smart ionic" (智能离子) - per-site Shannon radii from a BVS
     * oxidation-state estimate ([BondValence.smartIonicRules]). When the structure cannot be
     * analysed (no ionic pairs) or exceeds [BondValence.SMART_IONIC_ATOM_LIMIT] expanded atoms
     * (performance guard), fall back to the bonding-radius rule set. Backs [ensureAutoBondRules]
     * (default path, size-guarded). The manual menu item goes through [rebuildBondRules].
     */
    private fun smartOrBondingRules(structure: CrystalStructure): List<BondRule> {
        val sizeGuarded = CrystalEngine.expandAsymmetricUnit(structure).size > BondValence.SMART_IONIC_ATOM_LIMIT
        if (!sizeGuarded) {
            val result = BondValence.smartIonicRules(structure)
            if (result.success) return result.rules
        }
        return bondingRules(structure)
    }

    /** Plain bonding-radius rule per site pair (the v0.4.1 default; now the smart-ionic fallback).
     *  Uses drop(i) so same-site pairs (a site bonded to itself across a cell image) are included —
     *  a single-site structure must still get a self-bond rule, matching the v0.4.1 generator. */
    private fun bondingRules(structure: CrystalStructure): List<BondRule> =
        structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).map { siteB ->
                BondRule(
                    siteA.id, siteB.id, 0.1,
                    PeriodicTable.radius(siteA.element, RadiusSource.BONDING) + PeriodicTable.radius(siteB.element, RadiusSource.BONDING) + 0.45,
                    BondRuleSource.CUSTOM,
                )
            }
        }

    /**
     * Per v0.4.1/v0.5.0: clear every existing bond rule (and the disabled-pair record) and
     * regenerate a rule for every site pair using the given [source]. Used by the bond editor's
     * "auto-apply radii" button. SMART_IONIC is not size-guarded here (the user explicitly asked
     * for it) but still falls back to bonding radii when the structure cannot be analysed; in that
     * case [EditResult.warnings] carries [SMART_IONIC_UNAVAILABLE] for the UI.
     */
    fun rebuildBondRules(structure: CrystalStructure, source: RadiusSource): EditResult {
        if (source == RadiusSource.SMART_IONIC) {
            val result = BondValence.smartIonicRules(structure)
            if (result.success) {
                return EditResult(structure.copy(bondRules = result.rules, disabledBondPairs = emptySet()))
            }
            return EditResult(
                structure.copy(bondRules = bondingRules(structure), disabledBondPairs = emptySet()),
                warnings = listOf(SMART_IONIC_UNAVAILABLE),
            )
        }
        val newRules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).map { siteB ->
                BondRule(
                    siteA.id, siteB.id, 0.1,
                    PeriodicTable.radius(siteA.element, source) + PeriodicTable.radius(siteB.element, source) + 0.45,
                    BondRuleSource.CUSTOM,
                )
            }
        }
        return EditResult(structure.copy(bondRules = newRules, disabledBondPairs = emptySet()))
    }

    private fun transform(structure: CrystalStructure, rows: List<List<Int>>, translation: Vec3): EditResult {
        require(rows.size == 3 && rows.all { it.size == 3 }) { "Transformation matrix must be 3x3" }
        val transform = Mat3.fromRows(rows)
        val determinant = transform.determinant()
        require(abs(determinant) >= 1.0 - 1e-9) { "Transformation matrix must be non-singular" }
        val multiplicity = abs(determinant).toInt()
        require(multiplicity <= 64) { "Transformation determinant is too large (maximum 64)" }
        val newCellMatrix = structure.cell.matrix * transform
        val inverse = transform.inverse()
        val sites = mutableListOf<AtomSite>()
        structure.sites.forEach { site ->
            val generated = mutableListOf<Vec3>()
            outer@ for (i in 0 until multiplicity) for (j in 0 until multiplicity) for (k in 0 until multiplicity) {
                // Per v0.3.3: x' = M⁻¹·x + t — the linear transform is applied first, then the
                // translation in the new (transformed) fractional basis. The lattice itself is
                // unaffected by t (an origin shift never changes the basis vectors).
                val candidate = (inverse * (site.fractional + Vec3(i.toDouble(), j.toDouble(), k.toDouble())) + translation).wrapped()
                if (generated.none { it.almostEquals(candidate) }) generated += candidate
                if (generated.size == multiplicity) break@outer
            }
            generated.forEachIndexed { index, position ->
                val label = if (multiplicity == 1) site.label else "${site.label}_${index + 1}"
                sites += site.copy(id = "${site.id}:T${index + 1}", label = label, fractional = position)
            }
        }
        return EditResult(structure.copy(cell = UnitCell.fromMatrix(newCellMatrix), sites = sites, bondRules = emptyList(), disabledBondPairs = emptySet()))
    }

    /**
     * Convert an R-lattice trigonal structure between the hexagonal and rhombohedral settings.
     * hex → rhom uses the matrix [[1,0,0],[1,1,0],[1,1,1]]; rhom → hex uses its inverse.
     * Unlike [transform], this preserves the (non-orthogonal) rhombohedral cell angles instead of
     * re-applying [constrainCell], which would force the hexagonal a=b, α=β=90, γ=120 constraint.
     */
    fun convertHexRhom(structure: CrystalStructure, toRhombohedral: Boolean): EditResult {
        val rows = if (toRhombohedral) listOf(listOf(1, 0, 0), listOf(1, 1, 0), listOf(1, 1, 1))
        else listOf(listOf(1, 0, 0), listOf(-1, 1, 0), listOf(0, -1, 1))
        val transform = Mat3.fromRows(rows)
        val inverse = transform.inverse()
        val newCellMatrix = structure.cell.matrix * transform
        val sites = structure.sites.map { site ->
            val position = (inverse * site.fractional).wrapped()
            site.copy(fractional = position)
        }
        val newStructure = structure.copy(cell = UnitCell.fromMatrix(newCellMatrix), sites = sites, bondRules = emptyList(), disabledBondPairs = emptySet())
        return ensureAutoBondRules(newStructure)
    }

    private fun constrainCell(cell: UnitCell, groupNumber: Int?): UnitCell = when (groupNumber ?: 1) {
        in 3..15 -> cell.copy(alpha = 90.0, gamma = 90.0)
        in 16..74 -> cell.copy(alpha = 90.0, beta = 90.0, gamma = 90.0)
        in 75..142 -> cell.copy(b = cell.a, alpha = 90.0, beta = 90.0, gamma = 90.0)
        in 143..194 -> cell.copy(b = cell.a, alpha = 90.0, beta = 90.0, gamma = 120.0)
        in 195..230 -> cell.copy(b = cell.a, c = cell.a, alpha = 90.0, beta = 90.0, gamma = 90.0)
        else -> cell
    }

    private fun uniqueLabel(base: String, sites: List<AtomSite>): String {
        if (sites.none { it.label == base }) return base
        var suffix = 2
        while (sites.any { it.label == "$base$suffix" }) suffix++
        return "$base$suffix"
    }

    private fun occupancyWarnings(value: Double) = if (value in 0.0..1.0) emptyList() else listOf("Occupancy was clamped to 0–1")
}
