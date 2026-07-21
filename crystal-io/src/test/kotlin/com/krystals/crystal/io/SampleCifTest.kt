package com.krystals.crystal.io

import com.krystals.crystal.analysis.editing.CrystalEditor
import com.krystals.crystal.analysis.editing.EditCommand
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.BondRule
import com.krystals.crystal.core.Vec3
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
                val written = CifCodec.write(parsed, parsed.structure)
                val reparsed = CifCodec.parseStructure(written, block)
                assertEquals(parsed.structure.sites.size, reparsed.structure.sites.size, file.name)
            }
        }
    }

    @Test
    fun customBondRulesSurviveCifWrite() {
        val parsed = CifCodec.newDocument().let { base ->
            val structure = CrystalEditor.apply(base.structure, EditCommand.AddAtom("C", "C1", Vec3.ZERO, 1.0)).structure
            base.copy(structure = structure)
        }
        val site = parsed.structure.sites.single()
        val structure = CrystalEditor.apply(parsed.structure, EditCommand.SetBondRule(BondRule(site.id, site.id, 0.8, 1.8))).structure
        val text = CifCodec.write(parsed, structure)
        assertEquals(1, CifCodec.parseStructure(text).structure.bondRules.size)
    }
}
