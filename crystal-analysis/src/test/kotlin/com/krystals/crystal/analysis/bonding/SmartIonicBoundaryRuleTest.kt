package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.data.RadiusSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #7 (v0.7.0): pyrite FeS2 renders its cross-boundary S-S (disulfide) bonds correctly,
 * but the bond-rule window shows no S-S rule in the single cell (it appears after cell
 * expansion). Root cause found by the Task-1 diagnostic: `hasMatchingBond` matches the S-S
 * rule fine — the rule is never GENERATED, because `smartIonicRules` blanket-skips anion-anion
 * pairs while `BondDetector` draws the bond through its covalent auto fallback. Fix: generate
 * rules for anion-anion pairs that have a real covalent contact (covalent window), keep the
 * skip for non-bonded anions; harden the same-site matcher to unified min-image pairing.
 */
class SmartIonicBoundaryRuleTest {

    /** Pa-3, a=5.39682380 — matches res/cifs_example/01_basic/pyrite_FeS2.cif exactly. */
    private fun pyrite(): CrystalStructure = CrystalStructure(
        blockName = "pyrite",
        lattice = Lattice(5.39682380, 5.39682380, 5.39682380, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("Pa-3", 205),
        symmetryOperations = SpaceGroupCatalog.operations("Pa-3"),
        sites = listOf(
            Site("Fe0", "Fe", Species("Fe"), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site("S1", "S", Species("S"), FractionalCoordinate(0.11461986, 0.61461986, 0.88538014)),
        ),
    )

    private fun minImage(x: Vec3, y: Vec3, structure: CrystalStructure): Double =
        structure.latticeOffsets.minOf { distance(x, y + it) }

    @Test
    fun pyriteSSRuleGeneratedAndVisibleInSingleCell() {
        val structure = pyrite()
        val cfg = BondConfiguration()
        val smart = BondValence.smartIonicRules(structure, cfg, 0.2, includeHbonds = false)
        assertTrue(smart.success, "pyrite must resolve (Fe cation, S anion)")

        // The S-S disulfide rule must now be generated with the covalent window
        // (S covalent 1.03 + 1.03 + eps 0.2 = 2.26), and the 2.143 Å bond must fall inside.
        val ss = smart.rules.firstOrNull { it.siteA == "S1" && it.siteB == "S1" }
            ?: error("smartIonic must generate the S1-S1 rule (bonded anion-anion pair)")
        assertTrue(ss.maxAngstrom >= 2.143, "S-S window ${ss.minAngstrom}..${ss.maxAngstrom} must cover the 2.143 Å bond")
        assertTrue(ss.maxAngstrom < 3.0, "S-S window must be the covalent window, not the wide Shannon one")

        // The rule-list filter (the user-visible window) must show it in the single cell.
        assertTrue(BondRuleMatching.hasMatchingBond(ss, structure, cfg), "S-S rule must match in the single cell")
        // The renderer-side truth stays unchanged: the bond is detected regardless of rules.
        val net = BondDetector.buildNetwork(structure, cfg)
        assertTrue(net.bonds.any { it.rule.siteA == "S1" && it.rule.siteB == "S1" && it.distance in 2.0..2.3 },
            "BondDetector must still find the 2.143 Å S-S bond")
    }

    @Test
    fun rocksaltChloridePairStaysSkipped() {
        // NaCl (Fm-3m, a=5.64): nearest Cl-Cl contact is the full cell axis (5.64 Å) — far
        // beyond the covalent O/Cl window — so the non-bonded anion-anion skip must be kept.
        val structure = CrystalStructure(
            blockName = "nacl",
            lattice = Lattice(5.64, 5.64, 5.64, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("Fm-3m", 225),
            symmetryOperations = SpaceGroupCatalog.operations("Fm-3m"),
            sites = listOf(
                Site("Na0", "Na", Species("Na"), FractionalCoordinate(0.0, 0.0, 0.0)),
                Site("Cl1", "Cl", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val smart = BondValence.smartIonicRules(structure, BondConfiguration(), 0.45, includeHbonds = false)
        assertTrue(smart.success, "NaCl must resolve")
        assertTrue(smart.rules.any { setOf(it.siteA, it.siteB) == setOf("Na0", "Cl1") }, "Na-Cl rule must exist")
        assertTrue(smart.rules.none { it.siteA == "Cl1" && it.siteB == "Cl1" },
            "non-bonded Cl-Cl pair must stay skipped (no covalent contact)")
    }

    @Test
    fun peroxideOxygenPairGetsCovalentRule() {
        // BaO2-like P1 cell (a=6): Ba at origin, O2 dumbbell along x with O-O = 1.4 Å — a real
        // peroxide bond the renderer draws via covalent fallback; smartIonic must emit it.
        val structure = CrystalStructure(
            blockName = "bao2like",
            lattice = Lattice(6.0, 6.0, 6.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(com.krystals.crystal.core.symmetry.SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Ba0", "Ba", Species("Ba"), FractionalCoordinate(0.0, 0.0, 0.0)),
                Site("O1", "O", Species("O"), FractionalCoordinate(0.35, 0.0, 0.0)),
                Site("O2", "O", Species("O"), FractionalCoordinate(0.583, 0.0, 0.0)),
            ),
        )
        val smart = BondValence.smartIonicRules(structure, BondConfiguration(), 0.2, includeHbonds = false)
        assertTrue(smart.success, "Ba-O must resolve")
        val oo = smart.rules.firstOrNull { setOf(it.siteA, it.siteB) == setOf("O1", "O2") }
            ?: error("bonded O-O pair must generate a rule")
        assertTrue(oo.maxAngstrom >= 1.4, "O-O window ${oo.minAngstrom}..${oo.maxAngstrom} must cover the 1.4 Å bond")
        assertTrue(BondRuleMatching.hasMatchingBond(oo, structure, BondConfiguration()),
            "peroxide O-O rule must match (min-image 1.4 Å)")
    }

    /** Task-1 evidence dump: expansion set, min-image pairs, renderer truth, rule sources. */
    @Test
    fun diagnostic() {
        val structure = pyrite()
        val cfg = BondConfiguration()

        println("== real-CIF parse path (app's actual input) ==")
        val cifText = java.io.File("../res/cifs_example/01_basic/pyrite_FeS2.cif").takeIf { it.isFile }?.readText()
            ?: java.io.File("res/cifs_example/01_basic/pyrite_FeS2.cif").takeIf { it.isFile }?.readText()
        if (cifText != null) {
            // Faithful ASU + op set extraction (crystal-analysis cannot depend on crystal-io).
            val opLines = Regex("""^\s*\d+\s+'([^']+)'""", RegexOption.MULTILINE).findAll(cifText)
                .map { it.groupValues[1] }.toList()
            val cifOps = opLines.map { com.krystals.crystal.core.symmetry.SymmetryOperation.parse(it) }
            val siteMatches = Regex(
                """^\s*([A-Z][a-z]?)\s+(\S+)\s+\d+\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([\d.]+)""",
                RegexOption.MULTILINE,
            ).findAll(cifText).map { m ->
                Site(m.groupValues[2], m.groupValues[1], Species(m.groupValues[1]),
                    FractionalCoordinate(m.groupValues[3].toDouble(), m.groupValues[4].toDouble(), m.groupValues[5].toDouble()))
            }.toList()
            val ps = CrystalStructure(
                blockName = "pyrite-cif",
                lattice = Lattice(5.39682380, 5.39682380, 5.39682380, 90.0, 90.0, 90.0),
                spaceGroup = SpaceGroupCatalog.resolve("Pa-3", 205),
                symmetryOperations = cifOps,
                sites = siteMatches,
            )
            println("cif ops=${cifOps.size} sites=" + siteMatches.map { "${it.id}@${"%.4f,%.4f,%.4f".format(it.fractionalCoordinate.x, it.fractionalCoordinate.y, it.fractionalCoordinate.z)}" }.joinToString(" | "))
            val pExpanded = SymmetryExpander.expand(ps)
            println("cif-parsed expanded S count=${pExpanded.count { it.siteId == "S1" }}")
            val pS = pExpanded.filter { it.siteId == "S1" }
            val pMin = pS.indices.flatMap { i -> (i + 1 until pS.size).map { j -> minImage(pS[i].cartesianCoordinate.toVec3(), pS[j].cartesianCoordinate.toVec3(), ps) } }.sorted()
            println("cif-parsed S min-image first 6: " + pMin.take(6).map { "%.3f".format(it) }.joinToString(" "))
            val ssRuleParsed = BondRule("S1", "S1", 1.5, 2.5, BondRuleSource.AUTO)
            println("cif-parsed hasMatchingBond(S-S): " + BondRuleMatching.hasMatchingBond(ssRuleParsed, ps, BondConfiguration()))
            val parsedNet = BondDetector.buildNetwork(ps, BondConfiguration())
            println("cif-parsed BondDetector S-S count: " + parsedNet.bonds.count { it.rule.siteA == "S1" && it.rule.siteB == "S1" })
        } else {
            println("CIF not found")
        }

        println("== supercell (2x2x2) smartIonic — does the S-S rule appear after cell expansion? ==")
        val base = SymmetryExpander.expand(structure)
        val superSites = base.map { a ->
            Site("${a.siteId}#${a.id}", a.species.symbol, a.species, a.fractionalCoordinate)
        }
        val superCell = CrystalStructure(
            blockName = "pyrite-x8",
            lattice = Lattice(5.39682380, 5.39682380, 5.39682380, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(com.krystals.crystal.core.symmetry.SymmetryOperation.IDENTITY),
            sites = superSites,
        )
        val superSmart = BondValence.smartIonicRules(superCell, BondConfiguration(), 0.2, includeHbonds = false)
        println("supercell sites=${superSites.size} smartIonic success=${superSmart.success}")
        println("supercell rules=" + superSmart.rules.map { "${it.siteA}-${it.siteB}" }.joinToString(" "))

        println("== smartIonic rules (AUTO path) ==")
        val smart = BondValence.smartIonicRules(structure, cfg, 0.2, includeHbonds = false)
        println("success=${smart.success}")
        smart.rules.forEach { println("  ${it.siteA}-${it.siteB} ${it.minAngstrom}..${it.maxAngstrom}") }

        println("== bonding-radius rules (rebuildBondRules, the other visible source) ==")
        val bonding = CrystalEditor.rebuildBondRules(structure, cfg, RadiusSource.BONDING, 0.45, includeHbonds = false)
        bonding.bondConfiguration.rules.forEach { println("  ${it.siteA}-${it.siteB} ${it.minAngstrom}..${it.maxAngstrom}") }
        val ssFromBonding = bonding.bondConfiguration.rules.firstOrNull { it.siteA == "S1" && it.siteB == "S1" }
        println("S-S rule from bonding: " + (ssFromBonding?.let { "${it.minAngstrom}..${it.maxAngstrom}" } ?: "NONE"))
        println("hasMatchingBond(bonding S-S): " + (ssFromBonding?.let { BondRuleMatching.hasMatchingBond(it, structure, cfg) }))

        println("== expanded S atoms (matcher's atom set) ==")
        val expanded = SymmetryExpander.expand(structure)
        val sAtoms = expanded.filter { it.siteId == "S1" }
        println("count=${sAtoms.size}")
        sAtoms.forEach { println("  ${"%.4f,%.4f,%.4f".format(it.fractionalCoordinate.x, it.fractionalCoordinate.y, it.fractionalCoordinate.z)}") }

        println("== min-image distances between distinct expanded S pairs (sorted, first 12) ==")
        val dmin = sAtoms.indices.flatMap { i ->
            (i + 1 until sAtoms.size).map { j ->
                minImage(sAtoms[i].cartesianCoordinate.toVec3(), sAtoms[j].cartesianCoordinate.toVec3(), structure)
            }
        }.sorted()
        println(dmin.take(12).map { "%.3f".format(it) }.joinToString(" "))

        println("== BondDetector S-S bonds (renderer-side truth) ==")
        val net = BondDetector.buildNetwork(structure, cfg)
        val ssBonds = net.bonds.filter { it.rule.siteA == "S1" && it.rule.siteB == "S1" }
        println("count=${ssBonds.size} distances=" + ssBonds.take(6).map { "%.3f".format(it.distance) }.joinToString(" "))

        println("== hasMatchingBond on a hand-made S-S rule window 1.5..2.5 ==")
        val ssRule = BondRule("S1", "S1", 1.5, 2.5, BondRuleSource.AUTO)
        println("direct=" + BondRuleMatching.hasMatchingBond(ssRule, structure, cfg))
        println("from-config=" + BondRuleMatching.hasMatchingBond(ssRule, structure, cfg.add(ssRule)))
    }
}
