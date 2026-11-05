package com.paper2binary.core.analysis

import com.paper2binary.core.model.Survey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A portable file holding a survey and, optionally, its responses. Sending one to another phone
 * shares the wording, and importing several phones' files combines their results. Each response
 * has a unique id, so importing the same file twice cannot create duplicates.
 */
@Serializable
data class SurveyBundle(
    val formatVersion: Int = 1,
    val survey: Survey,
    val responses: List<ResponseRecord> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FILE_EXTENSION = "p2b.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Throws [IllegalArgumentException] if the text is not a valid bundle. */
        fun fromJson(text: String): SurveyBundle = json.decodeFromString(serializer(), text)
    }
}
