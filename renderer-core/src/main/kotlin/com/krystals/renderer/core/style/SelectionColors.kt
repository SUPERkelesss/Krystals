package com.krystals.renderer.core.style

/**
 * Selection/highlight colors shared by both rendering backends.
 *
 * - [SELECTED_ARGB] is used for the currently selected atom(s).
 * - [LOCKED_ARGB] is used for atoms that are part of a locked measurement or inspection.
 */
object SelectionColors {
    const val SELECTED_ARGB = 0xFF7542A5L
    const val LOCKED_ARGB = 0xFFCFA7F5L
}
