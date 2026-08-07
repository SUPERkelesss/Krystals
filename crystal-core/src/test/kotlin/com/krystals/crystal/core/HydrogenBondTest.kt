package com.krystals.crystal.core

import com.krystals.crystal.core.model.HydrogenBond
import com.krystals.crystal.core.periodic.Int3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HydrogenBondTest {

    private fun hbond(
        siteA: String = "H1",
        siteB: String = "O2",
        extendAtoB: Boolean = false,
        extendBtoA: Boolean = false,
    ) = HydrogenBond(
        donorId = 1L,
        acceptorId = 2L,
        distance = 1.8,
        siteA = siteA,
        siteB = siteB,
        ruleKey = "H1\u0000O2\u0000hbond",
        extendAtoB = extendAtoB,
        extendBtoA = extendBtoA,
        offsetB = Int3(0, 0, 1),
    )

    @Test
    fun ruleKeyPreservesHbondDiscriminator() {
        val h = hbond()
        assertEquals("H1\u0000O2\u0000hbond", h.ruleKey)
    }

    @Test
    fun extendAcrossCellInsideDonorSite() {
        // donor site inside, acceptor external, no extend flags -> hidden (false).
        assertFalse(hbond().shouldExtendAcrossCell("H1", true))
        // extendAtoB permits the H->X direction.
        assertTrue(hbond(extendAtoB = true).shouldExtendAcrossCell("H1", true))
        // the X->H direction needs extendBtoA.
        assertFalse(hbond(extendAtoB = true).shouldExtendAcrossCell("O2", true))
        assertTrue(hbond(extendBtoA = true).shouldExtendAcrossCell("O2", true))
    }

    @Test
    fun extendAcrossCellNeverBlocksNonExternal() {
        // outsideAtomIsExternal=false short-circuits to true, same as BondRule.
        assertTrue(hbond().shouldExtendAcrossCell("H1", false))
    }

    @Test
    fun extendAcrossCellSameSitePairUsesEitherFlag() {
        val self = hbond(siteA = "H1", siteB = "H1")
        assertFalse(self.shouldExtendAcrossCell("H1", true))
        assertTrue(hbond(siteA = "H1", siteB = "H1", extendBtoA = true).shouldExtendAcrossCell("H1", true))
    }

    @Test
    fun extendAcrossCellUnknownSiteReturnsFalse() {
        assertFalse(hbond().shouldExtendAcrossCell("N9", true))
    }

    @Test
    fun offsetBPreserved() {
        assertEquals(Int3(0, 0, 1), hbond().offsetB)
    }
}
