package com.krystals.renderer.filament

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilamentSmokeTest {
    @Test
    fun engineCreatesAndClosesWithBundledMaterials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FilamentRenderer(context).close()
    }
}
