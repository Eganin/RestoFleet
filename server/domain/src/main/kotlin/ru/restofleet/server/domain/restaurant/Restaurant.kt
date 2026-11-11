package ru.restofleet.server.domain.restaurant

/** Идентификатор ресторана (UUID строкой). */
@JvmInline
value class RestaurantId(val value: String)

/** Ресторан сети (FR-BE-01, `1-coursework.md` 4.4). [code] уникален; по нему же идёт upsert dev-seed. */
data class Restaurant(
    val id: RestaurantId,
    val code: String,
    val name: String,
    val city: String,
    val address: String,
    /** IANA-зона, например `Europe/Moscow` (рабочие часы — Д). */
    val timezone: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Поля нового ресторана (`POST /restaurants`, seed). */
data class RestaurantDraft(
    val code: String,
    val name: String,
    val city: String,
    val address: String,
    val timezone: String,
)

/** Частичное изменение (`PATCH /restaurants/{id}`): `null` — поле не меняется. */
data class RestaurantPatch(
    val code: String? = null,
    val name: String? = null,
    val city: String? = null,
    val address: String? = null,
    val timezone: String? = null,
)

/** Порт хранилища ресторанов. Только внутри транзакции ([ru.restofleet.server.domain.common.TransactionRunner]). */
interface RestaurantRepository {
    suspend fun insert(restaurant: Restaurant)

    suspend fun update(restaurant: Restaurant)

    suspend fun findById(id: RestaurantId): Restaurant?

    suspend fun findByCode(code: String): Restaurant?

    /** Все рестораны по `code` (их десятки, К); пагинация — в Д. */
    suspend fun list(): List<Restaurant>
}

/** Итог upsert: ресторан и был ли он создан. */
data class UpsertedRestaurant(val restaurant: Restaurant, val created: Boolean)
