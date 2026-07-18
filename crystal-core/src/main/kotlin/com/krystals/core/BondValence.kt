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

    /**
     * Generate per-site-pair bond rules from estimated Shannon crystal radii. Returns
     * [SmartIonicResult.success] = false when no cation–anion pair could be analysed at all (e.g. a
     * pure metal or an all-covalent structure); in that case [rules] is empty and the caller should
     * fall back to [RadiusSource.BONDING].
     */
    fun smartIonicRules(structure: CrystalStructure): SmartIonicResult {
        val atoms = CrystalEngine.expandAsymmetricUnit(structure)
        if (atoms.size < 2) return SmartIonicResult(emptyList(), success = false)

        // 1. Build the bonding-radius neighbour table (real Cartesian distances, closest image).
        val neighbours = bondingNeighbours(structure, atoms)
        if (neighbours.isEmpty()) return SmartIonicResult(emptyList(), success = false)

        // 2. Coordination number per expanded atom (count of bonding-radius neighbours).
        val cnByAtom = HashMap<Long, Int>()
        for ((a, b, _) in neighbours) {
            cnByAtom[a] = (cnByAtom[a] ?: 0) + 1
            cnByAtom[b] = (cnByAtom[b] ?: 0) + 1
        }

        // 3. Estimate an oxidation state + Shannon radius per *site* (not per expanded atom):
        //    every expanded atom of a site shares its site's resolution.
        val radiusBySite = HashMap<String, Double?>()
        var anyResolved = false
        for (site in structure.sites) {
            val resolved = resolveSiteRadius(site, atoms, neighbours, cnByAtom)
            radiusBySite[site.id] = resolved
            if (resolved != null) anyResolved = true
        }
        // No site could be resolved → the structure isn't analysable as ionic.
        if (!anyResolved) return SmartIonicResult(emptyList(), success = false)

        // 4. Emit one rule per site pair (including same-site pairs via drop(i), matching
        //    bondingRules), falling back to the bonding radius for unresolved sites.
        val rules = structure.sites.flatMapIndexed { i, siteA ->
            structure.sites.drop(i).map { siteB ->
                val rA = radiusBySite[siteA.id] ?: PeriodicTable.radius(siteA.element, RadiusSource.BONDING)
                val rB = radiusBySite[siteB.id] ?: PeriodicTable.radius(siteB.element, RadiusSource.BONDING)
                BondRule(siteA.id, siteB.id, 0.1, rA + rB + 0.45, BondRuleSource.CUSTOM)
            }
        }
        return SmartIonicResult(rules, success = true)
    }

    /** Bonding-radius neighbour pairs (atomA id, atomB id, real distance) using the minimum-image
     *  convention, matching [CrystalEngine.inferBonds]. */
    private fun bondingNeighbours(structure: CrystalStructure, atoms: List<ExpandedAtom>): List<Triple<Long, Long, Double>> {
        if (atoms.size < 2) return emptyList()
        // One temporary bonding-radius rule per unordered site pair drives inferBonds' window.
        val tempRules = structure.sites.flatMapIndexed { i, a ->
            structure.sites.drop(i + 1).map { b ->
                BondRule(a.id, b.id, 0.1,
                    PeriodicTable.radius(a.element, RadiusSource.BONDING) + PeriodicTable.radius(b.element, RadiusSource.BONDING) + 0.45,
                    BondRuleSource.AUTO)
            }
        }
        val bonds = CrystalEngine.inferBonds(atoms, tempRules, emptySet(), structure.cell)
        return bonds.map { Triple(it.atomA, it.atomB, it.distance) }
    }

    /**
     * Resolve a Shannon crystal radius for [site] at its coordination number. Anion sites (elements
     * with a fixed anion valence — O, S, F, Cl, …) are looked up directly at that valence; cation
     * sites estimate their valence by minimising |BVS(V) − V| against the most-electronegative anion
     * neighbour. Returns null when the site has no analysable bonds or no Shannon entry (the caller
     * then falls back to the bonding radius for that site).
     */
    private fun resolveSiteRadius(
        site: AtomSite,
        atoms: List<ExpandedAtom>,
        neighbours: List<Triple<Long, Long, Double>>,
        cnByAtom: Map<Long, Int>,
    ): Double? {
        // Gather this site's expanded atoms and their bond distances to *other-element* partners.
        val siteAtoms = atoms.filter { it.siteId == site.id }
        if (siteAtoms.isEmpty()) return null

        // Coordination number: average CN across the site's expanded atoms (symmetry-equivalent).
        val cn = siteAtoms.mapNotNull { cnByAtom[it.id] }.ifEmpty { return null }.average().toInt().coerceAtLeast(1)

        // Anion site (O/S/F/Cl/…): look up its Shannon radius at the fixed anion valence directly.
        val fixedAnionV = PeriodicTable.anionValence(site.element)
        if (fixedAnionV != null) {
            return PeriodicTable.shannonCrystalRadius(site.element, fixedAnionV, cn)
        }

        // Bond distances from this site to each neighbouring element.
        val distByElement = HashMap<String, MutableList<Double>>()
        val atomById = atoms.associateBy { it.id }
        for ((a, b, d) in neighbours) {
            val atomA = atomById[a] ?: continue
            val atomB = atomById[b] ?: continue
            if (atomA.siteId == site.id && atomB.element != site.element) {
                distByElement.getOrPut(atomB.element) { mutableListOf() }.add(d)
            } else if (atomB.siteId == site.id && atomA.element != site.element) {
                distByElement.getOrPut(atomA.element) { mutableListOf() }.add(d)
            }
        }
        if (distByElement.isEmpty()) return null

        // The anion partner is the most electronegative neighbouring element that has a fixed
        // anion valence; the site itself must be the cation (less electronegative).
        val anionElement = distByElement.keys
            .filter { PeriodicTable.anionValence(it) != null && PeriodicTable.isAnion(it, site.element) }
            .maxByOrNull { PeriodicTable.electronegativityPublic(it) }
            ?: return null
        val anionV = PeriodicTable.anionValence(anionElement) ?: return null
        val distances = distByElement.getValue(anionElement)

        // Candidate cation valences tabulated for this (cation, anion, anionValence) pair.
        val candidates = PeriodicTable.cationValences(site.element)
            .filter { v -> PeriodicTable.bondValenceParam(site.element, v, anionElement, anionV) != null }
        if (candidates.isEmpty()) return null

        // Pick the valence whose BVS is closest to itself.
        var bestV = candidates.first()
        var bestErr = Double.POSITIVE_INFINITY
        for (v in candidates) {
            val param = PeriodicTable.bondValenceParam(site.element, v, anionElement, anionV)!!
            val bvs = distances.sumOf { d -> exp((param.r0 - d) / param.b) }
            val err = abs(bvs - v)
            if (err < bestErr) { bestErr = err; bestV = v }
        }

        return PeriodicTable.shannonCrystalRadius(site.element, bestV, cn)
    }
}
