package com.krystals.renderer

import com.google.android.filament.Engine

/** Keeps Filament availability explicit and allows device capability checks without creating an engine eagerly. */
object FilamentRuntime {
    const val VERSION = "1.71.5"
    fun isAvailable(): Boolean = runCatching { Engine::class.java.name.isNotBlank() }.getOrDefault(false)
}
