package com.krystals.crystal.core

/**
 * Thrown when a CIF token (symmetry operation fraction, cell parameter, etc.) cannot be
 * parsed into a number.  The UI layer catches this to show a user-readable warning dialog
 * instead of crashing with a raw [NumberFormatException].
 */
class CifParseException(message: String) : RuntimeException(message)
