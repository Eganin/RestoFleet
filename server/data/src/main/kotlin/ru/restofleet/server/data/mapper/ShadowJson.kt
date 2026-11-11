package ru.restofleet.server.data.mapper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue
import kotlin.math.abs

/**
 * JSON ↔ [ShadowDocument] (реш. 224): числа — `Double`, целое пишется без `.0` (точно до 2^53); `null` — отсутствие
 * ключа (каноническая форма протокола), `null` в массиве пропускается; бесконечные числа (`1e400`) отбрасываются
 * так же.
 * Используется для `desired`/`reported` теней и параметров команд.
 */
object ShadowJson {
    private const val MAX_EXACT = 9_007_199_254_740_992.0

    fun toJson(document: ShadowDocument): JsonObject = JsonObject(document.fields.mapValues { (_, v) -> toElement(v) })

    fun fromJson(json: JsonObject): ShadowDocument =
        ShadowDocument(json.entries.mapNotNull { (k, v) -> fromElement(v)?.let { k to it } }.toMap())

    fun encode(document: ShadowDocument): String = RfJson.encodeToString(JsonObject.serializer(), toJson(document))

    /** Разбор JSON-объекта; не объект — [IllegalArgumentException]. */
    fun decode(text: String): ShadowDocument =
        fromJson(RfJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not a JSON object"))

    fun toElement(value: ShadowValue): JsonElement = when (value) {
        is ShadowValue.Num -> number(value.value)
        is ShadowValue.Str -> JsonPrimitive(value.value)
        is ShadowValue.Bool -> JsonPrimitive(value.value)
        is ShadowValue.List -> JsonArray(value.items.map(::toElement))
        is ShadowValue.Obj -> JsonObject(value.fields.mapValues { (_, v) -> toElement(v) })
    }

    fun fromElement(element: JsonElement): ShadowValue? = when (element) {
        JsonNull -> null
        is JsonObject -> ShadowValue.Obj(fromJson(element).fields)
        is JsonArray -> ShadowValue.List(element.mapNotNull(::fromElement))
        is JsonPrimitive -> when {
            element.isString -> ShadowValue.Str(element.content)
            element.booleanOrNull != null -> ShadowValue.Bool(element.booleanOrNull!!)
            // `1e400` разбирается в бесконечность: такой узел отбрасывается, иначе `jsonb` получил бы невалидный JSON
            else -> element.doubleOrNull?.takeIf { it.isFinite() }?.let { ShadowValue.Num(it) }
        }
    }

    private fun number(value: Double): JsonPrimitive =
        if (value % 1.0 == 0.0 && abs(value) <= MAX_EXACT) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
}
