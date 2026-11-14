package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class YouTubeLyrics(
    val lines: List<Line>?,
    val plain: String?,
    val source: String?,
) {
    data class Line(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    companion object {
        fun fromAndroidBrowse(response: JsonElement): YouTubeLyrics? {
            val data = response.findObject("timedLyricsModel")?.get("lyricsData")?.asObject() ?: return null
            val lines =
                (data["timedLyricsData"] as? JsonArray)
                    ?.mapNotNull { element ->
                        val line = element.asObject() ?: return@mapNotNull null
                        val cue = line["cueRange"]?.asObject() ?: return@mapNotNull null
                        Line(
                            text = line["lyricLine"].text() ?: return@mapNotNull null,
                            startMs = cue["startTimeMilliseconds"].text()?.toLongOrNull() ?: return@mapNotNull null,
                            endMs = cue["endTimeMilliseconds"].text()?.toLongOrNull() ?: 0L,
                        )
                    }.orEmpty()
            if (lines.isEmpty()) return null
            return YouTubeLyrics(
                lines = lines,
                plain = null,
                source = data["sourceMessage"].text(),
            )
        }

        private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

        private fun JsonElement.findObject(key: String): JsonObject? =
            when (this) {
                is JsonObject -> (this[key] as? JsonObject) ?: values.firstNotNullOfOrNull { it.findObject(key) }
                is JsonArray -> firstNotNullOfOrNull { it.findObject(key) }
                else -> null
            }
    }
}
