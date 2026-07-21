package com.krystals.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
        val atoms = CrystalEngine.expandAsymmetricUnit(parsed.structure)
        assertEquals(2, atoms.size)
        assertEquals(1.0 / 3.0, parsed.structure.sites.single().fractional.x, 1e-8)
    }

    @Test
    fun preservesUnknownCommentsWhenWriting() {
        val parsed = CifCodec.parseStructure(simple)
        val written = CifCodec.write(parsed, parsed.structure.copy(cell = parsed.structure.cell.copy(a = 5.0)))
        assertTrue("# preserved comment" in written)
        assertEquals(5.0, CifCodec.parseStructure(written).structure.cell.a)
    }

    @Test
    fun elementColorOverridesRoundTrip() {
        val parsed = CifCodec.newDocument()
        val structure = parsed.structure.copy(
            sites = listOf(AtomSite("c1", "C1", "C", Vec3.ZERO)),
            elementArgbOverrides = mapOf("C" to 0xFFFF0000L),
        )
        val written = CifCodec.write(parsed, structure)
        val reparsed = CifCodec.parseStructure(written)
        assertEquals(0xFFFF0000L, reparsed.structure.elementArgbOverrides["C"])
        assertEquals(
            PeriodicTable.resolveArgb("C", reparsed.structure.elementArgbOverrides),
            reparsed.structure.elementArgbOverrides["C"],
        )
    }

    @Test
    fun externalBondRulesAreRead() {
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
        val parsed = CifCodec.parseStructure(source)
        assertEquals(1, parsed.structure.bondRules.size)
        assertEquals(1.2, parsed.structure.bondRules.single().maxAngstrom, 1e-8)
    }
}
