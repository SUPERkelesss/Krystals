package com.krystals.app

import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.io.CifCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per v0.7.0: verify the open-file gate for unrecognised space groups.
 * cod-1544612.cif declares sg '?' (no IT number) — after parsing, the space
 * group must be considered UNRECOGNISED so the spglib path runs. Also covers
 * the COD original form (no _krystals_is_conventional flag) and the Krystals
 * round-trip form (flag present).
 */
class UnknownSpaceGroupGateTest {

    /** Krystals round-trip form: explicit flag, sg '?', no IT number. */
    private fun cifWithFlag(): String = """
data_cod-1544612
_space_group_name_H-M_alt   '?'
_cell_length_a   26.786
_cell_length_b   26.786
_cell_length_c   13.3351
_cell_angle_alpha   90
_cell_angle_beta   90
_cell_angle_gamma   90
_krystals_is_conventional   1
loop_
 _atom_site_label
 _atom_site_type_symbol
 _atom_site_fract_x
 _atom_site_fract_y
 _atom_site_fract_z
 _atom_site_occupancy
 Si1 Si 0.19249 0.01001 0.30891 1
 O1 O 0.036 0.1788 0.261 1
""".trimIndent()

    /** COD original form: no Krystals flag, sg '?', no IT number. */
    private fun cifWithoutFlag(): String = """
data_cod-1544612
_space_group_name_H-M_alt   '?'
_cell_length_a   26.786
_cell_length_b   26.786
_cell_length_c   13.3351
_cell_angle_alpha   90
_cell_angle_beta   90
_cell_angle_gamma   90
loop_
 _atom_site_label
 _atom_site_type_symbol
 _atom_site_fract_x
 _atom_site_fract_y
 _atom_site_fract_z
 _atom_site_occupancy
 Si1 Si 0.19249 0.01001 0.30891 1
 O1 O 0.036 0.1788 0.261 1
""".trimIndent()

    private fun isRecognized(structure: com.krystals.crystal.core.model.CrystalStructure): Boolean {
        val number = structure.spaceGroup.number ?: return false
        if (number !in 1..230) return false
        val catalog = SpaceGroupCatalog.all.getOrNull(number - 1) ?: return false
        return catalog.symbol == structure.spaceGroup.symbol || SpaceGroupCatalog.find(structure.spaceGroup.symbol) != null
    }

    @Test
    fun questionMarkSgIsUnrecognizedWithFlag() {
        val parsed = CifCodec.parseStructure(cifWithFlag(), autoConvertConventional = true)
        println("parsed sg='${parsed.structure.spaceGroup.symbol}' number=${parsed.structure.spaceGroup.number} sites=${parsed.structure.sites.size}")
        assertFalse(isRecognized(parsed.structure), "sg '?' must be unrecognised")
        assertTrue(parsed.structure.sites.size >= 2)
    }

    @Test
    fun questionMarkSgIsUnrecognizedWithoutFlag() {
        val parsed = CifCodec.parseStructure(cifWithoutFlag(), autoConvertConventional = true)
        println("parsed sg='${parsed.structure.spaceGroup.symbol}' number=${parsed.structure.spaceGroup.number} sites=${parsed.structure.sites.size}")
        assertFalse(isRecognized(parsed.structure), "sg '?' must be unrecognised")
    }

    @Test
    fun knownSgIsRecognized() {
        val parsed = CifCodec.parseStructure(cifWithFlag().replace("'?'", "'Fm-3m'"), autoConvertConventional = true)
        assertTrue(isRecognized(parsed.structure), "Fm-3m must be recognised")
    }
}
