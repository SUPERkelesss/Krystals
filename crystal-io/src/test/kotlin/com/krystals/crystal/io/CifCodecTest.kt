package com.krystals.crystal.io

import com.krystals.crystal.analysis.bonding.BondConfiguration
import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CifCodecTest {
    private val simple = """
        # preserved comment
        data_demo
        _symmetry_space_group_name_H-M 'P -1'
        _cell_length_a 4.0
        _cell_length_b 4.0
        _cell_length_c 4.0
        _cell_angle_alpha 90
        _cell_angle_beta 90
        _cell_angle_gamma 90
        loop_
        _symmetry_equiv_pos_site_id
        _symmetry_equiv_pos_as_xyz
        1 'x,y,z'
        2 '-x,-y,-z'
        loop_
        _atom_site_label
        _atom_site_type_symbol
        _atom_site_fract_x
        _atom_site_fract_y
        _atom_site_fract_z
        _atom_site_occupancy
        C1 C 1/3 0.2 0.4 1
    """.trimIndent()

    @Test
    fun parsesAndExpandsSymmetry() {
        val parsed = CifCodec.parseStructure(simple)
        val atoms = SymmetryExpander.expand(parsed.structure)
        assertEquals(2, atoms.size)
        assertEquals(1.0 / 3.0, parsed.structure.sites.single().fractionalCoordinate.x, 1e-8)
    }

    @Test
    fun rejectsCifWithDegenerateCellGeometry() {
        val invalid = simple
            .replace("_cell_angle_alpha 90", "_cell_angle_alpha 10")
            .replace("_cell_angle_beta 90", "_cell_angle_beta 10")
            .replace("_cell_angle_gamma 90", "_cell_angle_gamma 170")

        val exception = assertFailsWith<IllegalArgumentException> {
            CifCodec.parseStructure(invalid)
        }
        assertTrue(exception.message.orEmpty().contains("non-degenerate volume"))
    }

    @Test
    fun preservesUnknownCommentsWhenWriting() {
        val parsed = CifCodec.parseStructure(simple)
        val written = CifCodec.write(
            parsed,
            parsed.structure.copy(lattice = parsed.structure.lattice.copy(a = 5.0)),
            parsed.bondConfiguration,
            parsed.displayMetadata,
        )
        assertTrue("# preserved comment" in written)
        assertEquals(5.0, CifCodec.parseStructure(written).structure.lattice.a)
    }

    @Test
    fun elementColorOverridesRoundTrip() {
        val parsed = CifCodec.newDocument()
        val structure = parsed.structure.copy(
            sites = listOf(Site("c1", "C1", Species("C"), FractionalCoordinate.ZERO)),
        )
        val metadata = CifDisplayMetadata(mapOf("C" to 0xFFFF0000L))
        val written = CifCodec.write(parsed, structure, parsed.bondConfiguration, metadata)
        val reparsed = CifCodec.parseStructure(written)
        assertEquals(0xFFFF0000L, reparsed.displayMetadata.elementArgbOverrides["C"])
    }

    @Test
    fun externalBondRulesAreIgnored() {
        val source = """
            data_test
            _cell_length_a 4.0
            _cell_length_b 4.0
            _cell_length_c 4.0
            _cell_angle_alpha 90
            _cell_angle_beta 90
            _cell_angle_gamma 90
            loop_
            _atom_site_label
            _atom_site_type_symbol
            _atom_site_fract_x
            _atom_site_fract_y
            _atom_site_fract_z
            C1 C 0 0 0
            O1 O 0.5 0 0
            loop_
            _geom_bond_atom_site_label_1
            _geom_bond_atom_site_label_2
            _geom_bond_distance
            C1 O1 1.2
        """.trimIndent()
        // Per v0.7.1: bond rules from CIF are intentionally ignored to prevent
        // stale or incorrect rules (e.g. Cr-Cr in Cr2O3) from being loaded.
        val parsed = CifCodec.parseStructure(source)
        assertEquals(0, parsed.bondConfiguration.rules.size)
    }

    @Test fun preservesUnknownSpaceGroupSymbol() {
        val source = simple.replace("'P -1'", "'Unknown group'")
        assertEquals("Unknown group", CifCodec.parseStructure(source).structure.spaceGroup.symbol)
    }

    // ── Per v0.7.0: Materials Project returns conventional standard cells. ───────

    @Test fun mpConventionalFeCellWithIdentityOpsIsNotConverted() {
        // Simulates the CIF MaterialsProject.buildCif writes for a conventional bcc Fe cell.
        val mpFe = """
            data_Fe
            _symmetry_space_group_name_H-M   Im-3m
            _cell_length_a   2.86303550
            _cell_length_b   2.86303550
            _cell_length_c   2.86303550
            _cell_angle_alpha   90.00000000
            _cell_angle_beta   90.00000000
            _cell_angle_gamma   90.00000000
            _symmetry_Int_Tables_number   229
            loop_
             _symmetry_equiv_pos_site_id
             _symmetry_equiv_pos_as_xyz
              1  'x, y, z'
            loop_
             _atom_site_label
             _atom_site_type_symbol
             _atom_site_fract_x
             _atom_site_fract_y
             _atom_site_fract_z
             _atom_site_occupancy
             Fe1 Fe 0.00000000 0.00000000 0.00000000 1
        """.trimIndent()
        val parsed = CifCodec.parseStructure(mpFe)
        assertEquals(2.86303550, parsed.structure.lattice.a, 1e-6, "lattice must stay conventional")
        assertEquals(1, parsed.structure.sites.size, "ASU site must not be converted")
        assertTrue(parsed.structure.isConventional)
    }

    @Test fun mpConventionalI2CellWithIdentityOpsIsNotConverted() {
        // Simulates the CIF MaterialsProject.buildCif writes for a conventional Cmce I2 cell.
        val mpI = """
            data_I
            _symmetry_space_group_name_H-M   Cmce
            _cell_length_a   7.67919583
            _cell_length_b   4.62909281
            _cell_length_c   9.79618588
            _cell_angle_alpha   90.00000000
            _cell_angle_beta   90.00000000
            _cell_angle_gamma   90.00000000
            _symmetry_Int_Tables_number   64
            loop_
             _symmetry_equiv_pos_site_id
             _symmetry_equiv_pos_as_xyz
              1  'x, y, z'
            loop_
             _atom_site_label
             _atom_site_type_symbol
             _atom_site_fract_x
             _atom_site_fract_y
             _atom_site_fract_z
             _atom_site_occupancy
             I1 I 0.00000000 0.15908468 0.62002706 1
        """.trimIndent()
        val parsed = CifCodec.parseStructure(mpI)
        assertEquals(7.67919583, parsed.structure.lattice.a, 1e-6, "lattice a must stay conventional")
        assertEquals(4.62909281, parsed.structure.lattice.b, 1e-6, "lattice b must stay conventional")
        assertEquals(1, parsed.structure.sites.size, "ASU site must not be converted")
        assertTrue(parsed.structure.isConventional)
    }

    @Test
    fun hbondRulesAreNotPersistedInCifLoop() {
        val parsed = CifCodec.parseStructure(simple)
        val normal = BondRule("C", "O", 0.1, 1.5)
        val hbond = BondRule("C", "O", 1.0, 2.5, isHBond = true)
        val config = BondConfiguration(listOf(normal, hbond))
        val written = CifCodec.write(parsed, parsed.structure, config, parsed.displayMetadata)
        // The loop header must be present.
        assertTrue("_krystals_bond_rule_" in written, "bond rule loop header must be present")
        // Only one data row: the normal rule. Count non-meta lines after the header.
        val afterHeader = written.substringAfterLast("_krystals_bond_rule_extend_b_to_a")
        val dataRows = afterHeader.trim().lines().filter { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("loop_") && !t.startsWith("data_")
        }
        assertEquals(1, dataRows.size, "only the normal rule should be persisted")
        // The normal rule's distance values must appear in the output.
        assertTrue("1.5" in written, "normal rule maxAngstrom must appear")
    }

    @Test
    fun autoConvertConventionalFalsePreservesNonConventionalCell() {
        // Space group with :R suffix — the parser sets isConventional=false.
        val source = """
            data_test
            _symmetry_space_group_name_H-M 'R 3 :R'
            _cell_length_a 5.0
            _cell_length_b 5.0
            _cell_length_c 5.0
            _cell_angle_alpha 60
            _cell_angle_beta 60
            _cell_angle_gamma 60
            loop_
            _symmetry_equiv_pos_site_id
            _symmetry_equiv_pos_as_xyz
            1 'x,y,z'
            loop_
            _atom_site_label
            _atom_site_type_symbol
            _atom_site_fract_x
            _atom_site_fract_y
            _atom_site_fract_z
            O1 O 0.2 0.3 0.4
        """.trimIndent()
        // Default autoConvertConventional=true: converts to conventional.
        val converted = CifCodec.parseStructure(source, autoConvertConventional = true)
        assertTrue(converted.structure.isConventional, "converted cell must be marked conventional")
        // autoConvertConventional=false: the structure is kept as-is — not converted.
        val primitive = CifCodec.parseStructure(source, autoConvertConventional = false)
        // The :R cell is not conventional, but also not converted, so the lattice should
        // differ from the converted version (rhombohedral vs hexagonal setting).
        assertTrue(!primitive.structure.isConventional || primitive.structure.lattice != converted.structure.lattice,
            "non-converted cell should differ from auto-converted cell")
    }
}
