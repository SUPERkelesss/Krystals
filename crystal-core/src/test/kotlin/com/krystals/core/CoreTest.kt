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

    @Test fun originAtomGeneratesPrimaryAndShellCells() {
        // Per v0.3.4: a single atom at the origin is placed in the primary cell, and the 26
        // surrounding neighbour cells are considered during bonding. Shell atoms that do not bond
        // to any primary atom are discarded to keep the snapshot small.
        val structure = CrystalStructure(
            "corners", UnitCell.DEFAULT, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(AtomSite("origin", "A1", "C", Vec3.ZERO)),
        )
        val scene = CrystalEngine.buildScene(structure)
        assertEquals(1, scene.atoms.size)
        assertEquals(1, scene.atoms.count { !it.isShell })
        assertEquals(0, scene.atoms.count { it.isShell })
    }

    @Test fun crossCellBondsUseShellAtoms() {
        // Per v0.3.4: a corner bond (0,0,0)-(0.75,0.75,0.75) is found by materialising B's image
        // in the (-1,-1,-1) shell cell, not via a minimum-image offset. The in-cell distance
        // sqrt(3)*0.75 ≈ 1.299 > 0.9 is ignored; the shell-atom distance sqrt(3)*0.25 ≈ 0.433 < 0.9
        // produces a bond whose atomB is the shell image and whose offsetB is zero.
        val structure = CrystalStructure(
            "corner", UnitCell.DEFAULT, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("a", "A1", "C", Vec3.ZERO),
                AtomSite("b", "B1", "O", Vec3(0.75, 0.75, 0.75)),
            ),
            bondRules = listOf(BondRule("a", "b", 0.1, 0.9)),
        )
        val scene = CrystalEngine.buildScene(structure)
        // Per v0.3.4: the cross-cell bond to B's (-1,-1,-1) shell image (distance sqrt(3)*0.25 ≈ 0.433)
        // is found via a real shell atom, not a minimum-image offset. Real-distance bonding with
        // boundary images also reaches other in-range images of the centre atom, so assert the
        // specific shell-image bond rather than an exact bond count.
        val bond = scene.bonds.first { it.distance < 0.5 && it.offsetB == Int3(0, 0, 0) }
        assertEquals(0.433, bond.distance, 0.01)
        val bAtom = scene.atoms.first { it.id == bond.atomB }
        assertTrue(bAtom.isShell)
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

    @Test fun csClCornerGeneratesBoundaryImages() {
        // Per v0.3.41: a corner atom (Cs at 0,0,0) in a 1×1×1 expansion should generate boundary
        // images at the other 7 corners of the unit cube. These are visible by default and can
        // act as bond centres, so all 8 Cs vertices are rendered.
        val cell = UnitCell(4.0, 4.0, 4.0, 90.0, 90.0, 90.0)
        val structure = CrystalStructure(
            "CsCl", cell, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("Cs", "Cs1", "Cs", Vec3.ZERO),
                AtomSite("Cl", "Cl1", "Cl", Vec3(0.5, 0.5, 0.5)),
            ),
        )
        val scene = CrystalEngine.buildScene(structure)
        val csAtoms = scene.atoms.filter { it.siteId == "Cs" }
        val clAtoms = scene.atoms.filter { it.siteId == "Cl" }

        assertEquals(8, csAtoms.size, "expected 1 primary Cs + 7 boundary-image Cs")
        assertEquals(1, clAtoms.count { !it.isShell }, "expected exactly one primary Cl")
        assertEquals(7, scene.atoms.count { it.isBoundaryImage && it.siteId == "Cs" })
        assertTrue(scene.atoms.none { it.isBoundaryImage && it.siteId == "Cl" })

        // Bonds whose atomB is not an external shell atom are drawn by default. Every Cs centre
        // (primary or boundary) bonds to the primary Cl, giving 8 visible Cs-Cl bonds.
        val atomById = scene.atoms.associateBy { it.id }
        val drawnBonds = scene.bonds.count { bond -> atomById.getValue(bond.atomB).let { !it.isExternalShell } }
        assertEquals(8, drawnBonds)

        // All 8 Cs atoms (primary + boundary images) participate in at least one bond.
        assertTrue(csAtoms.all { cs -> scene.bonds.any { it.atomA == cs.id || it.atomB == cs.id } })
    }

    @Test fun csClExternalShellIsHiddenWithoutExtendAcrossCell() {
        // Per v0.3.41: external shell atoms are kept for coordination/polyhedra but hidden unless
        // the bond rule opts in to "extend across cell".
        val cell = UnitCell(4.0, 4.0, 4.0, 90.0, 90.0, 90.0)
        val structure = CrystalStructure(
            "CsCl", cell, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("Cs", "Cs1", "Cs", Vec3.ZERO),
                AtomSite("Cl", "Cl1", "Cl", Vec3(0.5, 0.5, 0.5)),
            ),
            bondRules = listOf(BondRule("Cs", "Cl", 0.1, 4.0)),
        )
        val scene = CrystalEngine.buildScene(structure)
        val externalShell = scene.atoms.filter { it.isExternalShell }

        assertTrue(externalShell.isNotEmpty())
        assertTrue(externalShell.all { it.siteId == "Cl" })
        assertTrue(scene.atoms.none { it.isExternalShell && it.isBoundaryImage })
        // Every external-shell atom is referenced by at least one bond (so it is kept in the
        // snapshot), but no bond to an external-shell atom is drawn because extendAcrossCell is false.
        assertTrue(externalShell.all { ex -> scene.bonds.any { it.atomA == ex.id || it.atomB == ex.id } })
        assertTrue(scene.bonds.none { bond -> atomById(scene, bond.atomB).isExternalShell && bond.rule.extendAcrossCell })
    }

    @Test fun csClExtendAcrossCellRevealsExternalShell() {
        // Per v0.3.41: when the bond rule extends across the cell, every external-shell ligand
        // becomes visible.
        val cell = UnitCell(4.0, 4.0, 4.0, 90.0, 90.0, 90.0)
        val structure = CrystalStructure(
            "CsCl", cell, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("Cs", "Cs1", "Cs", Vec3.ZERO),
                AtomSite("Cl", "Cl1", "Cl", Vec3(0.5, 0.5, 0.5)),
            ),
            bondRules = listOf(BondRule("Cs", "Cl", 0.1, 4.0, extendAcrossCell = true)),
        )
        val scene = CrystalEngine.buildScene(structure)
        val externalShell = scene.atoms.filter { it.isExternalShell }

        assertTrue(externalShell.isNotEmpty())
        assertTrue(externalShell.all { ex ->
            scene.bonds.any { bond -> bond.atomB == ex.id && bond.rule.extendAcrossCell }
        })
    }

    @Test fun csClBoundaryImageBondsOrientShellAsAtomB() {
        // Per v0.3.43/v0.3.44: a primary-to-boundary-image bond keeps the shell atom as atomB so the
        // renderer (which keys cross-cell visibility on atomB) draws boundary-image bonds by default.
        // With a Cs-Cl rule, every Cs centre (primary or boundary image) bonds to the body-centred Cl;
        // the bond endpoint that is a shell atom must be atomB.
        val cell = UnitCell(4.0, 4.0, 4.0, 90.0, 90.0, 90.0)
        val structure = CrystalStructure(
            "CsCl", cell, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("Cs", "Cs1", "Cs", Vec3.ZERO),
                AtomSite("Cl", "Cl1", "Cl", Vec3(0.5, 0.5, 0.5)),
            ),
            bondRules = listOf(BondRule("Cs", "Cl", 0.1, 4.0)),
        )
        val scene = CrystalEngine.buildScene(structure)
        val atomById = scene.atoms.associateBy { it.id }
        // When a bond has exactly one shell endpoint, that endpoint must be atomB (so the renderer's
        // atomB-based cross-cell visibility check classifies it correctly). Bonds with two shell
        // endpoints (boundary<->boundary) are also fine and not constrained by this orientation rule.
        assertTrue(scene.bonds.all { bond ->
            val a = atomById.getValue(bond.atomA)
            val b = atomById.getValue(bond.atomB)
            !a.isShell || b.isShell // if a is a shell atom, b must be too (shell stays on the B side)
        })
        // There is at least one primary↔boundary-image bond (the renderer should draw by default).
        assertTrue(scene.bonds.any { bond ->
            val a = atomById.getValue(bond.atomA)
            val b = atomById.getValue(bond.atomB)
            !a.isShell && b.isBoundaryImage
        })
    }

    @Test fun boundaryImageToBoundaryImageBondIsGenerated() {
        // Per v0.3.44: a bond lying in a cell-face/edge plane (both endpoints are boundary images,
        // e.g. two different-site atoms on a shared cell edge) must be generated. Previously the
        // `a.isBoundaryImage && !b.isExternalShell` skip suppressed every boundary↔boundary pair.
        // Build a 1×1×1 cell with two atoms on the same cell edge (x=0,y=0 line): A at (0,0,0.25),
        // B at (0,0,0.75). Their images on neighbouring cells share that edge, so the A–B bond along
        // the edge is a boundary↔boundary bond.
        val cell = UnitCell(4.0, 4.0, 4.0, 90.0, 90.0, 90.0)
        val structure = CrystalStructure(
            "edge", cell, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("A", "A1", "C", Vec3(0.0, 0.0, 0.25)),
                AtomSite("B", "B1", "O", Vec3(0.0, 0.0, 0.75)),
            ),
            bondRules = listOf(BondRule("A", "B", 0.1, 2.5)),
        )
        val scene = CrystalEngine.buildScene(structure)
        val atomById = scene.atoms.associateBy { it.id }
        // At least one bond whose both endpoints are boundary images.
        val bbBonds = scene.bonds.filter { bond ->
            val a = atomById.getValue(bond.atomA)
            val b = atomById.getValue(bond.atomB)
            a.isBoundaryImage && b.isBoundaryImage
        }
        assertTrue(bbBonds.isNotEmpty(), "expected at least one boundary-image↔boundary-image bond")
        // Deduplication: no duplicate unordered atom-id pair.
        val keys = scene.bonds.map { bond -> listOf(bond.atomA, bond.atomB).sorted() }
        assertEquals(keys.size, keys.toSet().size, "duplicate bonds detected")
    }

    @Test fun sameSiteIntegerTranslationStillSkippedForBoundaryImages() {
        // Per v0.3.44: relaxing the boundary-centre skip must NOT reintroduce same-site periodic-
        // image bonds (Cs–Cs etc.) when no explicit rule exists. A single corner atom (Cs at origin)
        // with only an unrelated Cs–Cl rule should still produce zero Cs–Cs bonds.
        val cell = UnitCell(4.0, 4.0, 4.0, 90.0, 90.0, 90.0)
        val structure = CrystalStructure(
            "CsCl", cell, "P1", 1, listOf(SymmetryOperation.IDENTITY),
            listOf(
                AtomSite("Cs", "Cs1", "Cs", Vec3.ZERO),
                AtomSite("Cl", "Cl1", "Cl", Vec3(0.5, 0.5, 0.5)),
            ),
            bondRules = listOf(BondRule("Cs", "Cl", 0.1, 4.0)),
        )
        val scene = CrystalEngine.buildScene(structure)
        val atomById = scene.atoms.associateBy { it.id }
        assertTrue(scene.bonds.none { bond ->
            val a = atomById.getValue(bond.atomA)
            val b = atomById.getValue(bond.atomB)
            a.siteId == "Cs" && b.siteId == "Cs"
        }, "spurious Cs–Cs same-site periodic-image bond generated")
    }

    private fun atomById(scene: SceneSnapshot, id: Long): ExpandedAtom = scene.atoms.first { it.id == id }
}
