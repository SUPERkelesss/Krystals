package com.krystals.crystal.analysis.editing

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per v0.7.0: regression — cell conversion must preserve atomic species AND
 * produce unique site ids.
 *
 * Bug 1 (species collapse): "素晶胞-正当晶胞转化后,晶胞参数和原子位置正确,
 * 但原子种类全变成同一种". spglib's types pass through unchanged (verified on
 * 2.7.0), but the removed spglib-raw-data path was unreliable.
 *
 * Bug 2 (crash): the removed convertWithSpglib built site ids with per-species
 * counters under the prefix "spg:P" — after a NaCl conversion Na1 AND Cl1 both
 * got id "spg:P1", and LazyColumn threw `IllegalArgumentException: Key
 * "spg:P1" was already used` (2026-08-08, main-thread FATAL).
 *
 * v0.7.0 resolution: spglib contributes only the space-group number; the
 * actual conversion runs through the legacy matrix path
 * (convertToConventional / convertToPrimitive), which keeps species and builds
 * ids from the source site id ("${siteId}:C$n" / "${siteId}:P$n" — unique
 * because source ids are unique). These tests lock that behavior in.
 */
class SpglibConvertSpeciesTest {

    @Test
    fun restoreSpaceGroupSymmetryReducesFullCellToAsymmetricUnit() {
        val fullCell = CrystalStructure(
            blockName = "p-minus-one-full",
            lattice = Lattice(5.0, 5.0, 5.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P-1", 2),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("C1", "C1", Species("C"), FractionalCoordinate(0.1, 0.2, 0.3)),
                Site("C2", "C2", Species("C"), FractionalCoordinate(0.9, 0.8, 0.7)),
            ),
        )

        val restored = CrystalEditor.restoreSpaceGroupSymmetry(fullCell)

        assertEquals(1, restored.sites.size)
        assertEquals(2, restored.symmetryOperations.size)
        assertEquals(2, SymmetryExpander.expand(restored).size)
    }

    /** MP-download fallback state: Fm-3m DECLARED but only the 2-site primitive
     *  cell present with identity ops (what buildCif emits when spglib fails).
     *  centering=F so convertToConventional must really transform. */
    private fun naclPrimitive(): CrystalStructure = CrystalStructure(
        blockName = "nacl-prim",
        lattice = Lattice(3.951402922131455, 3.951402172480169, 3.951402,
                          59.99998470065274, 59.99999097646918, 59.99999737523848),
        spaceGroup = SpaceGroupCatalog.resolve("Fm-3m", 225),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(
            Site("Na1", "Na1", Species("Na"), FractionalCoordinate(0.0, 0.0, 0.0)),
            Site("Cl1", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
        ),
    )

    @Test
    fun primitiveToConventionalPreservesSpecies() {
        val result = CrystalEditor.convertToConventional(naclPrimitive(), BondConfiguration())
        val symbols = result.structure.sites.map { it.species.symbol }.toSet()
        assertTrue("Na" in symbols, "converted cell must contain Na, got $symbols")
        assertTrue("Cl" in symbols, "converted cell must contain Cl, got $symbols")
        assertEquals(2, symbols.size, "must contain exactly 2 species, got $symbols")
    }

    @Test
    fun convertedSiteIdsAreUniqueAcrossSpecies() {
        // Regression for `Key "spg:P1" was already used` crash.
        val result = CrystalEditor.convertToConventional(naclPrimitive(), BondConfiguration())
        val ids = result.structure.sites.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "site ids must be unique, got $ids")
        assertTrue(ids.none { it.startsWith("spg:") }, "no legacy spg: ids, got $ids")
    }

    @Test
    fun spaceGroupCorrectionUsesSpglibNumber() {
        // The EditorPanels v0.7.0 flow: correct the declared space group with the
        // spglib-detected number, then run the legacy matrix conversion. Here the
        // cell is declared P1 (mislabelled) and spglib says 225 Fm-3m; the corrected
        // structure must convert via the F-centering matrix and keep both species.
        val declared = CrystalStructure(
            blockName = "nacl-mislabeled",
            lattice = Lattice(3.951402922131455, 3.951402172480169, 3.951402,
                              59.99998470065274, 59.99999097646918, 59.99999737523848),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("Na1", "Na1", Species("Na"), FractionalCoordinate(0.0, 0.0, 0.0)),
                Site("Cl1", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val spglibNumber = 225
        val corrected = SpaceGroupCatalog.all.getOrNull(spglibNumber - 1)
            ?.let { declared.copy(spaceGroup = it) } ?: declared
        val result = CrystalEditor.convertToConventional(corrected, BondConfiguration())
        val symbols = result.structure.sites.map { it.species.symbol }.toSet()
        assertEquals(2, symbols.size, "corrected conversion must keep both species, got $symbols")
        assertEquals(225, result.structure.spaceGroup.number)
        val ids = result.structure.sites.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "site ids must be unique, got $ids")
    }

    /**
     * Per v0.7.0: rebuildFromSpglib is the open-file import path for
     * unrecognised space groups. It must (a) keep both species when spglib's
     * Z values pass through, (b) produce globally unique site ids (the v0.7.0
     * "spg:P1" collision crash), (c) drop symmetry-equivalent duplicates so the
     * result is an asymmetric unit, and (d) apply the refined lattice.
     */
    @Test
    fun rebuildFromSpglibKeepsSpeciesAndUniqueIds() {
        val structure = naclPrimitive()
        val cell = SpglibCellData(
            latticeParams = doubleArrayOf(5.588126, 5.588126, 5.588126, 90.0, 90.0, 90.0),
            positions = doubleArrayOf(
                0.0, 0.0, 0.0, 0.5, 0.5, 0.5, 0.0, 0.5, 0.5, 0.5, 0.0, 0.0,
                0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.5,
            ),
            numbers = intArrayOf(11, 17, 11, 17, 11, 17, 11, 17),
            spaceGroupNumber = 225,
            rotations = IntArray(0),
            translations = DoubleArray(0),
            opCount = 0,
        )
        val rebuilt = CrystalEditor.rebuildFromSpglib(structure, cell)
        val symbols = rebuilt.sites.map { it.species.symbol }.toSet()
        assertTrue("Na" in symbols, "rebuilt must contain Na, got $symbols")
        assertTrue("Cl" in symbols, "rebuilt must contain Cl, got $symbols")
        assertEquals(2, symbols.size, "must contain exactly 2 species, got $symbols")
        val ids = rebuilt.sites.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "site ids must be globally unique, got $ids")
        assertTrue(ids.none { it.startsWith("spg:") }, "no legacy spg: ids, got $ids")
        // Fm-3m ASU is 2 sites (Na 4a + Cl 4b).
        assertEquals(2, rebuilt.sites.size, "ASU must be 2 sites, got ${rebuilt.sites.size}")
        assertEquals(225, rebuilt.spaceGroup.number)
        assertEquals(5.588126, rebuilt.lattice.a, 1e-4, "refined lattice a must be applied")
        assertEquals(true, rebuilt.isConventional)
    }

    /**
     * Per v0.7.0: CIFs with unrecognised space groups often carry placeholder
     * species (COD guest-site "X" atoms, e.g. cod-1544612.cif). These have no
     * periodic-table Z; refineUnknownSpaceGroup maps them to placeholder Z
     * (>= 119) + symbolMap, and rebuildFromSpglib must bring them back.
     */
    @Test
    fun rebuildFromSpglibKeepsPlaceholderSpecies() {
        val structure = naclPrimitive()
        val cell = SpglibCellData(
            latticeParams = doubleArrayOf(5.588126, 5.588126, 5.588126, 90.0, 90.0, 90.0),
            positions = doubleArrayOf(
                0.0, 0.0, 0.0, 0.5, 0.5, 0.5, 0.0, 0.5, 0.5, 0.5, 0.0, 0.0,
                0.5, 0.0, 0.5, 0.0, 0.5, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.5,
            ),
            numbers = intArrayOf(11, 17, 11, 17, 11, 17, 11, 17),
            spaceGroupNumber = 225,
            rotations = IntArray(0),
            translations = DoubleArray(0),
            opCount = 0,
            symbolMap = mapOf(119 to "X"),
        )
        // Inject a placeholder site into the full cell (positions 9..11, Z=119).
        val withX = cell.copy(
            positions = cell.positions + doubleArrayOf(0.25, 0.25, 0.25),
            numbers = cell.numbers + intArrayOf(119),
        )
        val rebuilt = CrystalEditor.rebuildFromSpglib(structure, withX)
        assertTrue("X" in rebuilt.sites.map { it.species.symbol }, "placeholder X must survive, got ${rebuilt.sites.map { it.species.symbol }}")
    }

    @Test
    fun rebuildFromSpglibPreservesPartialOccupancy() {
        val cell = SpglibCellData(
            latticeParams = doubleArrayOf(5.0, 5.0, 5.0, 90.0, 90.0, 90.0),
            positions = doubleArrayOf(0.0, 0.0, 0.0),
            numbers = intArrayOf(1),
            spaceGroupNumber = 1,
            rotations = IntArray(0),
            translations = DoubleArray(0),
            opCount = 0,
            symbolMap = mapOf(1 to "Na"),
            occupancyMap = mapOf(1 to 0.375),
        )

        val rebuilt = CrystalEditor.rebuildFromSpglib(naclPrimitive(), cell)

        assertEquals(1, rebuilt.sites.size)
        assertEquals("Na", rebuilt.sites.single().species.symbol)
        assertEquals(0.375, rebuilt.sites.single().occupancy)
    }

    @Test
    fun rebuildFromSpglibDoesNotMergeDifferentOccupancies() {
        val cell = SpglibCellData(
            latticeParams = doubleArrayOf(5.0, 5.0, 5.0, 90.0, 90.0, 90.0),
            positions = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            numbers = intArrayOf(1, 2),
            spaceGroupNumber = 1,
            rotations = IntArray(0),
            translations = DoubleArray(0),
            opCount = 0,
            symbolMap = mapOf(1 to "Na", 2 to "Na"),
            occupancyMap = mapOf(1 to 0.25, 2 to 0.75),
        )

        val rebuilt = CrystalEditor.rebuildFromSpglib(naclPrimitive(), cell)

        assertEquals(listOf(0.25, 0.75), rebuilt.sites.map { it.occupancy }.sorted())
    }
}
