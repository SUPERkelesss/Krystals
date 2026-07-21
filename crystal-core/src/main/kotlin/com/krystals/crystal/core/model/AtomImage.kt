package com.krystals.crystal.core.model

import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.periodic.Int3

data class AtomImage(
    val id: Long,
    val siteId: String,
    val siteLabel: String,
    val species: Species,
    val fractionalCoordinate: FractionalCoordinate,
    val cartesianCoordinate: CartesianCoordinate,
    val occupancy: Double,
    val cellOffset: Int3,
    val isShell: Boolean = false,
    val isBoundaryImage: Boolean = false,
) {
    val isExternalShell: Boolean get() = isShell && !isBoundaryImage
}
