package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.RadiusSource
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per v0.8.x: the H-bond angle threshold is parameterized end-to-end (rule layer +
 * detector layer) so the UI's angle slider (default 110°) controls detection AND
 * display consistently. These tests pin the rule-layer gating in
 * [HbondChecking.hbondRules] and the smart-ionic → bonding fallback in
 * [CrystalEditor.rebuildHbondRules].
 *
 * Fixture chemistry: P–H···P. Phosphorus is a hydrogen-bond acceptor (P ∈
 * {O, N, F, Cl, S, P}), and its Pauling electronegativity (2.19) is BELOW
 * hydrogen's (2.20), so in a P–H structure no site resolves as a cation/anion
 * pair — smart-ionic analysis genuinely fails (success=false) while the bonding
 * hbond path still works.
 */
class HbondAngleThresholdParamTest {

    /** Build a simple P1 structure. Cell = 10x10x10 AA so fractional → Cartesian = ×10. */
    private fun simpleStructure(sites: List<Site>) = CrystalStructure(
        blockName = "test",
        lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = sites,
    )

    /**
     * P1–H1 covalent (~1.0 AA) plus a second P (H-bond acceptor) whose contact angle
     * P2–H1–P1 ≈ 100°. H1···P2 = 2.0 AA sits beyond the covalent window
     * (bonding radii H + P + 0.45 ≈ 1.82 AA) but inside 0.95×vdW
     * (vdW H 1.20 + P 1.80 = 3.00 → 2.85), so it is a genuine H-bond candidate —
     * IF the angle threshold accepts 100°.
     *
     * Geometry: P1@(0.5, 0.5, 0.3), H1@(0.5, 0.5, 0.4), P2@(0.697, 0.5, 0.4347).
     *   toY = H1→P1 = (0, 0, −1.0), |toY| = 1.0
     *   toX = H1→P2 = (1.97, 0, 0.347), |toX| = 2.0
     *   cos θ = toX·toY / (|toX||toY|) = −0.347/2.0 = −0.1736 → θ ≈ 100.0°
     */
    private fun angle100Structure() = simpleStructure(
        listOf(
            Site("P1", "P1", Species("P"), FractionalCoordinate(0.5, 0.5, 0.3)),
            Site("H1", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),
            Site("P2", "P2", Species("P"), FractionalCoordinate(0.697, 0.5, 0.4347)),
        ),
    )

    @Test
    fun angleThresholdControlsRuleGeneration() {
        val structure = angle100Structure()
        val atoms = SymmetryExpander.expand(structure)
        val bySite = atoms.associateBy { it.siteId }
        val h = bySite.getValue("H1")
        val p1 = bySite.getValue("P1")
        val p2 = bySite.getValue("P2")
        // H1's neighbour table: covalent P1 at 1.0 AA, acceptor P2 at 2.0 AA.
        val neighboursByAtomId = mapOf(h.id to listOf(p1.id to 1.0, p2.id to 2.0))
        val normalRules = listOf(BondRule("P1", "H1", 0.1, 1.82, BondRuleSource.CUSTOM))

        val at95 = HbondChecking.hbondRules(
            structure, atoms, neighboursByAtomId, setOf("H1"), normalRules, angleThreshold = 95.0,
        )
        assertTrue(at95.any { setOf(it.siteA, it.siteB) == setOf("H1", "P2") },
            "100° contact must generate an H-bond rule at threshold 95°, got $at95")

        val at110 = HbondChecking.hbondRules(
            structure, atoms, neighboursByAtomId, setOf("H1"), normalRules, angleThreshold = 110.0,
        )
        assertTrue(at110.isEmpty(),
            "100° contact must be filtered at threshold 110°, got $at110")
    }

    @Test
    fun rebuildHbondRulesSmartIonicFailsFallsBackToBonding() {
        // Linear P–H···P layout (180° angle, passes any realistic threshold): P1–H1 covalent
        // at 1.0 AA, H1···P2 at 2.0 AA. Smart-ionic cannot resolve any site (P's fixed anion
        // valence is never reached because H is MORE electronegative than P, and H's
        // anion-partner test fails for the same reason), so the analysis returns
        // success=false — rebuildHbondRules must fall back to the bonding hbond path and
        // still produce the H1···P2 rule instead of returning the configuration unchanged.
        val structure = simpleStructure(
            listOf(
                Site("P1", "P1", Species("P"), FractionalCoordinate(0.5, 0.5, 0.3)),
                Site("H1", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.4)),
                Site("P2", "P2", Species("P"), FractionalCoordinate(0.5, 0.5, 0.6)),
            ),
        )

        // Sanity: smart-ionic genuinely fails on this structure — the fallback precondition.
        val si = BondValence.smartIonicRules(structure, BondConfiguration())
        assertFalse(si.success, "P–H structure must fail smart-ionic analysis")

        val config = BondConfiguration(
            rules = listOf(BondRule("P1", "H1", 0.1, 1.82, BondRuleSource.CUSTOM)),
        )
        val result = CrystalEditor.rebuildHbondRules(structure, config, RadiusSource.SMART_IONIC, angleThreshold = 110.0)
        val rules = result.bondConfiguration.rules
        assertTrue(rules.any { !it.isHBond && setOf(it.siteA, it.siteB) == setOf("P1", "H1") },
            "normal rules must survive the fallback, got $rules")
        assertTrue(rules.any { it.isHBond && setOf(it.siteA, it.siteB) == setOf("H1", "P2") },
            "failed smart-ionic must fall back to the bonding hbond path and produce H1···P2, got $rules")
    }
}
