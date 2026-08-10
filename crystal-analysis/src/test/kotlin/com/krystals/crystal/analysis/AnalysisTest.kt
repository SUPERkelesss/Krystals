package com.krystals.crystal.analysis

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.bonding.BondValence
import com.krystals.crystal.analysis.bonding.VoronoiNeighbours
import com.krystals.crystal.analysis.bonding.VoronoiSearchLimitExceededException
import com.krystals.crystal.analysis.coordination.CoordinationAnalyzer
import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.editing.EditCommand
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.analysis.polyhedron.PolyhedronHull
import com.krystals.crystal.analysis.structure.StructureAnalyzer
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisTest {
    private fun csCl(configuration: BondConfiguration = BondConfiguration()): Pair<CrystalStructure, BondConfiguration> {
        val structure = CrystalStructure(
            blockName = "cscl",
            lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate.ZERO),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        return structure to configuration
    }

    @Test fun expandsStronglyTypedAtomImages() {
        val structure = csCl().first
        val atoms = SymmetryExpander.expand(structure)
        assertEquals(2, atoms.size)
        assertEquals(Species("Cs"), atoms.first().species)
        assertEquals(FractionalCoordinate.ZERO, atoms.first().fractionalCoordinate)
        assertEquals(0.0, atoms.first().cartesianCoordinate.x, 1e-10)
    }

    @Test fun periodicVoronoiRetainsCoordinationMultiplicity() {
        val structure = csCl().first
        val atoms = SymmetryExpander.expand(structure)
        val neighbours = VoronoiNeighbours.find(structure, atoms)

        val csClNeighbours = neighbours.filter { (a, b, _) ->
            setOf(a, b) == setOf(atoms.first { it.siteId == "Cs" }.id, atoms.first { it.siteId == "Cl" }.id)
        }
        assertEquals(8, csClNeighbours.size)
        assertTrue(csClNeighbours.all { (_, _, distance) ->
            kotlin.math.abs(distance - kotlin.math.sqrt(12.0)) < 1e-8
        })

        val parameter = requireNotNull(PeriodicTable.bondValenceParam("Cs", 1, "Cl", -1))
        val expectedBvs = 8.0 * kotlin.math.exp((parameter.r0 - kotlin.math.sqrt(12.0)) / parameter.b)
        val bvs = BondValence.bondValenceSums(structure, BondConfiguration())
        assertEquals(expectedBvs, bvs.getValue("Cs"), 1e-8)
        // Per v0.6.5: anion BVS is negated for display (Cl is an anion).
        assertEquals(-expectedBvs, bvs.getValue("Cl"), 1e-8)
    }

    @Test fun bvsReusesSymmetryEquivalentSiteAtoms() {
        val structure = CrystalStructure(
            blockName = "inversion-equivalent-sites",
            lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(
                SymmetryOperation.parse("x,y,z"),
                SymmetryOperation.parse("-x,-y,-z"),
            ),
            sites = listOf(
                Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate(0.1, 0.1, 0.1)),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.6, 0.6, 0.6)),
            ),
        )

        val atoms = SymmetryExpander.expand(structure)
        assertEquals(2, atoms.count { it.siteId == "Cs" })
        assertEquals(2, atoms.count { it.siteId == "Cl" })

        val bvs = BondValence.bondValenceSums(structure, BondConfiguration())
        assertTrue(bvs.getValue("Cs").isFinite())
        assertTrue(bvs.getValue("Cl").isFinite())
    }

    @Test fun simpleCubicVoronoiHasSixPeriodicNeighbours() {
        val structure = CrystalStructure(
            blockName = "simple-cubic",
            lattice = Lattice(3.0, 3.0, 3.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site("Na", "Na1", Species("Na"), FractionalCoordinate.ZERO)),
        )
        val neighbours = VoronoiNeighbours.find(structure, SymmetryExpander.expand(structure))

        // Opposite images are represented by one undirected periodic edge, whose two endpoints
        // contribute two neighbours to the coordination count.
        assertEquals(3, neighbours.size)
        assertTrue(neighbours.all { (a, b, distance) -> a == b && kotlin.math.abs(distance - 3.0) < 1e-8 })
    }

    @Test fun skewCellVoronoiFindsBeyondOneCellImage() {
        val structure = CrystalStructure(
            blockName = "skew-cell",
            lattice = Lattice(1.0, 1.9, 10.0, 90.0, 90.0, 5.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site("Na", "Na1", Species("Na"), FractionalCoordinate.ZERO)),
        )
        val neighbours = VoronoiNeighbours.find(structure, SymmetryExpander.expand(structure))

        val shortImage = kotlin.math.sqrt(
            (2.0 - 1.9 * kotlin.math.cos(Math.toRadians(5.0))).let { it * it } +
                (1.9 * kotlin.math.sin(Math.toRadians(5.0))).let { it * it },
        )
        assertTrue(neighbours.any { (_, _, distance) -> kotlin.math.abs(distance - shortImage) < 1e-8 })
    }

    @Test fun nearDegenerateCellStopsVoronoiBeforeCandidateAllocation() {
        val structure = CrystalStructure(
            blockName = "near-degenerate",
            lattice = Lattice(10.0, 10.0, 10.0, 90.0, 90.0, 0.001),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Na", "Na1", Species("Na"), FractionalCoordinate.ZERO),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val atoms = SymmetryExpander.expand(structure)

        assertFailsWith<VoronoiSearchLimitExceededException> {
            VoronoiNeighbours.find(structure, atoms)
        }
        // Per v0.6.5: smartIonicRules now catches VoronoiSearchLimitExceededException internally
        // and returns success=false instead of propagating the exception.
        val smartIonicResult = BondValence.smartIonicRules(structure, BondConfiguration())
        assertFalse(smartIonicResult.success)
        assertTrue(smartIonicResult.rules.isEmpty())
        assertTrue(BondValence.bondValenceSums(structure, BondConfiguration()).isEmpty())
    }

    @Test fun preservesBondAndBoundaryImageResults() {
        val rule = BondRule("Cs", "Cl", 0.1, 4.0, extendAtoB = true, extendBtoA = true)
        val structure = csCl(BondConfiguration(listOf(rule))).first
        val network = BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule)), Expansion())
        assertTrue(network.bonds.isNotEmpty())
        assertTrue(network.atoms.any { it.isBoundaryImage })
        assertTrue(network.bonds.all { it.distance in 0.1..4.0 })
        assertTrue(CoordinationAnalyzer.coordinationNumber(network, network.atoms.first().id) > 0)
    }

    @Test fun emptyRulesDisableCovalentFallbackWhenConfigured() {
        // 2026-08-09 回归:关闭"自动计算键规则"后打开晶胞必须完全不显示键。
        // 默认(allowAutoFallback=true):空规则仍按元素共价半径 AUTO fallback 成键
        // (v0.7.0 行为,corundum Al-Al 等依赖它)。
        val structure = csCl().first
        assertTrue(BondDetector.buildNetwork(structure, BondConfiguration()).bonds.isNotEmpty())
        // allowAutoFallback=false:空规则 = 无任何键(无 AUTO 兜底)。
        assertTrue(BondDetector.buildNetwork(structure, BondConfiguration(allowAutoFallback = false)).bonds.isEmpty())
        // 显式规则不受影响:即使关闭 fallback,规则窗口内的键照常生成。
        val rule = BondRule("Cs", "Cl", 0.1, 4.0)
        assertTrue(BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule), allowAutoFallback = false)).bonds.isNotEmpty())
    }

    @Test fun autoBondRulesSkipDistantPairsInLargeP1Cell() {
        val structure = CrystalStructure(
            blockName = "large-p1-framework",
            lattice = Lattice(100.0, 100.0, 110.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = (0 until 1020).map { index ->
                val x = index % 10
                val y = (index / 10) % 10
                val z = index / 100
                Site("C$index", "C$index", Species("C"), FractionalCoordinate(x / 10.0, y / 10.0, z / 11.0))
            },
        )

        val result = CrystalEditor.ensureAutoBondRules(structure, BondConfiguration(), includeHbonds = false)

        // The old all-pairs implementation produced 1020 * 1021 / 2 inert rules here.
        assertTrue(result.bondConfiguration.rules.isEmpty())
    }

    @Test fun boundaryImagesReuseTheirZeroCellPrimaryIdentity() {
        val siteId = "C"
        val structure = CrystalStructure(
            blockName = "corner-atom",
            lattice = Lattice(1.0, 1.0, 1.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site(siteId, "C1", Species("C"), FractionalCoordinate.ZERO)),
        )
        val rule = BondRule(siteId, siteId, 0.1, 1.01, extendAtoB = true, extendBtoA = true)

        fun verify(expansion: Expansion, boundaryOffset: Int3, boundaryPosition: FractionalCoordinate) {
            val network = BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule)), expansion)
            val matchingAtoms = network.atoms.filter { atom ->
                atom.siteId == siteId && atom.cellOffset == boundaryOffset &&
                    atom.fractionalCoordinate == boundaryPosition
            }
            assertEquals(1, matchingAtoms.size)
            val boundaryAtom = matchingAtoms.single()
            assertTrue(boundaryAtom.isBoundaryImage)
            assertTrue(network.bonds.any { bond ->
                bond.atomA == boundaryAtom.id || bond.atomB == boundaryAtom.id
            })
        }

        verify(Expansion(), Int3(1, 0, 0), FractionalCoordinate(1.0, 0.0, 0.0))
        verify(Expansion(2, 2, 2), Int3(2, 0, 0), FractionalCoordinate(2.0, 0.0, 0.0))
    }

    @Test fun bondConfigurationEditLifecycleIsIndependentFromStructure() {
        val rule = BondRule("Cs", "Cl", 0.1, 4.0)
        val addedDirectly = BondConfiguration().add(rule)
        assertEquals(listOf(rule), addedDirectly.rules)
        assertEquals(BondConfiguration(), addedDirectly.remove(rule).clear())
        val structure = csCl().first
        val added = CrystalEditor.apply(structure, BondConfiguration(listOf(rule)), EditCommand.SetBondRule(rule.copy(maxAngstrom = 3.9)))
        assertEquals(3.9, added.bondConfiguration.rules.single().maxAngstrom)
        assertEquals(structure, added.structure)

        val removed = CrystalEditor.apply(added.structure, added.bondConfiguration, EditCommand.RemoveBondRule(rule.key))
        assertTrue(removed.bondConfiguration.rules.isEmpty())
        assertTrue(rule.key in removed.bondConfiguration.disabledPairs)

        val rebuilt = CrystalEditor.ensureAutoBondRules(removed.structure, removed.bondConfiguration)
        assertTrue(rebuilt.bondConfiguration.rules.isNotEmpty())
        assertTrue(rule.key in rebuilt.bondConfiguration.disabledPairs)
    }

    @Test fun deletingSiteUpdatesOnlyAffectedRules() {
        val rules = listOf(BondRule("Cs", "Cl", 0.1, 4.0), BondRule("Cs", "Cs", 0.1, 5.0))
        val structure = csCl().first
        val result = CrystalEditor.apply(structure, BondConfiguration(rules), EditCommand.DeleteAtom("Cl"))
        assertEquals(listOf("Cs"), result.structure.sites.map { it.id })
        assertEquals(listOf(BondRule("Cs", "Cs", 0.1, 5.0)), result.bondConfiguration.rules)
    }

    @Test fun coordinationPolyhedronAndStructureInfoRemainAvailable() {
        val rule = BondRule("Cs", "Cl", 0.1, 4.0)
        val structure = csCl().first
        val network = BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule)))
        val neighbors = CoordinationAnalyzer.neighbors(network)
        val center = network.atoms.firstOrNull { it.siteId == "Cs" }
        assertNotNull(center)
        val vertices = neighbors[center.id].orEmpty().map { it.cartesianCoordinate.toVec3() }
        if (vertices.size >= 3) assertFalse(PolyhedronHull.faces(center.cartesianCoordinate.toVec3(), vertices).isEmpty())
        val info = StructureAnalyzer.info(structure)
        assertEquals("P1", info.spaceGroup)
        assertEquals(2, info.atomCount)
        assertTrue(info.volume > 0.0)
    }

    @Test fun transformedStructureClearsBondConfiguration() {
        val rule = BondRule("Cs", "Cl", 0.1, 4.0)
        val structure = csCl().first
        val result = CrystalEditor.apply(
            structure,
            BondConfiguration(listOf(rule), setOf(rule.key)),
            EditCommand.Transform(listOf(listOf(1, 0, 0), listOf(0, 1, 0), listOf(0, 0, 1))),
        )
        assertEquals(Int3(0, 0, 0), SymmetryExpander.expand(result.structure).first().cellOffset)
        assertEquals(BondConfiguration(), result.bondConfiguration)
    }

    // ── Per v0.7.1: primitive ↔ conventional round-trip regression tests ──────
    // Guards the matrix-direction convention in convertToConventional: the Bravais
    // transformation matrices are row-form (row i = new lattice vector i in the old
    // basis), so the Mat3 used for L' = L × M must be the transpose. F and I matrices
    // are symmetric and pass either way; A, C, R are not and catch the regression.

    private fun centeredStructure(
        name: String,
        symbol: String,
        number: Int,
        lattice: Lattice,
        sites: List<Site>,
    ): CrystalStructure = CrystalStructure(
        blockName = name,
        lattice = lattice,
        spaceGroup = SpaceGroupCatalog.resolve(symbol, number),
        symmetryOperations = SpaceGroupCatalog.operations(symbol),
        sites = sites,
    )

    private fun assertSameExpandedAtoms(expected: CrystalStructure, actual: CrystalStructure) {
        val expectedAtoms = SymmetryExpander.expand(expected)
        val remaining = SymmetryExpander.expand(actual).toMutableList()
        assertEquals(expectedAtoms.size, remaining.size, "expanded atom count")
        for (atom in expectedAtoms) {
            val match = assertNotNull(
                remaining.firstOrNull {
                    it.species.symbol == atom.species.symbol &&
                        it.fractionalCoordinate.almostEquals(atom.fractionalCoordinate, 1e-4)
                },
                "No match for ${atom.species.symbol} at ${atom.fractionalCoordinate}",
            )
            remaining.remove(match)
        }
    }

    private fun assertPrimitiveRoundTrip(structure: CrystalStructure) {
        val primitive = CrystalEditor.convertToPrimitive(structure, BondConfiguration()).structure
        assertFalse(primitive.isConventional)
        val restored = CrystalEditor.convertToConventional(primitive, BondConfiguration()).structure
        assertTrue(restored.isConventional)
        assertEquals(structure.lattice.a, restored.lattice.a, 1e-6, "a")
        assertEquals(structure.lattice.b, restored.lattice.b, 1e-6, "b")
        assertEquals(structure.lattice.c, restored.lattice.c, 1e-6, "c")
        assertEquals(structure.lattice.alpha, restored.lattice.alpha, 1e-6, "alpha")
        assertEquals(structure.lattice.beta, restored.lattice.beta, 1e-6, "beta")
        assertEquals(structure.lattice.gamma, restored.lattice.gamma, 1e-6, "gamma")
        assertSameExpandedAtoms(structure, restored)
    }

    @Test fun primitiveRoundTripCCentered() = assertPrimitiveRoundTrip(
        centeredStructure(
            "c2", "C2", 5, Lattice(5.0, 6.0, 7.0, 90.0, 100.0, 90.0),
            listOf(Site("Na", "Na1", Species("Na"), FractionalCoordinate(0.2, 0.3, 0.4))),
        ),
    )

    @Test fun primitiveRoundTripACentered() = assertPrimitiveRoundTrip(
        centeredStructure(
            "amm2", "Amm2", 38, Lattice(4.0, 5.0, 6.0, 90.0, 90.0, 90.0),
            listOf(Site("Si", "Si1", Species("Si"), FractionalCoordinate(0.15, 0.25, 0.35))),
        ),
    )

    @Test fun primitiveRoundTripICentered() = assertPrimitiveRoundTrip(
        centeredStructure(
            "w", "Im-3m", 229, Lattice(3.16, 3.16, 3.16, 90.0, 90.0, 90.0),
            listOf(Site("W", "W1", Species("W"), FractionalCoordinate.ZERO)),
        ),
    )

    @Test fun primitiveRoundTripFCentered() = assertPrimitiveRoundTrip(
        centeredStructure(
            "nacl", "Fm-3m", 225, Lattice(5.64, 5.64, 5.64, 90.0, 90.0, 90.0),
            listOf(
                Site("Na", "Na1", Species("Na"), FractionalCoordinate.ZERO),
                Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        ),
    )

    @Test fun primitiveRoundTripRCentered() = assertPrimitiveRoundTrip(
        centeredStructure(
            "r3", "R3", 146, Lattice(5.0, 5.0, 7.0, 90.0, 90.0, 120.0),
            listOf(Site("O", "O1", Species("O"), FractionalCoordinate(0.2, 0.3, 0.4))),
        ),
    )

    // ── Per v0.7.1: conventional-cell metric detection and noise-tolerant ASU ──

    @Test fun conventionalCellWithIdentityOpsIsNotConverted() {
        // Fe scenario from MP downloads: a conventional I-centered cell (all atoms listed,
        // identity ops only, cubic metric) must NOT be treated as primitive — converting
        // it again produces a wrong doubled cell (e.g. atom at 1/4,1/4,1/4).
        val structure = CrystalStructure(
            blockName = "fe",
            lattice = Lattice(2.87, 2.87, 2.87, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("Im-3m", 229),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Fe1", "Fe1", Species("Fe"), FractionalCoordinate.ZERO),
                Site("Fe2", "Fe2", Species("Fe"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        assertTrue(CrystalEditor.isConventionalCell(structure))
    }

    @Test fun primitiveCellWithIdentityOpsIsConverted() {
        // Primitive bcc cell: rhombohedral metric (109.47°) fails the cubic check.
        val primitive = CrystalStructure(
            blockName = "fe-p",
            lattice = Lattice(2.486, 2.486, 2.486, 109.471, 109.471, 109.471),
            spaceGroup = SpaceGroupCatalog.resolve("Im-3m", 229),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site("Fe1", "Fe1", Species("Fe"), FractionalCoordinate.ZERO)),
        )
        assertFalse(CrystalEditor.isConventionalCell(primitive))
        // R-centered hexagonal cell (γ=120°) with identity ops IS conventional.
        val hexR = CrystalStructure(
            blockName = "r3-hex",
            lattice = Lattice(5.0, 5.0, 7.0, 90.0, 90.0, 120.0),
            spaceGroup = SpaceGroupCatalog.resolve("R3", 146),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(Site("O", "O1", Species("O"), FractionalCoordinate(0.2, 0.3, 0.4))),
        )
        assertTrue(CrystalEditor.isConventionalCell(hexR))
    }

    @Test fun noisyPrimitiveAtomsMergeInAsu() {
        // MP-style noisy coordinates: symmetry-mate atoms differing by ~2e-6 must still
        // merge into one ASU site during primitive→conventional conversion (Cr2O3 scenario).
        val conventional = centeredStructure(
            "r3", "R3", 146, Lattice(5.0, 5.0, 7.0, 90.0, 90.0, 120.0),
            listOf(Site("O", "O1", Species("O"), FractionalCoordinate(0.2, 0.3, 0.4))),
        )
        val primitive = CrystalEditor.convertToPrimitive(conventional, BondConfiguration()).structure
        val baseSite = primitive.sites.single()
        val base = baseSite.fractionalCoordinate
        // The 3-fold axis is [111] in the rhombohedral primitive basis → cyclic permutation.
        val noisyMate1 = FractionalCoordinate(base.z + 2e-6, base.x - 2e-6, base.y + 1e-6).wrapped()
        val noisyMate2 = FractionalCoordinate(base.y - 1e-6, base.z + 2e-6, base.x - 2e-6).wrapped()
        val noisyPrimitive = primitive.copy(
            sites = listOf(
                baseSite,
                Site("O:m2", "O2", Species("O"), noisyMate1),
                Site("O:m3", "O3", Species("O"), noisyMate2),
            ),
        )
        val restored = CrystalEditor.convertToConventional(noisyPrimitive, BondConfiguration()).structure
        assertEquals(1, restored.sites.size, "noisy symmetry mates should merge into one ASU site")
        assertEquals(5.0, restored.lattice.a, 1e-6, "a")
        assertEquals(7.0, restored.lattice.c, 1e-6, "c")
        assertSameExpandedAtoms(conventional, restored)
    }

    @Test fun noisySpecialPositionCollapsesMultiplicity() {
        // Cr2O3 scenario from MP downloads: sites on special positions (Cr 12c, O 18e)
        // carrying DFT-relaxation noise must still expand to the correct multiplicity
        // (12 + 18 = 30), not to general-position near-duplicate clusters (36 + 36 = 72).
        val conventional = centeredStructure(
            "cr2o3", "R-3c", 167, Lattice(4.96, 4.96, 13.59, 90.0, 90.0, 120.0),
            listOf(
                Site("Cr", "Cr1", Species("Cr"), FractionalCoordinate(0.0, 0.0, 0.1477)),
                Site("O", "O1", Species("O"), FractionalCoordinate(0.3054, 0.0, 0.25)),
            ),
        )
        assertEquals(30, SymmetryExpander.expand(conventional).size, "sanity: ideal cell")
        val primitive = CrystalEditor.convertToPrimitive(conventional, BondConfiguration()).structure
        // Perturb each primitive site asymmetrically (simulating DFT relaxation noise).
        val noisy = primitive.copy(
            sites = primitive.sites.mapIndexed { i, site ->
                site.copy(
                    fractionalCoordinate = FractionalCoordinate(
                        site.fractionalCoordinate.x + 3e-6 * (i + 1),
                        site.fractionalCoordinate.y - 2e-6 * (i + 1),
                        site.fractionalCoordinate.z + 4e-6 * (i + 1),
                    ).wrapped(),
                )
            },
        )
        val restored = CrystalEditor.convertToConventional(noisy, BondConfiguration()).structure
        assertEquals(2, restored.sites.size, "ASU site count")
        assertEquals(30, SymmetryExpander.expand(restored).size, "expanded atom count")
        assertSameExpandedAtoms(conventional, restored)
    }

    // ── Per v0.7.0: Hbond rule coexistence ────────────────────────────────────

    @Test fun hbondRuleKeyDiffersFromNormalKey() {
        val normal = BondRule("H1", "O1", 0.1, 1.2)
        val hbond = BondRule("H1", "O1", 1.0, 2.5, isHBond = true)
        assertTrue(normal.key != hbond.key, "hbond rule must have a distinct key")
        assertTrue(hbond.key.endsWith("hbond"), "hbond key should carry the discriminator")
    }

    @Test fun normalAndHbondRulesCoexistInConfiguration() {
        val normal = BondRule("H1", "O1", 0.1, 1.2)
        val hbond = BondRule("H1", "O1", 1.0, 2.5, isHBond = true)
        val config = BondConfiguration().add(normal).add(hbond)
        assertEquals(2, config.rules.size, "both rules must coexist")
        assertTrue(config.rules.any { it.isHBond })
        assertTrue(config.rules.any { !it.isHBond })
    }

    @Test fun hbondRuleReplacesExistingHbondRuleForSamePair() {
        val first = BondRule("H1", "O1", 1.0, 2.0, isHBond = true)
        val second = BondRule("H1", "O1", 1.5, 2.5, isHBond = true)
        val config = BondConfiguration().add(first).add(second)
        assertEquals(1, config.rules.size)
        assertEquals(2.5, config.rules.single().maxAngstrom)
    }

    @Test fun bondDetectorFindsBondViaHbondRule() {
        // Layered HF chain: H at (0,0,0), F at (0,0,1.5) — no covalent window covers
        // 1.5 Å between H and F of different molecules, but an hbond rule (1.4–2.5 Å) does.
        val structure = CrystalStructure(
            blockName = "hf-chain",
            lattice = Lattice(5.0, 5.0, 5.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("H", "H1", Species("H"), FractionalCoordinate(0.5, 0.5, 0.2)),
                Site("F", "F1", Species("F"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val hbond = BondRule("H", "F", 1.4, 2.5, isHBond = true)
        val network = BondDetector.buildNetwork(structure, BondConfiguration(listOf(hbond)))
        // Per hbond-model: hbond bonds live in the separate hbonds channel.
        assertTrue(network.hbonds.isNotEmpty(), "hbond rule should produce hbonds")
        assertTrue(network.bonds.isEmpty(), "hbonds must be separated from normal bonds")
    }

    @Test fun uniqueLabelAutoNumbersSameElementSites() {
        // Per user spec: new-atom labels follow the X, X2, X3... scheme.
        val sites = listOf(
            Site("a", "Ga", Species("Ga"), FractionalCoordinate.ZERO),
            Site("b", "Ga", Species("Ga"), FractionalCoordinate(0.5, 0.5, 0.5)),
            Site("c", "Ga2", Species("Ga"), FractionalCoordinate(0.25, 0.25, 0.25)),
            Site("d", "O1", Species("O"), FractionalCoordinate(0.1, 0.1, 0.1)),
        )
        assertEquals("Ga", CrystalEditor.uniqueLabel("Ga", emptyList()), "first site of an element keeps the bare symbol")
        assertEquals("Ga3", CrystalEditor.uniqueLabel("Ga", sites), "occupied labels step up through the numeric suffix")
        assertEquals("Si", CrystalEditor.uniqueLabel("Si", sites), "unused element stays bare")
        assertEquals("O", CrystalEditor.uniqueLabel("O", sites), "a CIF-style O1 does not occupy the bare O label")
        assertEquals("O12", CrystalEditor.uniqueLabel("O1", sites), "the numeric suffix is appended to the taken base")
    }

    @Test fun transformTranslationIsNotPremultiplied() {
        // Per v0.7.0 (issue #6): the transform dialog formula is R' = XR + T — the translation
        // is added DIRECTLY in the new coordinate system, matching the fallback path. The
        // decomposable path used to premultiply it by P⁻¹, so a 2×2×1 expansion + T=(0.5,0,0)
        // moved atoms by only (0.25,0,0).
        val structure = csCl().first // Cs at (0,0,0), Cl at (0.5,0.5,0.5)
        val result = CrystalEditor.apply(
            structure,
            BondConfiguration(),
            EditCommand.Transform(
                listOf(listOf(2, 0, 0), listOf(0, 2, 0), listOf(0, 0, 1)),
                FractionalCoordinate(0.5, 0.0, 0.0),
            ),
        )
        val cs = result.structure.sites.first { it.id == "Cs" }
        // x' = P⁻¹x + T with x=0 gives T = (0.5, 0, 0); the buggy Pinv*T gave (0.25, 0, 0).
        assertEquals(0.5, cs.fractionalCoordinate.x, 1e-6, "translation must apply directly (no Pinv premultiply)")
        assertEquals(0.0, cs.fractionalCoordinate.y, 1e-6)
        assertEquals(0.0, cs.fractionalCoordinate.z, 1e-6)
    }
}
