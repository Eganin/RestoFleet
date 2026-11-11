package ru.restofleet.server.api.http

import kotlinx.serialization.Serializable

/** Список в ответе: `{ "items": [...] }` — место для пагинации без ломки клиентов. */
@Serializable
data class ItemsDto<T>(val items: List<T>)
