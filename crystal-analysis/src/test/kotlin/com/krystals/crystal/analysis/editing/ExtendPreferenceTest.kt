package com.krystals.crystal.analysis.editing

import com.krystals.crystal.analysis.bonding.BondRule
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v0.8.35: opening a file applies the user's default cross-cell bond-extension preference to
 * every generated rule. ALL → both directions extend; METALS_ONLY → only the metal side of a
 * metal-nonmetal bond extends (v0.8.43: metal-metal bonds never extend); NEVER → nothing.
 */
class ExtendPreferenceTest {

    private val structure = CrystalStructure(
        blockName = "cscl",
        lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
        spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
        symmetryOperations = listOf(SymmetryOperation.IDENTITY),
        sites = listOf(
            Site("Cs", "Cs1", Species("Cs"), FractionalCoordinate.ZERO),
            Site("Cl", "Cl1", Species("Cl"), FractionalCoordinate(0.5, 0.5, 0.5)),
        ),
    )

    private val rules = listOf(BondRule("Cs", "Cl", 0.1, 4.0))

    @Test
    fun allModeExtendsBothDirections() {
        val out = CrystalEditor.applyExtendPreference(structure, rules, CrystalEditor.ExtendBondDefaultMode.ALL)
        assertTrue(out.single().extendAtoB, "A→B must extend in ALL mode")
        assertTrue(out.single().extendBtoA, "B→A must extend in ALL mode")
    }

    @Test
    fun neverModeExtendsNothing() {
        val out = CrystalEditor.applyExtendPreference(structure, rules, CrystalEditor.ExtendBondDefaultMode.NEVER)
        assertFalse(out.single().extendAtoB, "A→B must not extend in NEVER mode")
        assertFalse(out.single().extendBtoA, "B→A must not extend in NEVER mode")
    }

    @Test
    fun metalsOnlyExtendsOnlyMetalDirection() {
        // Cs is a metal, Cl is a non-metal. In METALS_ONLY the direction whose INSIDE atom is
        // the metal extends; the non-metal direction stays off.
        val out = CrystalEditor.applyExtendPreference(structure, rules, CrystalEditor.ExtendBondDefaultMode.METALS_ONLY)
        val rule = out.single()
        assertTrue(rule.extendAtoB, "A=Cs (metal) direction must extend")
        assertFalse(rule.extendBtoA, "B=Cl (non-metal) direction must not extend")
    }

    @Test
    fun metalsOnlyNonMetalPairExtendsNothing() {
        // F-F rule (both non-metals): neither direction extends.
        val fRules = listOf(BondRule("F1", "F2", 0.1, 4.0))
        val fStructure = CrystalStructure(
            blockName = "ff",
            lattice = Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 90.0),
            spaceGroup = SpaceGroupCatalog.resolve("P1", 1),
            symmetryOperations = listOf(SymmetryOperation.IDENTITY),
            sites = listOf(
                Site("F1", "F1", Species("F"), FractionalCoordinate(0.1, 0.1, 0.1)),
                Site("F2", "F2", Species("F"), FractionalCoordinate(0.5, 0.5, 0.5)),
            ),
        )
        val out = CrystalEditor.applyExtendPreference(fStructure, fRules, CrystalEditor.ExtendBondDefaultMode.METALS_ONLY)
        assertFalse(out.single().extendAtoB, "non-metal A must not extend")
        assertFalse(out.single().extendBtoA, "non-metal B must not extend")
    }

    @Test
    fun metalsOnlyMetalMetalBondExtendsNothing() {
        // Cs-Cs rule (both metals, v0.8.43): metal-metal bonds are out of the METALS_ONLY
        // scope — neither direction extends (previously both extended).
        val mmRules = listOf(BondRule("Cs", "Cs", 0.1, 4.0))
        val out = CrystalEditor.applyExtendPreference(structure, mmRules, CrystalEditor.ExtendBondDefaultMode.METALS_ONLY)
        assertFalse(out.single().extendAtoB, "metal-metal A→B must not extend")
        assertFalse(out.single().extendBtoA, "metal-metal B→A must not extend")
    }

    @Test
    fun metalNonmetalMetalIdsScopesToMetalNonmetalBonds() {
        // Cs participates in a Cs-Cl (M-NM) bond and a Cs-Cs (M-M) bond — only the M-NM metal
        // id is in scope for polyhedra/extension.
        val rules = listOf(
            BondRule("Cs", "Cl", 0.1, 4.0),
            BondRule("Cs", "Cs", 0.1, 4.0),
        )
        assertEquals(setOf("Cs"), CrystalEditor.metalNonmetalMetalIds(structure, rules))
    }

    @Test
    fun metalNonmetalMetalIdsEmptyForMetalMetalOnly() {
        val out = CrystalEditor.metalNonmetalMetalIds(structure, listOf(BondRule("Cs", "Cs", 0.1, 4.0)))
        assertTrue(out.isEmpty(), "pure metal-metal bonding yields no polyhedra/extension scope")
    }
}
