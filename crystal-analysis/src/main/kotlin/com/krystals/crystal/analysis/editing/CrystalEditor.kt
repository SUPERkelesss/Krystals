package com.krystals.crystal.analysis.editing

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondRuleSource
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.HbondChecking
import com.krystals.crystal.analysis.bonding.VoronoiNeighbours
import com.krystals.crystal.analysis.bonding.VoronoiSearchLimitExceededException
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.Expansion
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
import com.krystals.crystal.core.symmetry.SymmetryOperation
import com.krystals.crystal.data.BravaisLatticeData
import com.krystals.crystal.data.PeriodicTableData
import kotlin.math.abs

/** Per v0.6.5: elements classified as non-metals. Hoisted to a single shared set so [isMetal] does
 *  not rebuild the set on every call (kept in sync with the identical set in bonding/BondValence.kt). */
private val NON_METALS: Set<String> = setOf(
    "H", "He", "B", "C", "N", "O", "F", "Ne",
    "Si", "P", "S", "Cl", "Ar",
    "Ge", "As", "Se", "Br", "Kr",
    "Sb", "Te", "I", "Xe",
    "At", "Rn", "Po",
)

/** Per v0.6.5: classify an element as metal (true) or non-metal (false). */
private fun isMetal(symbol: String): Boolean = symbol !in NON_METALS

/** Atomic-number index lookup for [PeriodicTableData.symbols], built once instead of an O(118)
 *  `indexOf` scan per comparison. */
private val symbolIndex: Map<String, Int> =
    PeriodicTableData.symbols.withIndex().associate { it.value to it.index }

/** Shared rule-sort comparator: metal sites first, then larger atomic number first within a type. */
private fun bondRuleComparator(siteSpecies: Map<String, String>): Comparator<BondRule> = compareBy(
    { !isMetal(siteSpecies[it.siteA] ?: "") },
    { !isMetal(siteSpecies[it.siteB] ?: "") },
    { -(symbolIndex[siteSpecies[it.siteA] ?: ""] ?: -1) },
    { -(symbolIndex[siteSpecies[it.siteB] ?: ""] ?: -1) },
)

/** Per v0.6.5: order a site pair so that metal comes first; if both same type, larger atomic number first. */
private fun orderedSites(siteA: Site, siteB: Site): Pair<Site, Site> {
    val aMetal = isMetal(siteA.species.symbol)
    val bMetal = isMetal(siteB.species.symbol)
    return when {
        aMetal && !bMetal -> siteA to siteB
        !aMetal && bMetal -> siteB to siteA
        else -> {
            val aNum = symbolIndex[siteA.species.symbol] ?: -1
            val bNum = symbolIndex[siteB.species.symbol] ?: -1
            if (aNum >= bNum) siteA to siteB else siteB to siteA
        }
    }
}

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
    val expansion: Expansion? = null,
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
            // Per v0.7.1: adding atoms does NOT regenerate bond rules — existing rules are preserved.
            EditResult(structure.copy(sites = structure.sites + site), bondConfiguration, occupancyWarnings(command.occupancy))
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
        is EditCommand.DeleteAtom -> {
            // Per v0.7.1: deleting an atom removes rules that reference it, but does NOT
            // regenerate all rules from radii. Other rules are preserved as-is.
            val remainingRules = bondConfiguration.rules.filterNot { it.siteA == command.siteId || it.siteB == command.siteId }
            EditResult(
                structure.copy(sites = structure.sites.filterNot { it.id == command.siteId }),
                bondConfiguration.copy(rules = remainingRules),
            )
        }
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
        val generated = smartOrBondingRules(structure, bondConfiguration, epsilon)
        // Per v0.6.3: replace existing rules with generated ones, but preserve
        // disabledPairs from the input and filter out any rules for disabled pairs.
        val filtered = generated.filter { it.key !in bondConfiguration.disabledPairs }
        return EditResult(structure, bondConfiguration.copy(rules = filtered))
    }

    /** Per v0.8.7: structures composed entirely of non-metal atoms default to bonding rules
     *  regardless of cell size (ignoring SMART_IONIC_ATOM_LIMIT). Metals keep the existing
     *  size-gated smart-ionic / bonding fallback logic. */
    private fun isAllNonMetals(structure: CrystalStructure): Boolean =
        structure.sites.all { !isMetal(it.species.symbol) }

    fun fromSmartIonicAttempt(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double,
        smartIonic: BondValence.SmartIonicResult?,
    ): EditResult {
        val atoms = SymmetryExpander.expand(structure)
        val sizeGuarded = atoms.size > BondValence.SMART_IONIC_ATOM_LIMIT
        // Per v0.8.7: all-non-metal structures skip smartIonic entirely.
        val useBonding = isAllNonMetals(structure) || sizeGuarded
        var timedOut = false
        val generated = if (!useBonding && smartIonic != null && smartIonic.success) {
            smartIonic.rules
        } else {
            if (!useBonding && smartIonic == null) timedOut = true
            bondingRulesWithHbonds(structure, atoms, bondingRules(structure, epsilon))
        }
        val warnings = if (timedOut) listOf(SMART_IONIC_TIMEOUT) else emptyList()
        // Per v0.6.3: replace existing rules with generated ones, but preserve
        // disabledPairs and filter out any rules for disabled pairs.
        val filtered = generated.filter { it.key !in bondConfiguration.disabledPairs }
        return EditResult(structure, bondConfiguration.copy(rules = filtered), warnings)
    }

    private fun smartOrBondingRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double,
    ): List<BondRule> {
        val atoms = SymmetryExpander.expand(structure)
        // Per v0.8.7: all-non-metal structures skip smartIonic, go to bonding rules directly.
        if (!isAllNonMetals(structure) && atoms.size <= BondValence.SMART_IONIC_ATOM_LIMIT) {
            val result = BondValence.smartIonicRules(structure, bondConfiguration, epsilon, atoms)
            if (result.success) return result.rules
        }
        // Per v0.8.6: the bonding-radius fallback path also gets hbond detection.
        val rules = bondingRules(structure, epsilon)
        return bondingRulesWithHbonds(structure, atoms, rules)
    }

    /** Per v0.8.6: append hbond rules to the bonding-radius rule set.
     *  Proton criterion for the bonding path: an expanded H atom whose Voronoi
     *  neighbours within the covalent (bonding-radius) window include exactly
     *  ONE O/N/F/S/P/Cl partner. Site-level rules count every site pair
     *  (even distant ones), so we use Voronoi distances instead. */
    private fun bondingRulesWithHbonds(
        structure: CrystalStructure,
        atoms: List<com.krystals.crystal.core.model.AtomImage>,
        rules: List<BondRule>,
    ): List<BondRule> {
        // Run Voronoi first.
        val neighboursByAtomId = try {
            val neighbours = VoronoiNeighbours.find(structure, atoms)
            val map = linkedMapOf<Long, MutableList<Pair<Long, Double>>>()
            for ((a, b, dist) in neighbours) {
                map.getOrPut(a) { mutableListOf() }.add(b to dist)
                map.getOrPut(b) { mutableListOf() }.add(a to dist)
            }
            map
        } catch (_: VoronoiSearchLimitExceededException) {
            return rules
        }

        // Compute covalent-max thresholds per element pair using bonding radii.
        val covRadius = { sym: String -> PeriodicTable.radius(sym, RadiusSource.BONDING) }
        val hbondAcceptorElements = setOf("O", "N", "F", "S", "P", "Cl")
        val atomById = atoms.associateBy { it.id }

        // Find H atoms with exactly 1 Voronoi neighbour in {O,N,F,S,P,Cl} within covalent distance.
        val protonAtomIds = mutableSetOf<Long>()
        for (atom in atoms) {
            if (atom.species.symbol != "H") continue
            val neighbours = neighboursByAtomId[atom.id] ?: continue
            val covBondedAcceptors = neighbours.mapNotNull { (nId, dist) ->
                val n = atomById[nId] ?: return@mapNotNull null
                if (n.species.symbol !in hbondAcceptorElements) return@mapNotNull null
                val covMax = covRadius("H") + covRadius(n.species.symbol) + 0.45
                if (dist <= covMax) n.species.symbol else null
            }.distinct()
            if (covBondedAcceptors.size == 1) protonAtomIds += atom.id
        }
        val protonSiteIds = protonAtomIds.mapNotNull { atomById[it]?.siteId }.toSet()
        if (protonSiteIds.isEmpty()) return rules

        val hbondRules = HbondChecking.hbondRules(
            structure, atoms, neighboursByAtomId, protonSiteIds, rules,
        )
        return rules + hbondRules
    }

    private fun bondingRules(structure: CrystalStructure, epsilon: Double = 0.45): List<BondRule> {
        val siteSpecies = structure.sites.associate { it.id to it.species.symbol }
        return structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                val (orderedA, orderedB) = orderedSites(siteA, siteB)
                BondRule(
                    orderedA.id,
                    orderedB.id,
                    0.1,
                    PeriodicTable.radius(siteA.species.symbol, RadiusSource.BONDING) +
                        PeriodicTable.radius(siteB.species.symbol, RadiusSource.BONDING) + epsilon,
                    BondRuleSource.CUSTOM,
                )
            }
        }.sortedWith(bondRuleComparator(siteSpecies))
    }

    fun rebuildBondRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        source: RadiusSource,
        epsilon: Double = 0.45,
    ): EditResult {
        // Per v0.8.7: all-non-metal structures skip smartIonic entirely.
        if (source == RadiusSource.SMART_IONIC && !isAllNonMetals(structure)) {
            val result = BondValence.smartIonicRules(structure, bondConfiguration, epsilon)
            if (result.success) {
                val filtered = result.rules.filter { it.key !in bondConfiguration.disabledPairs }
                return EditResult(structure, bondConfiguration.copy(rules = filtered))
            }
            val fallback = bondingRules(structure, epsilon)
            // Per v0.8.6: append hbond rules on the bonding fallback path.
            val atoms = SymmetryExpander.expand(structure)
            val withHbonds = bondingRulesWithHbonds(structure, atoms, fallback)
            val filteredFallback = withHbonds.filter { it.key !in bondConfiguration.disabledPairs }
            return EditResult(structure, bondConfiguration.copy(rules = filteredFallback), listOf(SMART_IONIC_UNAVAILABLE))
        }
        // Non-SMART_IONIC sources OR all-non-metal structures: bonding rules directly.
        if (source == RadiusSource.SMART_IONIC) {
            val atoms = SymmetryExpander.expand(structure)
            val rules = bondingRules(structure, epsilon)
            val withHbonds = bondingRulesWithHbonds(structure, atoms, rules)
            val filtered = withHbonds.filter { it.key !in bondConfiguration.disabledPairs }
            return EditResult(structure, bondConfiguration.copy(rules = filtered))
        }
        val siteSpecies = structure.sites.associate { it.id to it.species.symbol }
        val rules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                val (orderedA, orderedB) = orderedSites(siteA, siteB)
                BondRule(
                    orderedA.id,
                    orderedB.id,
                    0.1,
                    PeriodicTable.radius(siteA.species.symbol, source) +
                        PeriodicTable.radius(siteB.species.symbol, source) + epsilon,
                    BondRuleSource.CUSTOM,
                )
            }
        }.sortedWith(bondRuleComparator(siteSpecies))
        val filteredRules = rules.filter { it.key !in bondConfiguration.disabledPairs }
        return EditResult(structure, bondConfiguration.copy(rules = filteredRules))
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

        // Per v0.6.5: decompose P = R × S where R is a signed permutation (axis rotation)
        // and S = diag(s0, s1, s2) is a diagonal expansion. The rotation is applied to the
        // structure (coordinates, lattice, symmetry operations are conjugated); the expansion
        // is returned via EditResult.expansion so the rendering engine creates the supercell
        // instead of duplicating atoms.
val decomposition = decomposeTransform(rows)
if (decomposition != null) {
val R = decomposition.rotation
val sx = decomposition.sx
val sy = decomposition.sy
val sz = decomposition.sz
// Per v0.7.1: apply the FULL matrix P = R × S to lattice, sites, and symmetry ops.
// The scaling is handled by centering translations in the symmetry operations,
// NOT by the expansion system. This separates 3×3 matrix transforms from "扩展晶胞".
val S = Mat3(
Vec3(sx.toDouble(), 0.0, 0.0),
Vec3(0.0, sy.toDouble(), 0.0),
Vec3(0.0, 0.0, sz.toDouble()),
)
// P = R * S (Mat3 stores by columns, so R*S scales R's columns)
val P = Mat3(R.a * sx.toDouble(), R.b * sy.toDouble(), R.c * sz.toDouble())
val Pinv = P.inverse()
val tVec = Pinv * translation.toVec3()
// Sites: x' = P^(-1) * x + P^(-1) * t  (full transform, including scaling)
val newSites = structure.sites.map { site ->
site.copy(
fractionalCoordinate = FractionalCoordinate.fromVec3(
Pinv * site.fractionalCoordinate.toVec3() + tVec,
).wrapped(),
)
}
// Conjugate symmetry operations by full P: W' = P^(-1) * W * P, w' = P^(-1) * w
// Filter out operations with non-integer rotation entries — they are incompatible
// with the supercell (e.g. a 4-fold rotation when only one axis is scaled).
val conjugatedOps = structure.symmetryOperations.mapNotNull { op ->
val newRot = cleanMatrix(Pinv * op.rotation * P)
fun isInt(v: Double) = kotlin.math.abs(v - kotlin.math.round(v)) < 1e-4
if (!isInt(newRot.a.x) || !isInt(newRot.a.y) || !isInt(newRot.a.z) ||
!isInt(newRot.b.x) || !isInt(newRot.b.y) || !isInt(newRot.b.z) ||
!isInt(newRot.c.x) || !isInt(newRot.c.y) || !isInt(newRot.c.z)) return@mapNotNull null
val intRot = Mat3(
Vec3(kotlin.math.round(newRot.a.x), kotlin.math.round(newRot.a.y), kotlin.math.round(newRot.a.z)),
Vec3(kotlin.math.round(newRot.b.x), kotlin.math.round(newRot.b.y), kotlin.math.round(newRot.b.z)),
Vec3(kotlin.math.round(newRot.c.x), kotlin.math.round(newRot.c.y), kotlin.math.round(newRot.c.z)),
)
val newTrans = FractionalCoordinate.fromVec3(Pinv * op.translation).wrapped().toVec3()
SymmetryOperation(intRot, newTrans, op.source)
}
// Generate ALL centering translations for the supercell (not just single-axis).
// For sx=2, sy=2: (0.5,0,0), (0,0.5,0), (0.5,0.5,0) etc.
val centeringTranslations = mutableListOf<Vec3>()
for (i in 0 until sx) for (j in 0 until sy) for (k in 0 until sz) {
if (i == 0 && j == 0 && k == 0) continue
centeringTranslations += Vec3(i.toDouble() / sx, j.toDouble() / sy, k.toDouble() / sz)
}
// Combine: each conjugated op × each centering translation
val allOps = mutableListOf<SymmetryOperation>()
for (op in conjugatedOps) {
allOps += op
for (t in centeringTranslations) {
allOps += SymmetryOperation(
op.rotation,
FractionalCoordinate.fromVec3(op.translation + t).wrapped().toVec3(),
op.source + "_c",
)
}
}
val distinctOps = allOps.distinctBy { rotationKey(it.rotation) + ":" + translationKey(it.translation) }
val newStructure = structure.copy(
lattice = Lattice.fromMatrix(structure.lattice.matrix * P),
sites = newSites,
symmetryOperations = distinctOps,
)
// Per v0.7.1: no expansion returned — the scaling is handled by symmetry ops.
return EditResult(newStructure, BondConfiguration(), expansion = null)
}

        // Fallback: non-decomposable matrix — use the old atom-duplication approach.
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
        // Per v0.6.2: correct hexagonal ↔ rhombohedral (obverse setting) transformation.
        // Hex → Rhom: a_r=(2a+b+c)/3, b_r=(-a+b+c)/3, c_r=(-a-2b+c)/3  (det=1/3)
        // Rhom → Hex: a_h=a_r-b_r,      b_h=b_r-c_r,      c_h=a_r+b_r+c_r  (det=3)
        val transform = if (toRhombohedral) Mat3(
            Vec3(2.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0),
            Vec3(-1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0),
            Vec3(-1.0 / 3.0, -2.0 / 3.0, 1.0 / 3.0),
        ) else Mat3(
            Vec3(1.0, -1.0, 0.0),
            Vec3(0.0, 1.0, -1.0),
            Vec3(1.0, 1.0, 1.0),
        )
        val inverse = transform.inverse()
        val newCellMatrix = structure.lattice.matrix * transform
        val sites = structure.sites.map { site ->
            site.copy(
                fractionalCoordinate = FractionalCoordinate.fromVec3(
                    (inverse * site.fractionalCoordinate.toVec3()),
                ).wrapped(),
            )
        }
        // Per v0.6.3: set symmetry operations to identity after conversion. The rhombohedral
        // cell's symmetry elements are in different positions than the hexagonal cell's, so
        // the hexagonal symmetry operations would generate atoms at wrong positions. The
        // converted site coordinates are already complete, so no further expansion is needed.
        val newStructure = structure.copy(
            lattice = Lattice.fromMatrix(newCellMatrix),
            sites = sites,
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        )
        return ensureAutoBondRules(newStructure, BondConfiguration())
    }

    // ── Per v0.8.0: Clear symmetry (P1 fallback) ──────────────────────────────

    /**
     * Per v0.8.0: Remove symmetry by expanding all atoms to their full set, then setting
     * the space group to P1 with identity operations. The lattice is preserved unchanged.
     * Bond rules are regenerated so the displayed bonds remain identical.
     */
    fun clearSymmetry(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
    ): EditResult {
        // If already P1 with identity ops, nothing to do.
        val ops = structure.effectiveSymmetryOperations
        if (ops.size <= 1 && structure.spaceGroup.number == 1) {
            return EditResult(structure, bondConfiguration)
        }
        // 1. Expand all atoms using symmetry operations.
        val expanded = SymmetryExpander.expand(structure)
        // Pre-count images per siteId so label disambiguation below is O(1) instead of O(N) per
        // expanded atom (previously a full scan of `expanded` for every atom — O(N²) overall).
        val siteIdCounts = expanded.groupingBy { it.siteId }.eachCount()
        // 2. Create new Site objects from expanded atoms (dedup by position+species).
        val seen = mutableSetOf<Pair<String, Triple<Double, Double, Double>>>()
        val labelCounts = HashMap<String, Int>()
        val newSites = expanded.mapNotNull { atom ->
            val fc = atom.fractionalCoordinate
            val key = atom.species.symbol to Triple(
                Math.round(fc.x * 1e4) / 1e4,
                Math.round(fc.y * 1e4) / 1e4,
                Math.round(fc.z * 1e4) / 1e4,
            )
            if (key in seen) return@mapNotNull null
            seen.add(key)
            val n = labelCounts.getOrDefault(atom.species.symbol, 0) + 1
            labelCounts[atom.species.symbol] = n
            val label = if (siteIdCounts.getOrDefault(atom.siteId, 0) > 1) "${atom.species.symbol}${n}" else atom.siteLabel
            Site(
                id = "${atom.siteId}:C$n",
                label = label,
                species = atom.species,
                fractionalCoordinate = fc,
                occupancy = atom.occupancy,
            )
        }
        // 3. Set space group to P1, keep lattice, set identity ops.
        val p1 = SpaceGroupCatalog.resolve("P1", 1)
        val newStructure = structure.copy(
            spaceGroup = p1,
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = newSites,
        )
        // 4. Regenerate bond rules for the expanded atom set.
        return ensureAutoBondRules(newStructure, BondConfiguration())
    }

    // ── Per v0.8.0: Primitive ↔ Conventional cell conversion ──────────────────

    /**
     * Per v0.8.0: Convert from conventional to primitive cell.
     * Expands atoms via symmetry, applies the conv→prim matrix, deduplicates,
     * transforms symmetry operations to the primitive basis, and finds the ASU.
     * Per v0.6.5: sets isConventional=false.
     */
    fun convertToPrimitive(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
    ): EditResult {
        val centering = BravaisLatticeData.centeringFromSymbol(structure.spaceGroup.symbol)
        if (centering == BravaisLatticeData.CenteringType.PRIMITIVE) {
            return EditResult(structure.copy(isConventional = false), bondConfiguration)
        }
        val matrixRows = BravaisLatticeData.conventionalToPrimitiveMatrix(centering)
            ?: return EditResult(structure.copy(isConventional = false), bondConfiguration)
        val transform = Mat3.fromRowsDouble(matrixRows).transposed()
        val inverse = transform.inverse()
        // 1. Expand atoms using symmetry operations to get the full conventional cell.
        val expanded = SymmetryExpander.expand(structure)
        // 2. Transform coordinates to primitive cell.
        val transformed = expanded.map { atom ->
            FractionalCoordinate.fromVec3(inverse * atom.fractionalCoordinate.toVec3()).wrapped()
        }
        // 3. Deduplicate by position + species.
        val seen = mutableSetOf<Pair<String, Triple<Double, Double, Double>>>()
        val labelCounts = HashMap<String, Int>()
        val newSites = transformed.zip(expanded).mapNotNull { (fc, atom) ->
            val key = atom.species.symbol to Triple(
                Math.round(fc.x * 1e4) / 1e4,
                Math.round(fc.y * 1e4) / 1e4,
                Math.round(fc.z * 1e4) / 1e4,
            )
            if (key in seen) return@mapNotNull null
            seen.add(key)
            val n = labelCounts.getOrDefault(atom.species.symbol, 0) + 1
            labelCounts[atom.species.symbol] = n
            Site(
                id = "${atom.siteId}:P$n",
                label = "${atom.species.symbol}$n",
                species = atom.species,
                fractionalCoordinate = fc,
                occupancy = atom.occupancy,
            )
        }
        // 4. Transform lattice.
        val newCellMatrix = structure.lattice.matrix * transform
        // 5. Transform symmetry operations to the primitive basis.
        //    R' = P^{-1} * R * P,  t' = P^{-1} * t
        //    Centering translations become lattice vectors and wrap to (0,0,0).
        val fullOps = SpaceGroupCatalog.operations(structure.spaceGroup.symbol)
        val transformedOps = fullOps.map { op ->
            val newRot = cleanMatrix(inverse * op.rotation * transform)
            val newTrans = FractionalCoordinate.fromVec3(inverse * op.translation).wrapped().toVec3()
            SymmetryOperation(newRot, newTrans, op.source)
        }.distinctBy { rotationKey(it.rotation) + ":" + translationKey(it.translation) }
        // 6. Find the ASU using the transformed operations.
        val asu = findAsymmetricUnit(newSites, transformedOps).map { site ->
            site.copy(fractionalCoordinate = symmetrizePosition(site.fractionalCoordinate, transformedOps))
        }
        val newStructure = structure.copy(
            lattice = Lattice.fromMatrix(newCellMatrix),
            symmetryOperations = transformedOps,
            sites = asu,
            isConventional = false,
        )
        return ensureAutoBondRules(newStructure, BondConfiguration())
    }

    /**
     * Per v0.8.0: Convert from primitive to conventional cell.
     * Applies the prim→conv matrix, generates centering-related atoms, finds the asymmetric
     * unit, and restores the full symmetry operations for the space group.
     */
    fun convertToConventional(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
    ): EditResult {
        val centering = BravaisLatticeData.centeringFromSymbol(structure.spaceGroup.symbol)
        if (centering == BravaisLatticeData.CenteringType.PRIMITIVE) {
            return EditResult(structure, bondConfiguration)
        }
        // Per v0.8.0: guard against double-conventionalization. If the structure is already
        // conventional (centering ops present or lattice metric matches the crystal system),
        // leave it unchanged rather than applying the prim→conv matrix again.
        if (isConventionalCell(structure)) {
            return EditResult(structure.copy(isConventional = true), bondConfiguration)
        }
        val matrixRows = BravaisLatticeData.primitiveToConventionalMatrix(centering)
            ?: return EditResult(structure, bondConfiguration)
        // Per v0.7.1: fromRowsDouble stores the matrix as-is (no transpose); the lattice
        // update L' = L × M needs M's COLUMNS to be the new lattice vectors (which are the
        // ROWS of the BravaisLatticeData row-form matrices), so the transpose IS required.
        // Without it, conversion is wrong for every centering whose prim→conv matrix is
        // non-symmetric (A, B, C, R); F and I are symmetric and were accidentally unaffected.
        val transform = Mat3.fromRowsDouble(matrixRows).transposed()
        val inverse = transform.inverse()
        // 1. Transform atom coordinates to conventional cell.
        val transformed = structure.sites.map { site ->
            site.copy(
                fractionalCoordinate = FractionalCoordinate.fromVec3(
                    inverse * site.fractionalCoordinate.toVec3(),
                ).wrapped(),
            )
        }
        // 2. Generate centering-related atoms.
        val centeringTranslations = centeringTranslations(centering)
        val allAtoms = mutableListOf<Site>()
        val seen = mutableSetOf<Pair<String, Triple<Double, Double, Double>>>()
        for (site in transformed) {
            for (t in centeringTranslations) {
                val fc = FractionalCoordinate.fromVec3(
                    site.fractionalCoordinate.toVec3() + t,
                ).wrapped()
                val key = site.species.symbol to Triple(
                    Math.round(fc.x * 1e4) / 1e4,
                    Math.round(fc.y * 1e4) / 1e4,
                    Math.round(fc.z * 1e4) / 1e4,
                )
                if (key in seen) continue
                seen.add(key)
                allAtoms += site.copy(fractionalCoordinate = fc)
            }
        }
        // 3. Find the asymmetric unit using the full symmetry operations.
        val fullOps = SpaceGroupCatalog.operations(structure.spaceGroup.symbol)
        val asu = findAsymmetricUnit(allAtoms, fullOps).map { site ->
            site.copy(fractionalCoordinate = symmetrizePosition(site.fractionalCoordinate, fullOps))
        }
        // 4. Transform lattice.
        val newCellMatrix = structure.lattice.matrix * transform
        // 5. Adjust lattice parameters to ideal values for the crystal system.
        val adjustedLattice = adjustLatticeToCrystalSystem(Lattice.fromMatrix(newCellMatrix), structure.spaceGroup)
        val newStructure = structure.copy(
            lattice = adjustedLattice,
            symmetryOperations = fullOps,
            sites = asu,
            isConventional = true,
        )
        return ensureAutoBondRules(newStructure, BondConfiguration())
    }

    // ── Per v0.8.0: Matrix analysis helpers ───────────────────────────────────

    /**
     * Per v0.6.5: Returns true if the matrix is a shear (cannot be decomposed into
     * a signed permutation × diagonal expansion). Such matrices reduce crystal symmetry.
     */
    fun isShearMatrix(rows: List<List<Int>>): Boolean = decomposeTransform(rows) == null

    /** Returns true if the 3×3 integer matrix changes handedness (determinant < 0). */
    fun changesHandedness(rows: List<List<Int>>): Boolean {
        val det = rows[0][0] * (rows[1][1] * rows[2][2] - rows[1][2] * rows[2][1]) -
            rows[0][1] * (rows[1][0] * rows[2][2] - rows[1][2] * rows[2][0]) +
            rows[0][2] * (rows[1][0] * rows[2][1] - rows[1][1] * rows[2][0])
        return det < 0
    }

    /**
     * Per v0.8.0: Apply a shear transformation by first clearing symmetry, then transforming.
     * Used when the user confirms a shear-matrix operation that would reduce crystal symmetry.
     */
    fun transformAfterClearingSymmetry(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        rows: List<List<Int>>,
        translation: FractionalCoordinate,
    ): EditResult {
        val cleared = clearSymmetry(structure, bondConfiguration)
        return transform(cleared.structure, cleared.bondConfiguration, rows, translation)
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

    // ── Per v0.6.5: Transform decomposition ─────────────────────────────────

    /**
     * Per v0.6.5: Decompose an integer matrix P into P = R × S where R is a signed
     * permutation matrix (axis rotation) and S = diag(s0, s1, s2) is a diagonal
     * expansion matrix. Returns null if the decomposition is not possible (e.g. shear).
     *
     * The matrix is given in row form: rows[i][j] is the (i, j) element.
     * P = R × S means: first scale axis j by sj, then permute axes by R.
     */
    fun decomposeTransform(rows: List<List<Int>>): Mat3Decomposition? {
        // For each column j of P, find the single non-zero element.
        // P.colj = R × S.colj = sj × R.colj
        // So R.colj must be a signed unit axis vector, and sj = |P[i][j]|.
        val s = IntArray(3)
        val permRow = IntArray(3)  // permRow[j] = row index of the non-zero element in column j
        val signs = IntArray(3)

        for (j in 0..2) {
            var nonZeroCount = 0
            for (i in 0..2) {
                if (rows[i][j] != 0) {
                    nonZeroCount++
                    permRow[j] = i
                    s[j] = abs(rows[i][j])
                    signs[j] = if (rows[i][j] > 0) 1 else -1
                }
            }
            if (nonZeroCount != 1) return null
        }

        // Check that permRow is a valid permutation (each row used exactly once).
        val used = BooleanArray(3)
        for (j in 0..2) {
            if (used[permRow[j]]) return null
            used[permRow[j]] = true
        }

        // Build R as a signed permutation matrix (stored by columns).
        val cols = Array(3) { j ->
            val sign = signs[j].toDouble()
            when (permRow[j]) {
                0 -> Vec3(sign, 0.0, 0.0)
                1 -> Vec3(0.0, sign, 0.0)
                else -> Vec3(0.0, 0.0, sign)
            }
        }
        val R = Mat3(cols[0], cols[1], cols[2])
        return Mat3Decomposition(R, s[0], s[1], s[2])
    }

    /** Result of transform decomposition: rotation matrix R and diagonal scales (sx, sy, sz). */
    data class Mat3Decomposition(val rotation: Mat3, val sx: Int, val sy: Int, val sz: Int)

    // ── Per v0.8.0: Bravais conversion helpers ────────────────────────────────

    /** Centering translation vectors for each centering type. */
    private fun centeringTranslations(centering: BravaisLatticeData.CenteringType): List<Vec3> = when (centering) {
        BravaisLatticeData.CenteringType.PRIMITIVE -> listOf(Vec3.ZERO)
        BravaisLatticeData.CenteringType.C_CENTERED -> listOf(Vec3.ZERO, Vec3(0.5, 0.5, 0.0))
        BravaisLatticeData.CenteringType.A_CENTERED -> listOf(Vec3.ZERO, Vec3(0.0, 0.5, 0.5))
        BravaisLatticeData.CenteringType.B_CENTERED -> listOf(Vec3.ZERO, Vec3(0.5, 0.0, 0.5))
        BravaisLatticeData.CenteringType.I_CENTERED -> listOf(Vec3.ZERO, Vec3(0.5, 0.5, 0.5))
        BravaisLatticeData.CenteringType.F_CENTERED -> listOf(
            Vec3.ZERO, Vec3(0.5, 0.5, 0.0), Vec3(0.5, 0.0, 0.5), Vec3(0.0, 0.5, 0.5),
        )
        BravaisLatticeData.CenteringType.R_CENTERED -> listOf(
            Vec3.ZERO, Vec3(2.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0), Vec3(1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0),
        )
    }

    /**
     * Find the asymmetric unit from a list of atoms by removing those that can be generated
     * from another atom via a symmetry operation.
     * Per v0.7.1: uses 1e-4 tolerance (≈0.001 Å) so symmetry-mate atoms with DFT-relaxation
     * noise (e.g. Materials Project coordinates, ~1e-6 fractional) still merge into one site.
     * Only same-species atoms are merged.
     */
    private fun findAsymmetricUnit(atoms: List<Site>, operations: List<SymmetryOperation>): List<Site> {
        val asu = mutableListOf<Site>()
        val used = BooleanArray(atoms.size)
        for (i in atoms.indices) {
            if (used[i]) continue
            asu.add(atoms[i])
            for (op in operations) {
                val generated = op.apply(atoms[i].fractionalCoordinate)
                for (j in atoms.indices) {
                    if (!used[j] && j != i &&
                        atoms[j].species.symbol == atoms[i].species.symbol &&
                        generated.almostEquals(atoms[j].fractionalCoordinate, 1e-4)
                    ) {
                        used[j] = true
                    }
                }
            }
            used[i] = true
        }
        return asu
    }

    /**
     * Per v0.7.1: Snap a position exactly onto its symmetry elements by averaging it with
     * its near-coincident images (the site's stabilizer under [operations]). The average is
     * exactly invariant under the stabilizer, so a noisy near-special-position site (e.g.
     * Materials Project coordinates) expands to the correct special-position multiplicity
     * instead of producing near-duplicate atoms. General positions (trivial stabilizer)
     * are returned unchanged.
     */
    private fun symmetrizePosition(
        position: FractionalCoordinate,
        operations: List<SymmetryOperation>,
    ): FractionalCoordinate {
        val base = position.toVec3()
        val images = operations.map { it.apply(position) }
            .filter { it.almostEquals(position, 1e-4) }
        if (images.size <= 1) return position
        val sum = images.fold(Vec3.ZERO) { acc, img -> acc + unwrapNear(img.toVec3(), base) }
        return FractionalCoordinate.fromVec3(sum / images.size.toDouble()).wrapped()
    }

    /** Shift v by an integer lattice translation so it lies as close as possible to ref. */
    private fun unwrapNear(v: Vec3, ref: Vec3): Vec3 = Vec3(
        v.x + kotlin.math.round(ref.x - v.x),
        v.y + kotlin.math.round(ref.y - v.y),
        v.z + kotlin.math.round(ref.z - v.z),
    )

    // ── Per v0.6.5: Conventional cell detection and lattice adjustment ────────

    /**
     * Per v0.6.5: Remove centering translations from the full symmetry operation list,
     * keeping only point-group operations (one per unique rotation matrix).
     */
    fun removeCenteringOperations(
        operations: List<SymmetryOperation>,
        centering: BravaisLatticeData.CenteringType,
    ): List<SymmetryOperation> {
        if (centering == BravaisLatticeData.CenteringType.PRIMITIVE) return operations
        // Group by rotation matrix and keep the one with smallest translation norm per group.
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SymmetryOperation>()
        for (op in operations) {
            val rotKey = rotationKey(op.rotation)
            if (rotKey in seen) continue
            seen.add(rotKey)
            result.add(op)
        }
        return result
    }

    /** Create a hashable key from a rotation matrix (rounded to avoid float issues). */
    private fun rotationKey(m: Mat3): String {
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        return "${r(m.a.x)},${r(m.a.y)},${r(m.a.z)},${r(m.b.x)},${r(m.b.y)},${r(m.b.z)},${r(m.c.x)},${r(m.c.y)},${r(m.c.z)}"
    }

    /** Round matrix elements to clean up numerical noise from basis transformations. */
    private fun cleanMatrix(m: Mat3): Mat3 {
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        return Mat3(
            Vec3(r(m.a.x), r(m.a.y), r(m.a.z)),
            Vec3(r(m.b.x), r(m.b.y), r(m.b.z)),
            Vec3(r(m.c.x), r(m.c.y), r(m.c.z)),
        )
    }

    /** Create a hashable key from a translation vector (rounded + wrapped). */
    private fun translationKey(t: Vec3): String {
        fun r(v: Double) = Math.round(v * 1e6) / 1e6
        return "${r(t.x)},${r(t.y)},${r(t.z)}"
    }

    /**
     * Per v0.6.5: Adjust lattice parameters to ideal values for the crystal system.
     * E.g. cubic: a=b=c, α=β=γ=90; hexagonal/trigonal: a=b, α=β=90, γ=120.
     */
    fun adjustLatticeToCrystalSystem(lattice: Lattice, spaceGroup: com.krystals.crystal.core.symmetry.SpaceGroup): Lattice {
        val cs = spaceGroup.crystalSystem ?: return lattice
        return when (cs) {
            "Cubic" -> Lattice(
                lattice.a, lattice.a, lattice.a,
                90.0, 90.0, 90.0,
            )
            "Hexagonal", "Trigonal" -> Lattice(
                lattice.a, lattice.a, lattice.c,
                90.0, 90.0, 120.0,
            )
            "Tetragonal" -> Lattice(
                lattice.a, lattice.a, lattice.c,
                90.0, 90.0, 90.0,
            )
            "Orthorhombic" -> Lattice(
                lattice.a, lattice.b, lattice.c,
                90.0, 90.0, 90.0,
            )
            "Monoclinic" -> Lattice(
                lattice.a, lattice.b, lattice.c,
                90.0, lattice.beta, 90.0,
            )
            else -> lattice // Triclinic: no constraints
        }
    }

    /**
     * Per v0.6.5: Determine if a CIF-parsed structure is a conventional cell.
     * Checks if the symmetry operations include centering translations (e.g. 1/2,1/2,1/2 for I).
     * If centering translations are present, the cell is conventional.
     *
     * Per v0.7.1: metric fallback — if the lattice already satisfies the crystal system's
     * conventional constraints (e.g. cubic 90°, hexagonal 120°), the cell is conventional
     * even without centering operations. This prevents double-converting conventional cells
     * from Materials Project downloads (identity ops + all atoms listed). Primitive cells
     * (e.g. 109.47° for I, 60° for F, ~55° for R) fail the metric check and are converted.
     */
    fun isConventionalCell(structure: CrystalStructure): Boolean {
        val centering = BravaisLatticeData.centeringFromSymbol(structure.spaceGroup.symbol)
        if (centering == BravaisLatticeData.CenteringType.PRIMITIVE) return true

        // Check if the symmetry operations include centering translations.
        // A centering translation is an operation with identity rotation and a non-zero translation.
        val centeringTrans = centeringTranslations(centering).filter { it != Vec3.ZERO }
        val ops = structure.symmetryOperations

        if (ops.isNotEmpty() && centeringTrans.all { t ->
                ops.any { op ->
                    op.rotation == Mat3.IDENTITY &&
                        kotlin.math.abs(op.translation.x - t.x) < 1e-4 &&
                        kotlin.math.abs(op.translation.y - t.y) < 1e-4 &&
                        kotlin.math.abs(op.translation.z - t.z) < 1e-4
                }
            }
        ) return true

        // Per v0.7.1: metric fallback (see doc comment).
        return latticeMatchesCrystalSystem(structure.lattice, structure.spaceGroup.number)
    }

    /**
     * Per v0.7.1: Returns true if the lattice already satisfies the conventional metric
     * constraints of its crystal system, by comparing against constrainLattice output.
     * Lengths use 1e-3 relative tolerance, angles 1e-2 degrees absolute tolerance.
     */
    private fun latticeMatchesCrystalSystem(lattice: Lattice, groupNumber: Int?): Boolean {
        val c = constrainLattice(lattice, groupNumber)
        fun lengthEq(a: Double, b: Double) = abs(a - b) <= 1e-3 * maxOf(a, b)
        fun angleEq(a: Double, b: Double) = abs(a - b) <= 1e-2
        return lengthEq(c.a, lattice.a) && lengthEq(c.b, lattice.b) && lengthEq(c.c, lattice.c) &&
            angleEq(c.alpha, lattice.alpha) && angleEq(c.beta, lattice.beta) && angleEq(c.gamma, lattice.gamma)
    }
}
