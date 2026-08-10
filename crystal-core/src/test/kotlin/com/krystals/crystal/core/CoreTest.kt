package com.krystals.crystal.core

import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.lattice.Lattice
import com.krystals.crystal.core.math.ExpressionParser
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.angleDegrees
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.model.Site
import com.krystals.crystal.core.model.Species
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary
import com.krystals.crystal.core.symmetry.SpaceGroup
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreTest {
    @Test fun evaluatesSafeExpressions() {
        assertEquals(0.5, ExpressionParser("1 / (1 + 1)").evaluate(), 1e-10)
    }

    @Test fun computesMeasurements() {
        assertEquals(90.0, angleDegrees(Vec3(1.0, 0.0, 0.0), Vec3.ZERO, Vec3(0.0, 1.0, 0.0)), 1e-8)
    }

    @Test fun catalogContainsAllSpaceGroups() {
        assertEquals(230, SpaceGroupCatalog.all.size)
        assertEquals("Fm-3m", SpaceGroupCatalog.all[224].symbol)
        assertTrue(SpaceGroupCatalog.operations("Fm-3m").size > 1)
    }

    /**
     * Per v0.7.0: CIF files spell space groups in many ways — standard H-M
     * ("I41/amd"), pymatgen underscore style ("I4_1/amd"), IT origin-choice
     * suffixes ("I41/amd:1") and spaced forms ("F m -3 m"). resolve() must
     * always emit the single canonical catalog symbol so editors/UI are
     * consistent, and suffixed names must resolve instead of falling into the
     * unknown-group branch.
     */
    @Test fun resolveNormalizesAllSpellingsToCanonicalSymbol() {
        // Canonical tetragonal space group #141
        val canonical = SpaceGroupCatalog.all[140]
        assertEquals("I41/amd", canonical.symbol)

        // Underscore style used by pymatgen/Materials Project CIFs
        val underscored = SpaceGroupCatalog.resolve("I4_1/amd", 141)
        assertEquals("I41/amd", underscored.symbol, "underscore spelling must normalize to canonical")
        assertEquals(141, underscored.number)

        // IT origin-choice suffix
        val suffixed = SpaceGroupCatalog.resolve("I41/amd:1", 141)
        assertEquals("I41/amd", suffixed.symbol, "origin-choice suffix must be stripped")
        assertEquals(141, suffixed.number)

        // Spaced form
        val spaced = SpaceGroupCatalog.resolve("F m -3 m", 225)
        assertEquals("Fm-3m", spaced.symbol, "spaced spelling must normalize to canonical")
        assertEquals(225, spaced.number)

        // find() must also match suffixed/underscored spellings (used by operations())
        assertEquals(141, SpaceGroupCatalog.find("I4_1/amd:2")?.number)
        assertEquals(225, SpaceGroupCatalog.find("F m -3 m")?.number)

        // Operations must be found through the suffixed spelling, not fall back to identity
        assertTrue(SpaceGroupCatalog.operations("I41/amd:1").size > 1)
    }

    @Test fun latticeCoordinatesRoundTrip() {
        val lattice = Lattice(4.1, 5.2, 6.3, 78.0, 91.0, 113.0)
        val fractional = FractionalCoordinate(0.2, 0.4, 0.7)
        val cartesian = lattice.toCartesian(fractional)
        val roundTrip = lattice.toFractional(cartesian)
        assertEquals(fractional.x, roundTrip.x, 1e-10)
        assertEquals(fractional.y, roundTrip.y, 1e-10)
        assertEquals(fractional.z, roundTrip.z, 1e-10)
        assertTrue(lattice.volume > 0.0)
        assertEquals(lattice.a, Lattice.fromMatrix(lattice.matrix).a, 1e-10)
    }

    @Test fun rejectsInvalidAndDegenerateLattices() {
        assertFailsWith<IllegalArgumentException> {
            Lattice(4.0, 4.0, 4.0, 90.0, 90.0, 180.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Lattice(4.0, 4.0, 4.0, 10.0, 10.0, 170.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Lattice(Double.POSITIVE_INFINITY, 4.0, 4.0, 90.0, 90.0, 90.0)
        }
    }

    @Test fun periodicBoundaryPreservesExistingSemantics() {
        assertEquals(FractionalCoordinate(0.25, 0.0, 0.5), PeriodicBoundary.wrap(FractionalCoordinate(1.25, -1.0, 0.5)))
        assertTrue(PeriodicBoundary.equivalent(FractionalCoordinate.ZERO, FractionalCoordinate(1.0, -2.0, 3.0)))
        assertTrue(PeriodicBoundary.isIntegerTranslation(FractionalCoordinate(1.0, -2.0, 3.0)))
        assertEquals(27, PeriodicBoundary.neighborOffsets().size)
    }

    @Test fun coreModelsExposeStrongTypesAndCopy() {
        val species = Species("Si")
        val site = Site("si", "Si1", species, FractionalCoordinate.ZERO)
        val structure = CrystalStructure("test", Lattice.DEFAULT, SpaceGroup("unknown"), listOf(SymmetryOperation.IDENTITY), listOf(site))
        val image = AtomImage(1, site.id, site.label, species, site.fractionalCoordinate, CartesianCoordinate.ZERO, 1.0, Int3(0, 0, 0))
        assertEquals("unknown", structure.copy(blockName = "copy").spaceGroup.symbol)
        assertEquals(species, site.copy(label = "Si2").species)
        assertEquals(site.fractionalCoordinate, image.copy(id = 2).fractionalCoordinate)
    }
}
