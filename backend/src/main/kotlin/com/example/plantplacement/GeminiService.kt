package com.example.plantplacement

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GeminiService(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash",
    private val client: HttpClient = createClient(),
) : RecommendationEngine {
    override suspend fun recommend(request: RecommendationRequest): RecommendationResponse {
        check(apiKey.isNotBlank()) { "Gemini is not configured." }

        val payload = buildPayload(
            mode = request.mode,
            spacePhoto = request.spacePhoto,
            preferences = request.preferences,
            plantName = request.plantName,
            plantPhoto = request.plantPhoto,
            model = model,
        )

        // The key goes in a header, not the URL, so client exceptions that print the URL
        // (timeouts, etc.) can't leak it into logs.
        val response = client.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(payload)
        }

        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw GeminiUpstreamException("Gemini returned HTTP ${response.status.value}: ${bodyText.take(600)}")
        }
        return parseRecommendation(bodyText)
    }

    companion object {
        private const val MARKER_TOLERANCE = 0.002f

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        private fun createClient() = HttpClient {
            install(ContentNegotiation) { json() }
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 90_000
            }
        }

        fun buildPayload(
            mode: String,
            spacePhoto: ImageInput,
            preferences: SuggestionPreferences?,
            plantName: String?,
            plantPhoto: ImageInput?,
            model: String = "gemini-2.5-flash",
        ): JsonObject {
            val prompt = buildString {
                append("You are a careful houseplant placement assistant. Analyze the room photo and return JSON only. ")
                if (mode == "existing") {
                    append("The user wants help placing a plant they already own. Recommend the best visible spot for that plant, and explain any likely mismatch or care concern cautiously. ")
                    append("If the plant may not suit the room, include a few better-suited alternative plant recommendations after the existing plant. ")
                } else {
                    append("Recommend plants that may suit visible conditions and the user's preferences. ")
                }
                append("Still photos cannot establish daily sunlight; clearly describe lighting as an estimate. ")
                append("If the photo is too unclear to assess, set unclear=true and recommendations=[]; do not guess. ")
                append("Set highlight x/y/width/height to normalized values in the 0..1 range and keep the rectangle inside the image. ")
                append("Return JSON with keys unclear and recommendations. Each recommendation has id, title, summary, explanation, and highlight. ")
                append("Each highlight has x, y, width, height (numbers) and label (short string). ")
                append("For the first recommendation, use the plant or result title and label the best available spot on the room photo. Mode: $mode.\n")
                preferences?.let {
                    append("Preferences: care=${it.careLevel}; size=${it.plantSize}; pet-safe-required=${it.petFriendly}; liked types=${it.likedPlantTypes}.\n")
                }
                plantName?.let { append("Existing plant name: $it.\n") }
                if (plantPhoto != null) append("An image of the user's existing plant is also attached.\n")
            }

            val parts = buildJsonArray {
                add(buildJsonObject { put("text", prompt) })
                add(imagePart(spacePhoto))
                plantPhoto?.let { add(imagePart(it)) }
            }

            return buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", parts)
                    })
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.3)
                    // Was 1200. On 2.5 models, thinking tokens count against this limit,
                    // so a small value can leave no room for the actual JSON.
                    put("maxOutputTokens", 8192)
                    // Thinking can be disabled on 2.5 Flash. It cannot on 2.5 Pro, so only send it for Flash.
                    if (model.contains("2.5-flash")) {
                        putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                    }
                }
            }
        }

        fun extractJsonText(raw: String): String {
            val trimmed = raw.trim()
            val fromFence = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
                .find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            if (!fromFence.isNullOrEmpty()) return fromFence
            val firstBrace = trimmed.indexOf('{')
            val lastBrace = trimmed.lastIndexOf('}')
            if (firstBrace in 0 until lastBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1)
            }
            return trimmed
        }

        /**
         * Turns a raw Gemini generateContent response body into a validated result.
         * Every failure throws GeminiUpstreamException with a message that says what went wrong,
         * so the server log shows the real cause.
         */
        fun parseRecommendation(bodyText: String): RecommendationResponse {
            val root = runCatching { Json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
                ?: throw GeminiUpstreamException("Gemini body was not a JSON object: ${bodyText.take(300)}")

            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw GeminiUpstreamException(
                    "Gemini returned no candidates. promptFeedback=${root["promptFeedback"]} body=${bodyText.take(300)}",
                )

            val finishReason = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull

            val rawText = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }
                ?: throw GeminiUpstreamException("Gemini returned no text (finishReason=$finishReason)")

            val generatedText = extractJsonText(rawText)

            val result = try {
                lenientJson.decodeFromString<RecommendationResponse>(generatedText)
            } catch (e: Exception) {
                throw GeminiUpstreamException(
                    "Gemini JSON did not match the expected shape (finishReason=$finishReason): ${e.message?.take(200)} | text=${generatedText.take(400)}",
                )
            }
            return validateResult(result)
        }

        private fun validateResult(result: RecommendationResponse): RecommendationResponse {
            if (result.unclear) return result
            if (result.recommendations.isEmpty()) throw GeminiUpstreamException("Gemini returned no recommendations")

            val fixed = result.recommendations.map { recommendation ->
                val m = recommendation.highlight
                val invalid = m.x < -MARKER_TOLERANCE || m.y < -MARKER_TOLERANCE ||
                    m.width <= 0f || m.height <= 0f ||
                    m.x + m.width > 1f + MARKER_TOLERANCE || m.y + m.height > 1f + MARKER_TOLERANCE
                if (invalid) {
                    throw GeminiUpstreamException(
                        "Gemini returned an invalid photo marker for '${recommendation.id}': " +
                            "x=${m.x} y=${m.y} width=${m.width} height=${m.height}",
                    )
                }
                // Clamp harmless float overshoot back inside the image.
                val x = m.x.coerceIn(0f, 1f)
                val y = m.y.coerceIn(0f, 1f)
                val width = m.width.coerceAtMost(1f - x)
                val height = m.height.coerceAtMost(1f - y)
                if (width <= 0f || height <= 0f) {
                    throw GeminiUpstreamException("Gemini returned a marker with no area for '${recommendation.id}'")
                }
                recommendation.copy(highlight = m.copy(x = x, y = y, width = width, height = height))
            }
            return result.copy(recommendations = fixed)
        }

        private fun imagePart(image: ImageInput) = buildJsonObject {
            putJsonObject("inlineData") {
                put("mimeType", image.mimeType)
                put("data", image.data)
            }
        }
    }
}

class GeminiUpstreamException(message: String) : RuntimeException(message)
