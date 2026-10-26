package com.example.plantplacement

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
private const val MAX_ENCODED_IMAGE_CHARS = 6 * 1024 * 1024
private val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private const val GENERIC_FAILURE = "Plant analysis could not finish. Please try again."

fun Application.recommendationRoutes(engine: RecommendationEngine) {
    routing {
        get("/health") {
            call.respond(HealthResponse("ok"))
        }
        route("/api") {
            post("/recommendations") {
                val request = try {
                    call.receive<RecommendationRequest>()
                } catch (_: ContentTransformationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request body must be valid JSON."))
                    return@post
                } catch (_: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request is missing required fields."))
                    return@post
                } catch (_: BadRequestException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Request body could not be read."))
                    return@post
                }

                val validationError = validateRequest(request)
                if (validationError != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                    return@post
                }

                try {
                    call.respond(engine.recommend(request))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    call.application.log.error("Plant analysis not configured: ${e.message}")
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Plant analysis is not configured yet."))
                } catch (e: GeminiUpstreamException) {
                    // The detail goes to the server log only, never to the client.
                    call.application.log.error("Gemini upstream failure: ${e.message}", e)
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(GENERIC_FAILURE))
                } catch (e: Exception) {
                    call.application.log.error("Unexpected failure during plant analysis", e)
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(GENERIC_FAILURE))
                }
            }
        }
    }
}

private fun validateRequest(request: RecommendationRequest): String? {
    val spacePhotoError = validateImage(request.spacePhoto, "Choose a supported, clear room photo.")
    if (spacePhotoError != null) return spacePhotoError

    return when (request.mode) {
        "suggest" -> {
            val preferences = request.preferences ?: return "Plant preferences are required."
            if (preferences.careLevel !in setOf("easy", "more-care")) return "Choose a valid care level."
            if (preferences.plantSize !in setOf("small", "medium", "large")) return "Choose a valid plant size."
            if (preferences.likedPlantTypes.length > 300) return "Plant preferences are too long."
            null
        }
        "existing" -> {
            if (request.plantName.isNullOrBlank() && request.plantPhoto == null) {
                return "Enter the plant name or add a photo of the plant."
            }
            if (request.plantName != null && request.plantName.length > 120) return "Plant name is too long."
            request.plantPhoto?.let { validateImage(it, "Choose a supported plant photo.") }
        }
        else -> "Choose a valid recommendation type."
    }
}

private fun validateImage(image: ImageInput, message: String): String? {
    if (image.mimeType !in ALLOWED_IMAGE_TYPES) return message
    if (image.data.isBlank() || image.data.length > MAX_ENCODED_IMAGE_CHARS) return "Image is too large. Choose a smaller photo."
    val decodedLength = runCatching { Base64.getDecoder().decode(image.data).size }.getOrNull()
        ?: return message
    if (decodedLength !in 1..MAX_IMAGE_BYTES) return "Image is too large. Choose a smaller photo."
    return null
}

interface RecommendationEngine {
    suspend fun recommend(request: RecommendationRequest): RecommendationResponse
}
