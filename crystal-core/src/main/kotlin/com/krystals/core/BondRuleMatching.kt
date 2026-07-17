package com.krystals.core

/**
 * Helpers for deciding whether a [BondRule] actually produces a visible bond in a given structure.
 *
 * Per v0.2.3: bond-rule lists (editor + display) hide rules that match no atom pair within their
 * distance window, so the user isn't shown rules that don't affect the rendered scene.
 */
object BondRuleMatching {

    /**
     * True if [structure] contains expanded atoms for both [BondRule.siteA] and [BondRule.siteB]
     * and at least one pair sits within `[rule.minAngstrom, rule.maxAngstrom]`.
     *
     * Expansion is used (not just the asymmetric unit) because bonds often form across cell
     * boundaries; checking only the ASU would under-report matches.
     */
    fun hasMatchingBond(rule: BondRule, structure: CrystalStructure): Boolean {
        val atoms = CrystalEngine.expandAsymmetricUnit(structure)
        val a = atoms.filter { it.siteId == rule.siteA }
        val b = atoms.filter { it.siteId == rule.siteB }
        if (a.isEmpty() || b.isEmpty()) return false
        // Per v0.3.2: use the minimum-image convention (via the cell's lattice vectors) so cross-
        // cell bonds are detected — matching how inferBonds now computes distances.
        val la = structure.cell.matrix.a
        val lb = structure.cell.matrix.b
        val lc = structure.cell.matrix.c
        val offsets = ArrayList<Vec3>(27)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            offsets += la * dx.toDouble() + lb * dy.toDouble() + lc * dz.toDouble()
        }
        fun minImage(x: Vec3, y: Vec3): Double {
            var best = Double.POSITIVE_INFINITY
            for (off in offsets) {
                val d = distance(x, y + off)
                if (d < best) best = d
            }
            return best
        }
        // Same-site rule (siteA == siteB): any two distinct atoms of that site.
        if (rule.siteA == rule.siteB) {
            for (i in a.indices) for (j in i + 1 until a.size) {
                val d = minImage(a[i].cartesian, a[j].cartesian)
                if (d > 0.0 && d >= rule.minAngstrom && d <= rule.maxAngstrom) return true
            }
            return false
        }
        for (x in a) for (y in b) {
            val d = minImage(x.cartesian, y.cartesian)
            if (d > 0.0 && d >= rule.minAngstrom && d <= rule.maxAngstrom) return true
        }
        return false
    }
}
