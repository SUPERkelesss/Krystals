package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleCifTest {
    @Test
    fun allBundledSamplesParseExpandAndRoundTrip() {
        val directory = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("Sample CIF directory not found")
        // Walk recursively — the corpus is organized into category subdirectories.
        val files = directory.walkTopDown().filter { it.isFile && it.extension.equals("cif", true) }.toList().sortedBy { it.name }
        assertTrue(files.size >= 30, "Expected bundled CIF corpus, found ${files.size}")
        files.forEach { file ->
            val source = file.readText()
            val document = CifCodec.parse(source)
            val candidates = CifCodec.structuralBlockIndices(document)
            assertTrue(candidates.isNotEmpty(), "No structure block in ${file.name}")
            candidates.forEach { block ->
                val parsed = CifCodec.parseStructure(source, block)
                val atoms = SymmetryExpander.expand(parsed.structure)
                assertTrue(atoms.isNotEmpty(), "No atoms in ${file.name}:${parsed.structure.blockName}")
                val written = CifCodec.write(
                    parsed,
                    parsed.structure,
                    parsed.bondConfiguration,
                    parsed.displayMetadata,
                )
                val reparsed = CifCodec.parseStructure(written, block)
                assertEquals(parsed.structure.sites.size, reparsed.structure.sites.size, file.name)
            }
        }
    }

    @Test
    fun customBondRulesAreNotReadFromCif() {
        val parsed = CifCodec.newDocument().let { base ->
            base.copy(structure = base.structure.copy(sites = listOf(
                Site("c1", "C1", Species("C"), FractionalCoordinate.ZERO),
            )))
        }
        val site = parsed.structure.sites.single()
        val configuration = BondConfiguration(listOf(BondRule(site.id, site.id, 0.8, 1.8)))
        val text = CifCodec.write(parsed, parsed.structure, configuration, parsed.displayMetadata)
        // Per v0.7.1: bond rules are written to CIF for external tools but intentionally
        // not read back, to prevent stale or incorrect rules from being loaded.
        assertEquals(0, CifCodec.parseStructure(text).bondConfiguration.rules.size)
    }
}
