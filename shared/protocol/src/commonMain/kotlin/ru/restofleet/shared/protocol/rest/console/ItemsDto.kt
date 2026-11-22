package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/** Список в ответе: `{ "items": [...] }` — место для пагинации без ломки клиентов (`limit`/`offset` в запросе). */
@Serializable
data class ItemsDto<T>(val items: List<T>)
