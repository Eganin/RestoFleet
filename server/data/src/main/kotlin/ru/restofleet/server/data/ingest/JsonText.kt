package ru.restofleet.server.data.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Проверки текста JSON перед записью исходного сообщения в `jsonb` (реш. 241, R-6): PostgreSQL требует корректных
 * суррогатных пар в экранированиях `\uXXXX`, а kotlinx-serialization принимает одиночный суррогат как обычный символ.
 */
internal object JsonText {
    /** В ключах или строковых значениях [element] есть суррогат без пары. */
    fun hasUnpairedSurrogate(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) -> hasUnpairedSurrogate(key) || hasUnpairedSurrogate(value) }
        is JsonArray -> element.any(::hasUnpairedSurrogate)
        is JsonPrimitive -> element.isString && hasUnpairedSurrogate(element.content)
    }

    fun hasUnpairedSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i += 2
                c.isSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }
}
