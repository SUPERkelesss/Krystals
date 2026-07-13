package com.krystals.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
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

    @Test fun parsesAndExpandsSymmetry() {
        val parsed = CifCodec.parseStructure(simple)
        val atoms = CrystalEngine.expandAsymmetricUnit(parsed.structure)
        assertEquals(2, atoms.size)
        assertEquals(1.0 / 3.0, parsed.structure.sites.single().fractional.x, 1e-8)
    }

    @Test fun preservesUnknownCommentsWhenWriting() {
        val parsed = CifCodec.parseStructure(simple)
        val written = CifCodec.write(parsed, parsed.structure.copy(cell = parsed.structure.cell.copy(a = 5.0)))
        assertTrue("# preserved comment" in written)
        assertEquals(5.0, CifCodec.parseStructure(written).structure.cell.a)
    }

    @Test fun evaluatesSafeExpressions() {
        assertEquals(0.5, ExpressionParser("1 / (1 + 1)").evaluate(), 1e-10)
    }

    @Test fun computesMeasurements() {
        assertEquals(90.0, angleDegrees(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, Vec3(0.0, 1.0, 0.0)), 1e-8)
    }

    @Test fun catalogContainsAllSpaceGroups() {
        assertEquals(230, SpaceGroupCatalog.all.size)
        assertEquals("Fm-3m", SpaceGroupCatalog.all[224].symbol)
    }
}
