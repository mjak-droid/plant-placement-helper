package com.example.plantplacement

import io.github.cdimascio.dotenv.dotenv
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }

    val env = dotenv {
        directory = "."
        ignoreIfMissing = true
    }

    val apiKey = env["GEMINI_API_KEY"] ?: System.getenv("GEMINI_API_KEY").orEmpty()
    val model = env["GEMINI_MODEL"] ?: System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash"
    recommendationRoutes(GeminiService(apiKey = apiKey, model = model))
}
