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
        assertEquals(expectedBvs, bvs.getValue("Cl"), 1e-8)
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
        assertFailsWith<VoronoiSearchLimitExceededException> {
            BondValence.smartIonicRules(structure, BondConfiguration())
        }
        assertTrue(BondValence.bondValenceSums(structure, BondConfiguration()).isEmpty())
    }

    @Test fun preservesBondAndBoundaryImageResults() {
        val rule = BondRule("Cs", "Cl", 0.1, 4.0, extendAcrossCell = true)
        val structure = csCl(BondConfiguration(listOf(rule))).first
        val network = BondDetector.buildNetwork(structure, BondConfiguration(listOf(rule)), Expansion())
        assertTrue(network.bonds.isNotEmpty())
        assertTrue(network.atoms.any { it.isBoundaryImage })
        assertTrue(network.bonds.all { it.distance in 0.1..4.0 })
        assertTrue(CoordinationAnalyzer.coordinationNumber(network, network.atoms.first().id) > 0)
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
        val rule = BondRule(siteId, siteId, 0.1, 1.01, extendAcrossCell = true)

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
}
