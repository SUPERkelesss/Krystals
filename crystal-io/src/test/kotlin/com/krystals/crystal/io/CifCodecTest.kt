package com.krystals.crystal.io

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
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
        val atoms = SymmetryExpander.expand(parsed.structure)
        assertEquals(2, atoms.size)
        assertEquals(1.0 / 3.0, parsed.structure.sites.single().fractionalCoordinate.x, 1e-8)
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
        assertEquals(1, parsed.bondConfiguration.rules.size)
        assertEquals(1.2, parsed.bondConfiguration.rules.single().maxAngstrom, 1e-8)
    }

    @Test fun preservesUnknownSpaceGroupSymbol() {
        val source = simple.replace("'P -1'", "'Unknown group'")
        assertEquals("Unknown group", CifCodec.parseStructure(source).structure.spaceGroup.symbol)
    }
}
