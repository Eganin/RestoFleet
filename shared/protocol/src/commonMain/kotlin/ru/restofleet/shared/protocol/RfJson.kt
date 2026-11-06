package ru.restofleet.shared.protocol

import kotlinx.serialization.json.Json

/**
 * Единая конфигурация JSON протокола.
 * - `ignoreUnknownKeys` — новые поля от более новой стороны не ломают старую;
 * - `explicitNulls = false` — отсутствующие значения не пишутся (канонический вид фикстур);
 * - `classDiscriminator = "type"` — полиморфные события различаются полем `type`.
 */
val RfJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}
