package com.google.ai.sample.feature.trip

data class Trip(
    val journey: List<TripLeg>
)

data class TripLeg(
    val date: String? = null,
    val type: String,
    val location: Location,
    val stopDuration: String? = null,
    val transportMode: String? = null,
    val travelTime: String? = null,
    val photos: List<String>,
    val weather: String? = null,
    val temperature: Int? = null,
    val activityLevel: String? = null,
    val nearbyPlaces: List<Location> = emptyList()
)

data class Location(
    val nodeType: String? = null,
    val nodeName: String,
    val areaName: String? = null,
    val city: String? = null,
    val address: String? = null,
    val link: String? = null,
)