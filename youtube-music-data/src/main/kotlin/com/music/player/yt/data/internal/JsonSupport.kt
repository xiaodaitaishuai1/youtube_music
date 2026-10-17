package com.music.player.yt.data.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.objectAt(vararg names: String): JsonObject? {
    return elementAt(*names) as? JsonObject
}

internal fun JsonObject.arrayAt(vararg names: String): JsonArray =
    elementAt(*names) as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.stringAt(vararg names: String): String? {
    return (elementAt(*names) as? JsonPrimitive)?.contentOrNull
}

internal fun JsonObject.longAt(vararg names: String): Long? {
    return (elementAt(*names) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}

internal fun JsonObject.elementAt(vararg names: String): JsonElement? {
    var current: JsonElement = this
    for (name in names) current = (current as? JsonObject)?.get(name) ?: return null
    return current
}

internal fun JsonElement.findString(predicate: (String) -> Boolean): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.takeIf(predicate)
    is JsonObject -> values.firstNotNullOfOrNull { it.findString(predicate) }
    is JsonArray -> firstNotNullOfOrNull { it.findString(predicate) }
}

internal fun JsonObject.runsText(): String = arrayAt("runs").joinToString("") {
    it.asObjectOrNull()?.stringAt("text").orEmpty()
}

internal fun JsonObject.continuationToken(): String? = arrayAt("continuations").firstNotNullOfOrNull { continuation ->
    continuation.asObjectOrNull()?.objectAt("nextContinuationData")?.stringAt("continuation")
        ?: continuation.asObjectOrNull()?.objectAt("nextRadioContinuationData")?.stringAt("continuation")
}
