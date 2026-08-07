package com.krystals.crystal.analysis.editing

import com.krystals.crystal.analysis.bonding.BondConfiguration
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
 * Per v0.8.40: regression — cell conversion must preserve atomic species AND
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
 * v0.8.40 resolution: spglib contributes only the space-group number; the
 * actual conversion runs through the legacy matrix path
 * (convertToConventional / convertToPrimitive), which keeps species and builds
 * ids from the source site id ("${siteId}:C$n" / "${siteId}:P$n" — unique
 * because source ids are unique). These tests lock that behavior in.
 */
class SpglibConvertSpeciesTest {

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
        // The EditorPanels v0.8.40 flow: correct the declared space group with the
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
}
