package com.example.plantplacement

import kotlinx.serialization.Serializable

@Serializable
data class ImageInput(
    val mimeType: String,
    val data: String,
)

@Serializable
data class SuggestionPreferences(
    val careLevel: String,
    val plantSize: String,
    val petFriendly: Boolean,
    val likedPlantTypes: String = "",
)

@Serializable
data class RecommendationRequest(
    val mode: String,
    val spacePhoto: ImageInput,
    val preferences: SuggestionPreferences? = null,
    val plantName: String? = null,
    val plantPhoto: ImageInput? = null,
)

@Serializable
data class HighlightArea(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String = "",
)

@Serializable
data class PlantRecommendation(
    val id: String,
    val title: String,
    val summary: String,
    val explanation: String,
    val highlight: HighlightArea,
)

@Serializable
data class RecommendationResponse(
    val unclear: Boolean = false,
    val recommendations: List<PlantRecommendation>,
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class HealthResponse(val status: String)
