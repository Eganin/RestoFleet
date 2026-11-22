package ru.restofleet.shared.shadow

/**
 * Значение узла тени — JSON без сериализации (реш. 224). Числа хранятся как [Double] и сравниваются по значению
 * (`45` и `45.0` равны); `null` в модели нет — отсутствующий ключ и есть «нет значения» (каноническая форма протокола,
 * `explicitNulls = false`).
 */
sealed interface ShadowValue {
    data class Num(val value: Double) : ShadowValue {
        constructor(value: Int) : this(value.toDouble())
        constructor(value: Long) : this(value.toDouble())
    }

    data class Str(val value: String) : ShadowValue

    data class Bool(val value: Boolean) : ShadowValue

    /** Список сравнивается целиком, с учётом порядка. */
    data class List(val items: kotlin.collections.List<ShadowValue>) : ShadowValue

    data class Obj(val fields: Map<String, ShadowValue>) : ShadowValue
}

/** Документ тени (`desired` или `reported`) — корневой объект «ключ → значение». */
data class ShadowDocument(val fields: Map<String, ShadowValue> = emptyMap()) {
    val isEmpty: Boolean get() = fields.isEmpty()

    operator fun get(key: String): ShadowValue? = fields[key]

    companion object {
        val EMPTY = ShadowDocument()
    }
}

/** Служебные ключи корня тени: в `delta` не участвуют (ТЗ 7.2). */
object ShadowKeys {
    const val VERSION = "version"
    const val APPLIED_VERSION = "appliedVersion"
    const val APPLIED_AT = "appliedAt"
    const val ERRORS = "errors"
    val METADATA: Set<String> = setOf(VERSION, APPLIED_VERSION, APPLIED_AT, ERRORS)
}
