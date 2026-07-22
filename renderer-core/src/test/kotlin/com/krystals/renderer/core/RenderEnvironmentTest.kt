package com.krystals.renderer.core

import com.krystals.renderer.core.style.DepthCueing
import com.krystals.renderer.core.style.RenderEnvironment
import com.krystals.renderer.core.style.ViewerAppearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenderEnvironmentTest {
    @Test
    fun appearanceMapsAllBackendNeutralStyles() {
        val appearance = ViewerAppearance(
            atomOpacity = 0.6f,
            bondRadius = 0.12f,
            polyhedronOpacity = 0.3f,
            showAxes = false,
        )

        val environment = appearance.toEnvironment()

        assertEquals(0.6f, environment.atoms.opacity)
        assertEquals(0.12f, environment.bonds.radius)
        assertEquals(0.3f, environment.polyhedra.opacity)
        assertEquals(false, environment.axes.visible)
    }

    @Test
    fun rejectsInvalidDepthRangeAndArgb() {
        assertFailsWith<IllegalArgumentException> { DepthCueing(near = -2f, far = 2f) }
        assertFailsWith<IllegalArgumentException> { RenderEnvironment(backgroundArgb = 0x1_00000000) }
    }
}
