package com.krystals.crystal.core.model

import com.krystals.crystal.core.coordinate.FractionalCoordinate

data class Site(
    val id: String,
    val label: String,
    val species: Species,
    val fractionalCoordinate: FractionalCoordinate,
    val occupancy: Double = 1.0,
)
