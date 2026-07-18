package com.krystals.core

import kotlin.math.abs
import kotlin.math.exp

/**
 * Per v0.5.0: "smart ionic" (智能离子) bond-rule generation.
 *
 * For each atom site the routine estimates an oxidation state via a bond-valence sum (BVS), reads
 * the coordination number (CN) from a bonding-radius neighbour count, then looks up a Shannon
 * crystal radius for (element, valence, CN) and builds a bond rule from the per-site radii. Any
 * site that can't be resolved (no bvparm pair, no Shannon entry) falls back to the bonding radius,
 * and a structure that can't be analysed at all signals failure so the caller can fall back to the
 * plain bonding-radius rule set.
 *
 * Data: R0/B parameters from the IUCr BVPARM2020 table, Shannon crystal radii from
 * R. D. Shannon (1976); see [PeriodicTable.bondValenceParam] / [PeriodicTable.shannonCrystalRadius].
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
    fun smartIonicRules(structure: CrystalStructure, epsilon: Double = 0.45): SmartIonicResult {
        val analysis = analyze(structure, epsilon)
        if (analysis == null || !analysis.anyResolved) return SmartIonicResult(emptyList(), success = false)

        val rules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).mapNotNull { siteB ->
                // Skip anion–anion pairs (O–O, O–F, …): no ionic bond between two anions.
                if (analysis.siteValence[siteA.id]?.isAnion == true &&
                    analysis.siteValence[siteB.id]?.isAnion == true) return@mapNotNull null
                val rA = analysis.siteValence[siteA.id]?.radius ?: PeriodicTable.radius(siteA.element, RadiusSource.BONDING)
                val rB = analysis.siteValence[siteB.id]?.radius ?: PeriodicTable.radius(siteB.element, RadiusSource.BONDING)
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
    fun bondValenceSums(structure: CrystalStructure, epsilon: Double = 0.45): Map<String, Double> {
        val analysis = analyze(structure, epsilon) ?: return emptyMap()
        val siteValence = analysis.siteValence
        val atomById = analysis.atoms.associateBy { it.id }
        // Per expanded-atom BVS.
        val bvsByAtom = HashMap<Long, Double>()
        for ((a, b, d) in analysis.neighbours) {
            val atomA = atomById[a] ?: continue
            val atomB = atomById[b] ?: continue
            if (atomA.element == atomB.element) continue // same-element pairs carry no ionic valence info
            val svA = siteValence[atomA.siteId] ?: continue
            val svB = siteValence[atomB.siteId] ?: continue
            val vA = svA.valence ?: continue
            val vB = svB.valence ?: continue
            // Look up R0/B for the (cation, anion) pair in either order.
            val param = if (!svA.isAnion && svB.isAnion) {
                PeriodicTable.bondValenceParam(atomA.element, vA, atomB.element, vB)
            } else if (svA.isAnion && !svB.isAnion) {
                PeriodicTable.bondValenceParam(atomB.element, vB, atomA.element, vA)
            } else continue
            val s = param?.let { exp((it.r0 - d) / it.b) } ?: continue
            bvsByAtom[a] = (bvsByAtom[a] ?: 0.0) + s
            bvsByAtom[b] = (bvsByAtom[b] ?: 0.0) + s
        }
        // Collapse per-atom BVS to per-site: average over the site's expanded atoms (they are
        // symmetry-equivalent and share the same environment, so this just picks a representative
        // value while tolerating any edge-case variation).
        val bySite = HashMap<String, MutableList<Double>>()
        for (atom in analysis.atoms) {
            val bvs = bvsByAtom[atom.id] ?: continue
            bySite.getOrPut(atom.siteId) { mutableListOf() }.add(bvs)
        }
        return bySite.mapValues { (_, list) -> list.average() }
    }

    /** Shared analysis: expand, build the (anion–anion-skipping) neighbour table, resolve each site. */
    private data class Analysis(
        val atoms: List<ExpandedAtom>,
        val neighbours: List<Triple<Long, Long, Double>>,
        val siteValence: Map<String, SiteValence>,
        val anyResolved: Boolean,
    )

    private fun analyze(structure: CrystalStructure, epsilon: Double): Analysis? {
        val atoms = CrystalEngine.expandAsymmetricUnit(structure)
        if (atoms.size < 2) return null
        val neighbours = bondingNeighbours(structure, atoms, epsilon)
        if (neighbours.isEmpty()) return null

        val cnByAtom = HashMap<Long, Int>()
        for ((a, b, _) in neighbours) {
            cnByAtom[a] = (cnByAtom[a] ?: 0) + 1
            cnByAtom[b] = (cnByAtom[b] ?: 0) + 1
        }

        val siteValence = HashMap<String, SiteValence>()
        var anyResolved = false
        for (site in structure.sites) {
            val sv = resolveSite(site, atoms, neighbours, cnByAtom)
            siteValence[site.id] = sv
            if (sv.radius != null || sv.valence != null) anyResolved = true
        }
        return Analysis(atoms, neighbours, siteValence, anyResolved)
    }

    /** Bonding-radius neighbour pairs (atomA id, atomB id, real distance) using the minimum-image
     *  convention, matching [CrystalEngine.inferBonds]. Anion–anion pairs are skipped so anion CN
     *  counts only cation neighbours. */
    private fun bondingNeighbours(structure: CrystalStructure, atoms: List<ExpandedAtom>, epsilon: Double): List<Triple<Long, Long, Double>> {
        if (atoms.size < 2) return emptyList()
        val tempRules = structure.sites.flatMapIndexed { i, a ->
            structure.sites.drop(i + 1).mapNotNull { b ->
                // Skip anion–anion temp rules so O–O etc. don't inflate anion coordination numbers.
                if (PeriodicTable.anionValence(a.element) != null && PeriodicTable.anionValence(b.element) != null) return@mapNotNull null
                BondRule(a.id, b.id, 0.1,
                    PeriodicTable.radius(a.element, RadiusSource.BONDING) + PeriodicTable.radius(b.element, RadiusSource.BONDING) + epsilon,
                    BondRuleSource.AUTO)
            }
        }
        val bonds = CrystalEngine.inferBonds(atoms, tempRules, emptySet(), structure.cell)
        return bonds.map { Triple(it.atomA, it.atomB, it.distance) }
    }

    /**
     * Resolve a site's Shannon radius, valence, and anion/cation role. Anion sites (elements with a
     * fixed anion valence — O, S, F, Cl, …) take that valence and look up the radius directly;
     * cation sites estimate their valence by minimising |BVS(V) − V| against the most-electronegative
     * anion neighbour. Returns a [SiteValence] with nulls when the site can't be analysed.
     */
    private fun resolveSite(
        site: AtomSite,
        atoms: List<ExpandedAtom>,
        neighbours: List<Triple<Long, Long, Double>>,
        cnByAtom: Map<Long, Int>,
    ): SiteValence {
        val siteAtoms = atoms.filter { it.siteId == site.id }
        if (siteAtoms.isEmpty()) return SiteValence(null, null, false)
        val cn = siteAtoms.mapNotNull { cnByAtom[it.id] }.ifEmpty { return SiteValence(null, null, false) }.average().toInt().coerceAtLeast(1)

        val fixedAnionV = PeriodicTable.anionValence(site.element)
        if (fixedAnionV != null) {
            val radius = PeriodicTable.shannonCrystalRadius(site.element, fixedAnionV, cn)
            return SiteValence(radius, fixedAnionV, isAnion = true)
        }

        // BVS is a per-atom property. A site's expanded atoms are symmetry-equivalent, so use one
        // representative's bonds (the first expanded atom's) rather than summing across all images —
        // summing would multiply the BVS by the site multiplicity and overshoot the real valence.
        val representative = siteAtoms.first()
        val distByElement = HashMap<String, MutableList<Double>>()
        val atomById = atoms.associateBy { it.id }
        for ((a, b, d) in neighbours) {
            val atomA = atomById[a] ?: continue
            val atomB = atomById[b] ?: continue
            if (atomA.id == representative.id && atomB.element != site.element) {
                distByElement.getOrPut(atomB.element) { mutableListOf() }.add(d)
            } else if (atomB.id == representative.id && atomA.element != site.element) {
                distByElement.getOrPut(atomA.element) { mutableListOf() }.add(d)
            }
        }
        if (distByElement.isEmpty()) return SiteValence(null, null, false)

        val anionElement = distByElement.keys
            .filter { PeriodicTable.anionValence(it) != null && PeriodicTable.isAnion(it, site.element) }
            .maxByOrNull { PeriodicTable.electronegativityPublic(it) }
            ?: return SiteValence(null, null, false)
        val anionV = PeriodicTable.anionValence(anionElement) ?: return SiteValence(null, null, false)
        val distances = distByElement.getValue(anionElement)

        val candidates = PeriodicTable.cationValences(site.element)
            .filter { v -> PeriodicTable.bondValenceParam(site.element, v, anionElement, anionV) != null }
        if (candidates.isEmpty()) return SiteValence(null, null, false)

        var bestV = candidates.first()
        var bestErr = Double.POSITIVE_INFINITY
        for (v in candidates) {
            val param = PeriodicTable.bondValenceParam(site.element, v, anionElement, anionV)!!
            val bvs = distances.sumOf { d -> exp((param.r0 - d) / param.b) }
            val err = abs(bvs - v)
            if (err < bestErr) { bestErr = err; bestV = v }
        }
        val radius = PeriodicTable.shannonCrystalRadius(site.element, bestV, cn)
        return SiteValence(radius, bestV, isAnion = false)
    }
}
