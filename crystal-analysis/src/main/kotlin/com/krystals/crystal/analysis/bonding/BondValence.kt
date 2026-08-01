package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.data.PeriodicTableData

import kotlin.math.abs
import kotlin.math.exp

/** Per v0.6.5: elements classified as non-metals. Hoisted to a single shared set so [isMetal] does
 *  not rebuild the set on every call (kept in sync with the identical set in editing/CrystalEditor.kt). */
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

/**
 * Per v0.5.0: "smart ionic" (智能离子) bond-rule generation.
 *
 * For each atom site the routine estimates an oxidation state via a bond-valence sum (BVS), reads
 * the coordination number (CN) from periodic Voronoi-face neighbours, then looks up a Shannon
 * crystal radius for (element, valence, CN) and builds a bond rule from the per-site radii. Any
 * site that can't be resolved (no bvparm pair, no Shannon entry) falls back to the bonding radius,
 * and a structure that can't be analysed at all signals failure so the caller can fall back to the
 * plain bonding-radius rule set.
 *
 * Data: R0/B parameters from the IUCr BVPARM2020 table, Shannon crystal radii from
 * R. D. Shannon (1976); see [PeriodicTable.bondValenceParam] / [PeriodicTable.shannonIonicRadius].
 */
object BondValence {

    /** Above this expanded-atom count the default path skips smart-ionic and uses bonding radii. */
    const val SMART_IONIC_ATOM_LIMIT: Int = 100

    /** Outcome of a smart-ionic analysis. [rules] are the generated rules; [success] is false when
     *  the structure couldn't be analysed (caller should fall back to bonding radii). */
    data class SmartIonicResult(val rules: List<BondRule>, val success: Boolean)

    /** Per-site resolution shared by rule generation and BVS reporting. */
    private data class SiteValence(
        val radius: Double?,      // Shannon crystal radius; null → caller falls back to bonding radius
        val valence: Int?,        // cation: BVS-estimated; anion: fixed; null: unresolved
        val isAnion: Boolean,     // element has a fixed anion valence (O/S/F/Cl/…)
        val isNeutral: Boolean = false, // Per v0.6.5: BVS ≈ 0, use bonding radius
    )

    /**
     * Generate per-site-pair bond rules from estimated Shannon crystal radii. [epsilon] is the bond
     * threshold added to rA+rB (max = rA + rB + epsilon). Anion–anion site pairs (e.g. O–O) are
     * skipped — in an ionic model anions don't bond each other, and their wide radius sum would
     * otherwise flag non-bonding O–O distances as bonds. Per v0.6.5: elements like P, N, As that
     * have a fixed anion valence but are bonded to more electronegative elements are treated as
     * cations (e.g. P in H3PO4 is P5+), so P–O is no longer skipped as anion–anion.
     *
     * Returns [SmartIonicResult.success] = false when no cation–anion pair could be analysed at all
     * (e.g. a pure metal or an all-covalent structure); the caller should fall back to BONDING.
     */
    fun smartIonicRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double = 0.45,
    ): SmartIonicResult = smartIonicRules(structure, bondConfiguration, epsilon, SymmetryExpander.expand(structure))

    /** Internal overload that reuses pre-expanded atoms so callers that expanded for a size guard
     *  (e.g. CrystalEditor.smartOrBondingRules) don't expand the same structure a second time. */
    internal fun smartIonicRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double,
        atoms: List<AtomImage>,
    ): SmartIonicResult {
        val analysis = try {
            analyze(atoms, structure)
        } catch (_: VoronoiSearchLimitExceededException) {
            return SmartIonicResult(emptyList(), success = false)
        }
        if (analysis == null || !analysis.anyResolved) return SmartIonicResult(emptyList(), success = false)

        val rules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                // Skip anion–anion pairs (O–O, O–F, …): no ionic bond between two anions.
                if (analysis.siteValence[siteA.id]?.isAnion == true &&
                    analysis.siteValence[siteB.id]?.isAnion == true) return@mapNotNull null
                val rA = analysis.siteValence[siteA.id]?.radius ?: PeriodicTable.radius(siteA.species.symbol, RadiusSource.BONDING)
                val rB = analysis.siteValence[siteB.id]?.radius ?: PeriodicTable.radius(siteB.species.symbol, RadiusSource.BONDING)
                // Per v0.6.5: order siteA/siteB — metal first, or larger atomic number first if same type.
                val (orderedA, orderedB) = orderedSites(siteA, siteB)
                BondRule(orderedA.id, orderedB.id, 0.1, rA + rB + epsilon, BondRuleSource.CUSTOM)
            }
        }

        // Per v0.8.1: collect H sites whose BVS resolved to valence 1 (protons) and append
        // H-bond rules for proton···acceptor contacts beyond the normal covalent windows.
        val protonSiteIds = analysis.siteValence
            .filter { (_, sv) -> sv.valence == 1 && !sv.isAnion && !sv.isNeutral }
            .keys.filter { siteId -> structure.sites.any { it.id == siteId && it.species.symbol == "H" } }
            .toSet()
        val hbondRules = HbondChecking.hbondRules(
            structure, atoms, analysis.neighboursByAtomId, protonSiteIds, rules,
        )

        return SmartIonicResult(rules + hbondRules, success = true)
    }

    /**
     * Per-site bond-valence sum (BVS). Cation sites use the BVS from their estimated valence; anion
     * sites sum the bond valences of their bonds to neighbouring cations (using each cation's
     * resolved valence to look up R0/B). Sites without a resolvable valence are omitted.
     *
     * BVS is a per-atom property: each expanded atom's BVS is the sum of its own bond valences. A
     * site's symmetry-equivalent atoms share the same environment, so the site BVS is taken from one
     * representative expanded atom (averaged across the site's atoms as a safety net) — NOT summed
     * across all symmetry images, which would multiply the BVS by the site multiplicity.
     */
    fun bondValenceSums(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double = 0.45,
    ): Map<String, Double> {
        // Per v0.5.2b: large cells would make this synchronous BVS computation (called from a
        // remember() in the viewer) stall the UI. Skip it above the smart-ionic atom limit; the
        // atom-info window then simply omits "s = X.XX" for those structures.
        // Expand once and reuse the atoms for both the size guard and the analysis (previously the
        // guard expanded again inside analyze()).
        val atoms = SymmetryExpander.expand(structure)
        if (atoms.size > SMART_IONIC_ATOM_LIMIT) return emptyMap()
        val analysis = try {
            analyze(atoms, structure)
        } catch (_: VoronoiSearchLimitExceededException) {
            return emptyMap()
        } ?: return emptyMap()
        val siteValence = analysis.siteValence
        val atomById = analysis.atomById
        // Per expanded-atom BVS.
        val bvsByAtom = HashMap<Long, Double>()
        for ((a, b, d) in analysis.neighbours) {
            val atomA = atomById[a] ?: continue
            val atomB = atomById[b] ?: continue
            if (atomA.species == atomB.species) continue // same-element pairs carry no ionic valence info
            val svA = siteValence[atomA.siteId] ?: continue
            val svB = siteValence[atomB.siteId] ?: continue
            val vA = svA.valence ?: continue
            val vB = svB.valence ?: continue
            // Look up R0/B for the (cation, anion) pair in either order.
            val param = if (!svA.isAnion && svB.isAnion) {
                PeriodicTable.bondValenceParam(atomA.species.symbol, vA, atomB.species.symbol, vB)
            } else if (svA.isAnion && !svB.isAnion) {
                PeriodicTable.bondValenceParam(atomB.species.symbol, vB, atomA.species.symbol, vA)
            } else continue
            val s = param?.let { exp((it.r0 - d) / it.b) } ?: continue
            bvsByAtom[a] = (bvsByAtom[a] ?: 0.0) + s
            bvsByAtom[b] = (bvsByAtom[b] ?: 0.0) + s
        }
        // Collapse per-atom BVS to per-site: average over the site's expanded atoms (they are
        // symmetry-equivalent and share the same environment, so this just picks a representative
        // value while tolerating any edge-case variation).
        // Per v0.6.5: for anion sites, negate the BVS so the displayed s is negative.
        val bySite = HashMap<String, Double>()
        for ((siteId, siteAtoms) in analysis.atomsBySiteId) {
            val values = siteAtoms.mapNotNull { bvsByAtom[it.id] }
            if (values.isNotEmpty()) {
                val avg = values.average()
                bySite[siteId] = if (siteValence[siteId]?.isAnion == true) -avg else avg
            }
        }
        return bySite
    }

    /** Shared analysis: expand, build the periodic Voronoi neighbour table, and resolve each site. */
    private data class Analysis(
        val atomById: Map<Long, AtomImage>,
        val atomsBySiteId: Map<String, List<AtomImage>>,
        val neighbours: List<Triple<Long, Long, Double>>,
        val neighboursByAtomId: Map<Long, List<Pair<Long, Double>>>,
        val siteValence: Map<String, SiteValence>,
        val anyResolved: Boolean,
    )

    private fun analyze(structure: CrystalStructure): Analysis? =
        analyze(SymmetryExpander.expand(structure), structure)

    /** Analysis over pre-expanded atoms so callers that already expanded (e.g. for a size guard)
     *  don't expand the same structure twice. */
    private fun analyze(atoms: List<AtomImage>, structure: CrystalStructure): Analysis? {
        if (atoms.size < 2) return null
        val atomById = atoms.associateBy { it.id }
        val atomsBySiteId = atoms.groupBy { it.siteId }
        val neighbours = VoronoiNeighbours.find(structure, atoms)
        if (neighbours.isEmpty()) return null

        val neighboursByAtomId = HashMap<Long, MutableList<Pair<Long, Double>>>()
        for ((a, b, distance) in neighbours) {
            neighboursByAtomId.getOrPut(a) { mutableListOf() }.add(b to distance)
            neighboursByAtomId.getOrPut(b) { mutableListOf() }.add(a to distance)
        }

        val cnByAtom = HashMap<Long, Int>()
        for ((a, b, _) in neighbours) {
            val atomA = atomById[a] ?: continue
            val atomB = atomById[b] ?: continue
            // Per v0.5.2b: exclude same-element neighbours from coordination-number counts,
            // consistent with BVS and distance collection which already skip them.
            if (atomA.species == atomB.species) continue
            cnByAtom[a] = (cnByAtom[a] ?: 0) + 1
            cnByAtom[b] = (cnByAtom[b] ?: 0) + 1
        }

        val siteValence = HashMap<String, SiteValence>()
        var anyResolved = false
        for (site in structure.sites) {
            val sv = resolveSite(
                site,
                atomsBySiteId[site.id].orEmpty(),
                atomById,
                neighboursByAtomId,
                cnByAtom,
            )
            siteValence[site.id] = sv
            if (sv.radius != null || sv.valence != null) anyResolved = true
        }
        return Analysis(
            atomById,
            atomsBySiteId,
            neighbours,
            neighboursByAtomId,
            siteValence,
            anyResolved,
        )
    }

    /**
     * Resolve a site's Shannon radius, valence, and anion/cation role. Anion sites (elements with a
     * fixed anion valence — O, S, F, Cl, …) take that valence and look up the radius directly;
     * cation sites estimate their valence by minimising |BVS(V) − V| against the most-electronegative
     * anion neighbour. Returns a [SiteValence] with nulls when the site can't be analysed.
     */
    private fun resolveSite(
        site: Site,
        siteAtoms: List<AtomImage>,
        atomById: Map<Long, AtomImage>,
        neighboursByAtomId: Map<Long, List<Pair<Long, Double>>>,
        cnByAtom: Map<Long, Int>,
    ): SiteValence {
        if (siteAtoms.isEmpty()) return SiteValence(null, null, false)
        val cn = siteAtoms.mapNotNull { cnByAtom[it.id] }.ifEmpty { return SiteValence(null, null, false) }.average().toInt().coerceAtLeast(1)

        val fixedAnionV = PeriodicTable.anionValence(site.species.symbol)
        if (fixedAnionV != null) {
            // Per v0.6.5: elements like P, N, As have a fixed anion valence but can also be cations
            // when bonded to more electronegative elements (e.g., P in H3PO4 is P5+ because O is
            // more electronegative; but in Li3P, P is P3-). Check if any Voronoi neighbour is more
            // electronegative; if so, fall through to the cation analysis path instead.
            val representative = siteAtoms.first()
            val hasMoreElectronegativeNeighbour = neighboursByAtomId[representative.id].orEmpty().any { (neighborId, _) ->
                val neighbor = atomById[neighborId] ?: return@any false
                neighbor.species.symbol != site.species.symbol &&
                    PeriodicTable.electronegativityPublic(neighbor.species.symbol) >
                    PeriodicTable.electronegativityPublic(site.species.symbol)
            }
            if (!hasMoreElectronegativeNeighbour) {
                val radius = PeriodicTable.shannonIonicRadius(site.species.symbol, fixedAnionV, cn)
                return SiteValence(radius, fixedAnionV, isAnion = true)
            }
            // Fall through to cation analysis: treat this site as a cation.
        }

        // BVS is a per-atom property. A site's expanded atoms are symmetry-equivalent, so use one
        // representative's bonds (the first expanded atom's) rather than summing across all images —
        // summing would multiply the BVS by the site multiplicity and overshoot the real valence.
        val representative = siteAtoms.first()
        val distByElement = HashMap<String, MutableList<Double>>()
        for ((neighborId, distance) in neighboursByAtomId[representative.id].orEmpty()) {
            val neighbor = atomById[neighborId] ?: continue
            if (neighbor.species != site.species) {
                distByElement.getOrPut(neighbor.species.symbol) { mutableListOf() }.add(distance)
            }
        }
        if (distByElement.isEmpty()) return SiteValence(null, null, false)

        val anionElement = distByElement.keys
            .filter { PeriodicTable.anionValence(it) != null && PeriodicTable.isAnion(it, site.species.symbol) }
            .maxByOrNull { PeriodicTable.electronegativityPublic(it) }
            ?: return SiteValence(null, null, false)
        val anionV = PeriodicTable.anionValence(anionElement) ?: return SiteValence(null, null, false)
        val distances = distByElement.getValue(anionElement)

        val candidates = PeriodicTable.cationValences(site.species.symbol)
            .filter { v -> PeriodicTable.bondValenceParam(site.species.symbol, v, anionElement, anionV) != null }
        if (candidates.isEmpty()) return SiteValence(null, null, false)

        var bestV = candidates.first()
        var bestErr = Double.POSITIVE_INFINITY
        for (v in candidates) {
            val param = PeriodicTable.bondValenceParam(site.species.symbol, v, anionElement, anionV)!!
            val bvs = distances.sumOf { d -> exp((param.r0 - d) / param.b) }
            val err = abs(bvs - v)
            if (err < bestErr) { bestErr = err; bestV = v }
        }
        val radius = PeriodicTable.shannonIonicRadius(site.species.symbol, bestV, cn)
        return SiteValence(radius, bestV, isAnion = false)
    }
}
