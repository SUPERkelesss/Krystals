package com.krystals.crystal.data

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PeriodicTableDataTest {

    @Test
    fun hydrogenCationHasShannonRadius() {
        // H⁺ needs a Shannon IR so smartIonic can resolve H as a valence-1 cation.
        assertNotNull(PeriodicTableData.shannonIonicRadius("H", 1, 1))
        assertNotNull(PeriodicTableData.shannonIonicRadius("H", 1, 2))
    }

    @Test
    fun hydrogenIonicRadiusIsBareProtonApproximation() {
        // User decision: H⁺ effective ionic radius ≈ 0.01 (bare proton).
        val cn1 = PeriodicTableData.shannonIonicRadius("H", 1, 1)
        val cn2 = PeriodicTableData.shannonIonicRadius("H", 1, 2)
        assertNotNull(cn1)
        assertNotNull(cn2)
        assertEquals(0.01, cn1!!, 0.001)
        assertEquals(0.01, cn2!!, 0.001)
    }
}
