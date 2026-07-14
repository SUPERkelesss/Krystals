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

    @Test fun originAtomIsShownAtAllEightCellCorners() {
        val structure = CrystalStructure(
            "corners", UnitCell.DEFAULT, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(AtomSite("origin", "A1", "C", Vec3.ZERO)),
        )
        val scene = CrystalEngine.buildScene(structure)
        assertEquals(8, scene.atoms.size)
        assertEquals(8, scene.atoms.map { it.fractional }.toSet().size)
    }

    @Test fun catalogContainsAllSpaceGroups() {
        assertEquals(230, SpaceGroupCatalog.all.size)
        assertEquals("Fm-3m", SpaceGroupCatalog.all[224].symbol)
    }

    @Test fun compositionUsesHillOrdering() {
        val structure = CrystalStructure(
            "formula", UnitCell.DEFAULT, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("o", "O1", "O", Vec3.ZERO, 1.0),
                AtomSite("c", "C1", "C", Vec3(0.2, 0.2, 0.2), 1.0),
                AtomSite("h", "H1", "H", Vec3(0.4, 0.4, 0.4), 2.0.coerceIn(0.0, 1.0)),
                AtomSite("ag", "Ag1", "Ag", Vec3(0.6, 0.6, 0.6), 1.0),
            ),
        )
        assertEquals("C 1 H 1 Ag 1 O 1", CrystalEngine.info(structure).composition)
    }

    @Test fun elementColorOverridesRoundTrip() {
        val parsed = CifCodec.newDocument()
        val structure = parsed.structure.copy(
            sites = listOf(AtomSite("c1", "C1", "C", Vec3.ZERO)),
            elementArgbOverrides = mapOf("C" to 0xFFFF0000L),
        )
        val written = CifCodec.write(parsed, structure)
        val reparsed = CifCodec.parseStructure(written)
        assertEquals(0xFFFF0000L, reparsed.structure.elementArgbOverrides["C"])
        assertEquals(PeriodicTable.resolveArgb("C", reparsed.structure.elementArgbOverrides), reparsed.structure.elementArgbOverrides["C"])
    }

    @Test fun externalBondRulesAreRead() {
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
