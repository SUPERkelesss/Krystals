package com.krystals.app

import android.content.SharedPreferences

enum class RendererBackend { FILAMENT, CANVAS_LEGACY }

object RendererBackendStore {
    const val KEY = "renderer_backend"

    fun load(preferences: SharedPreferences): RendererBackend = fromStored(preferences.getString(KEY, null))

    internal fun fromStored(value: String?): RendererBackend = runCatching {
        RendererBackend.valueOf(value ?: RendererBackend.FILAMENT.name)
    }.getOrDefault(RendererBackend.FILAMENT)

    fun save(preferences: SharedPreferences, backend: RendererBackend) {
        preferences.edit().putString(KEY, backend.name).apply()
    }
}

internal fun effectiveBackend(preferred: RendererBackend, filamentSessionFailed: Boolean): RendererBackend =
    if (preferred == RendererBackend.FILAMENT && filamentSessionFailed) RendererBackend.CANVAS_LEGACY else preferred
