package com.maxrave.spotify

import com.maxrave.spotify.model.response.spotify.SpotifyTrackInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Loose readers for Spotify's internal GraphQL responses.
 *
 * That schema is undocumented and reshaped without notice, so nothing here binds to a fixed path.
 * Walking for the shape that matters costs a full traversal of a response measured in kilobytes,
 * and buys immunity to the nesting moving — which it does.
 */

/** Every object in the tree, this one included, depth first. */
internal fun JsonElement.allObjects(): Sequence<JsonObject> =
    sequence {
        when (this@allObjects) {
            is JsonObject -> {
                yield(this@allObjects)
                values.forEach { yieldAll(it.allObjects()) }
            }

            is JsonArray -> forEach { yieldAll(it.allObjects()) }
            else -> Unit
        }
    }

internal fun JsonElement.findObject(predicate: (JsonObject) -> Boolean): JsonObject? =
    allObjects().firstOrNull(predicate)

/** The first integer stored under [key] anywhere in the tree. */
internal fun JsonElement.findInt(key: String): Int? =
    allObjects()
        .mapNotNull { (it[key] as? JsonPrimitive)?.content?.toIntOrNull() }
        .firstOrNull()

internal fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The message from a GraphQL `errors` array, when one is present. */
internal fun JsonElement.firstMessage(): String? =
    allObjects()
        .mapNotNull { it["message"]?.stringOrNull() }
        .firstOrNull()

/**
 * Reads one `__typename: "Track"` object into the shape the matcher wants.
 *
 * Duration has appeared as `trackDuration.totalMilliseconds`, as `duration.totalMilliseconds` and
 * as a bare number, so all three are tried. A track with no title is dropped: there is nothing to
 * search YouTube for, and a blank query would match something arbitrary.
 */
internal fun JsonObject.toTrackInfo(): SpotifyTrackInfo? {
    val title = this["name"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val artists =
        (this["artists"] as? JsonObject)
            ?.get("items")
            ?.allObjects()
            ?.mapNotNull { (it["profile"] as? JsonObject)?.get("name")?.stringOrNull() }
            ?.toList()
            .orEmpty()
    val duration =
        durationFrom("trackDuration") ?: durationFrom("duration")
            ?: (this["duration"] as? JsonPrimitive)?.content?.toLongOrNull()
    return SpotifyTrackInfo(
        title = title,
        artist = artists.joinToString(", "),
        durationMs = duration,
    )
}

private fun JsonObject.durationFrom(key: String): Long? =
    ((this[key] as? JsonObject)?.get("totalMilliseconds") as? JsonPrimitive)?.content?.toLongOrNull()

/** Convenience for callers holding a raw element they know is an object. */
internal fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
