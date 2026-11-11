package ru.restofleet.shared.shadow

/**
 * Значения по умолчанию полей элементов списков корня тени (V-2, реш. 305): отсутствующее поле равно умолчанию.
 * Источник — контракт протокола (`ProbeTargetDto`: `reference = false`; `expectedStatus = null` — отсутствие ключа),
 * их совпадение проверяет тест `shared:protocol`. Без этого цель пробы без `reference` в `desired` и эхо агента с
 * `reference: false` давали вечную непустую дельту.
 */
object ShadowDefaults {
    val LIST_ITEMS: Map<String, Map<String, ShadowValue>> = mapOf(
        "probes" to mapOf("reference" to ShadowValue.Bool(false)),
    )
}
