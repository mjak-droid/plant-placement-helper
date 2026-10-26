package com.example.plantplacement

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val VALID_BODY =
    """{"mode":"suggest","spacePhoto":{"mimeType":"image/jpeg","data":"AQ=="},"preferences":{"careLevel":"easy","plantSize":"small","petFriendly":false}}"""

/** Builds a Gemini generateContent response body whose single part contains [text]. */
private fun geminiBody(text: String, finishReason: String = "STOP"): String = buildJsonObject {
    putJsonArray("candidates") {
        add(buildJsonObject {
            putJsonObject("content") {
                putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
            }
            put("finishReason", finishReason)
        })
    }
}.toString()

private fun recommendationJson(x: String, y: String, w: String, h: String, label: String = ",\"label\":\"window\""): String =
    """{"unclear":false,"recommendations":[{"id":"r1","title":"Pothos","summary":"s","explanation":"e","highlight":{"x":$x,"y":$y,"width":$w,"height":$h$label}}]}"""

class RecommendationRoutesTest {
    @Test
    fun healthEndpointReturnsOk() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            recommendationRoutes(object : RecommendationEngine {
                override suspend fun recommend(request: RecommendationRequest) = error("not called")
            })
        }

        val response = client.get("/health") {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun invalidModeDoesNotCallGemini() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            recommendationRoutes(object : RecommendationEngine {
                override suspend fun recommend(request: RecommendationRequest) = error("Gemini must not be called")
            })
        }

        val response = client.post("/api/recommendations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody("""{"mode":"unsupported","spacePhoto":{"mimeType":"image/jpeg","data":"AQ=="},"preferences":{"careLevel":"easy","plantSize":"small","petFriendly":false}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            "Choose a valid recommendation type.",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun rejectsUnsupportedImageMimeType() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            recommendationRoutes(object : RecommendationEngine {
                override suspend fun recommend(request: RecommendationRequest) = error("Gemini must not be called")
            })
        }

        val response = client.post("/api/recommendations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody("""{"mode":"suggest","spacePhoto":{"mimeType":"image/svg+xml","data":"AQ=="},"preferences":{"careLevel":"easy","plantSize":"small","petFriendly":false}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun existingPlantModeRequiresPlantNameOrPhoto() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            recommendationRoutes(object : RecommendationEngine {
                override suspend fun recommend(request: RecommendationRequest) = error("Gemini must not be called")
            })
        }

        val response = client.post("/api/recommendations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody("""{"mode":"existing","spacePhoto":{"mimeType":"image/jpeg","data":"AQ=="}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "Enter the plant name")
    }

    @Test
    fun existingPlantModeAcceptsPlantName() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            recommendationRoutes(object : RecommendationEngine {
                override suspend fun recommend(request: RecommendationRequest): RecommendationResponse {
                    assertEquals("existing", request.mode)
                    assertEquals("Monstera", request.plantName)
                    return RecommendationResponse(unclear = true, recommendations = emptyList())
                }
            })
        }

        val response = client.post("/api/recommendations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody("""{"mode":"existing","spacePhoto":{"mimeType":"image/jpeg","data":"AQ=="},"plantName":"Monstera"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun upstreamFailureReturns502WithoutLeakingDetails() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            recommendationRoutes(object : RecommendationEngine {
                override suspend fun recommend(request: RecommendationRequest): RecommendationResponse =
                    throw GeminiUpstreamException("secret internal detail")
            })
        }

        val response = client.post("/api/recommendations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(VALID_BODY)
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        val text = response.bodyAsText()
        assertContains(text, "Plant analysis could not finish")
        assertFalse(text.contains("secret internal detail"))
    }

    @Test
    fun payloadUsesSimpleJsonMimeTypeAndStripsCodeFences() {
        val payload = GeminiService.buildPayload(
            "suggest",
            ImageInput("image/png", "AQ=="),
            SuggestionPreferences("easy", "medium", true, "fern"),
            null,
            null,
        )

        assertEquals("application/json", payload.jsonObject["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertFalse(payload.jsonObject["generationConfig"]!!.jsonObject.containsKey("responseSchema"))
        assertEquals("{\"unclear\":true,\"recommendations\":[]}", GeminiService.extractJsonText("```json\n{\"unclear\":true,\"recommendations\":[]}\n```"))
    }

    @Test
    fun payloadHasRoomForThinkingAndDisablesItOnFlash() {
        val flash = GeminiService.buildPayload("suggest", ImageInput("image/png", "AQ=="), null, null, null, "gemini-2.5-flash")
        val config = flash.jsonObject["generationConfig"]!!.jsonObject
        assertTrue(config["maxOutputTokens"]!!.jsonPrimitive.content.toInt() >= 4096)
        assertEquals("0", config["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)

        val pro = GeminiService.buildPayload("suggest", ImageInput("image/png", "AQ=="), null, null, null, "gemini-2.5-pro")
        assertFalse(pro.jsonObject["generationConfig"]!!.jsonObject.containsKey("thinkingConfig"))
    }

    @Test
    fun parsesValidResponse() {
        val result = GeminiService.parseRecommendation(geminiBody(recommendationJson("0.1", "0.2", "0.3", "0.4")))
        assertEquals(1, result.recommendations.size)
        assertEquals("window", result.recommendations.first().highlight.label)
    }

    @Test
    fun parsesResponseWhenModelOmitsLabelAndAddsExtraKeys() {
        val text = """{"unclear":false,"extra":"ignored","recommendations":[{"id":"r1","title":"t","summary":"s","explanation":"e","notes":"x","highlight":{"x":0.1,"y":0.1,"width":0.2,"height":0.2}}]}"""
        val result = GeminiService.parseRecommendation(geminiBody(text))
        assertEquals("", result.recommendations.first().highlight.label)
    }

    @Test
    fun parsesFencedResponse() {
        val fenced = "```json\n" + recommendationJson("0.1", "0.2", "0.3", "0.4") + "\n```"
        val result = GeminiService.parseRecommendation(geminiBody(fenced))
        assertEquals("r1", result.recommendations.first().id)
    }

    @Test
    fun parsesUnclearResponse() {
        val result = GeminiService.parseRecommendation(geminiBody("""{"unclear":true,"recommendations":[]}"""))
        assertTrue(result.unclear)
    }

    @Test
    fun clampsTinyMarkerOvershoot() {
        val result = GeminiService.parseRecommendation(geminiBody(recommendationJson("0.5", "0.5", "0.5001", "0.5")))
        val h = result.recommendations.first().highlight
        assertTrue(h.x + h.width <= 1f)
    }

    @Test
    fun rejectsMarkerOnThousandScaleAndReportsValues() {
        val e = assertFailsWith<GeminiUpstreamException> {
            GeminiService.parseRecommendation(geminiBody(recommendationJson("100", "200", "300", "400")))
        }
        assertContains(e.message.orEmpty(), "invalid photo marker")
    }

    @Test
    fun truncatedResponseReportsFinishReason() {
        // What Gemini returns when thinking tokens eat the output budget: no "parts" at all.
        val body = """{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS"}]}"""
        val e = assertFailsWith<GeminiUpstreamException> { GeminiService.parseRecommendation(body) }
        assertContains(e.message.orEmpty(), "MAX_TOKENS")
    }

    @Test
    fun truncatedJsonTextReportsShapeError() {
        val e = assertFailsWith<GeminiUpstreamException> {
            GeminiService.parseRecommendation(geminiBody("""{"unclear":false,"recommendations":[{"id":"r1","ti""", "MAX_TOKENS"))
        }
        assertContains(e.message.orEmpty(), "MAX_TOKENS")
    }
}
