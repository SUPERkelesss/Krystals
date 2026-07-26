package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.*
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site

import kotlin.math.abs
import kotlin.math.exp

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
    )

    /**
     * Generate per-site-pair bond rules from estimated Shannon crystal radii. [epsilon] is the bond
     * threshold added to rA+rB (max = rA + rB + epsilon). Anion–anion site pairs (e.g. O–O) are
     * skipped — in an ionic model anions don't bond each other, and their wide radius sum would
     * otherwise flag non-bonding O–O distances as bonds.
     *
     * Returns [SmartIonicResult.success] = false when no cation–anion pair could be analysed at all
     * (e.g. a pure metal or an all-covalent structure); the caller should fall back to BONDING.
     */
    fun smartIonicRules(
        structure: CrystalStructure,
        bondConfiguration: BondConfiguration,
        epsilon: Double = 0.45,
    ): SmartIonicResult {
        val analysis = analyze(structure)
        if (analysis == null || !analysis.anyResolved) return SmartIonicResult(emptyList(), success = false)

        val rules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                // Skip anion–anion pairs (O–O, O–F, …): no ionic bond between two anions.
                if (analysis.siteValence[siteA.id]?.isAnion == true &&
                    analysis.siteValence[siteB.id]?.isAnion == true) return@mapNotNull null
                val rA = analysis.siteValence[siteA.id]?.radius ?: PeriodicTable.radius(siteA.species.symbol, RadiusSource.BONDING)
                val rB = analysis.siteValence[siteB.id]?.radius ?: PeriodicTable.radius(siteB.species.symbol, RadiusSource.BONDING)
                BondRule(siteA.id, siteB.id, 0.1, rA + rB + epsilon, BondRuleSource.CUSTOM)
            }
        }
        return SmartIonicResult(rules, success = true)
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
        if (SymmetryExpander.expand(structure).size > SMART_IONIC_ATOM_LIMIT) return emptyMap()
        val analysis = try {
            analyze(structure)
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
        val bySite = HashMap<String, Double>()
        for ((siteId, siteAtoms) in analysis.atomsBySiteId) {
            val values = siteAtoms.mapNotNull { bvsByAtom[it.id] }
            if (values.isNotEmpty()) bySite[siteId] = values.average()
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

    private fun analyze(structure: CrystalStructure): Analysis? {
        val atoms = SymmetryExpander.expand(structure)
        if (atoms.size < 2) return null
        val atomById = atoms.associateBy { it.id }
        val atomsBySiteId = atoms.groupBy { it.siteId }
        val neighbours = VoronoiNeighbours.find(structure, atoms).filterNot { (a, b, _) ->
            val atomA = atomById[a] ?: return@filterNot false
            val atomB = atomById[b] ?: return@filterNot false
            PeriodicTable.anionValence(atomA.species.symbol) != null &&
                PeriodicTable.anionValence(atomB.species.symbol) != null
        }
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
            val radius = PeriodicTable.shannonIonicRadius(site.species.symbol, fixedAnionV, cn)
            return SiteValence(radius, fixedAnionV, isAnion = true)
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
