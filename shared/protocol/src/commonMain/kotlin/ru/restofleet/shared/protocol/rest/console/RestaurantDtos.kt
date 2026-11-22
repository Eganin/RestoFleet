package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/** Ресторан (FR-BE-01); в Android-консоли — только просмотр (FR-CN-03). */
@Serializable
data class RestaurantDto(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val address: String,
    val timezone: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** `POST /restaurants`. */
@Serializable
data class CreateRestaurantRequestDto(
    val code: String,
    val name: String,
    val city: String,
    val address: String = "",
    val timezone: String,
)

/** `PATCH /restaurants/{id}`: отсутствующее поле не меняется. */
@Serializable
data class UpdateRestaurantRequestDto(
    val code: String? = null,
    val name: String? = null,
    val city: String? = null,
    val address: String? = null,
    val timezone: String? = null,
)

/** Ссылка на ресторан внутри тревог, инцидентов и сводки: достаточно для строки списка без второго запроса. */
@Serializable
data class RestaurantRefDto(
    val id: String,
    val code: String,
    val name: String,
)
