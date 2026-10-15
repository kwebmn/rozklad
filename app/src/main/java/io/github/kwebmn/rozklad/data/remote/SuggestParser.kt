package io.github.kwebmn.rozklad.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object SuggestParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(val suggestions: List<String> = emptyList())

    fun parse(text: String): List<String> = try {
        json.decodeFromString<Response>(text).suggestions
    } catch (e: IllegalArgumentException) {
        throw SiteFormatException("Bad suggest response: ${e.message}")
    }
}
