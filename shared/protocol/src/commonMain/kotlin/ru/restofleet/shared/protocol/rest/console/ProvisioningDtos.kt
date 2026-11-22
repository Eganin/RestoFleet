package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/** `POST /provisioning/tokens`: один код; роль и ресторан фиксируются в коде (реш. 210). */
@Serializable
data class IssueTokenRequestDto(val restaurantId: String, val role: String, val name: String? = null)

/** `POST /provisioning/tokens:batch`: 1…1000 кодов (симулятор, КМ4). */
@Serializable
data class IssueTokensBatchRequestDto(
    val restaurantId: String,
    val role: String,
    val name: String? = null,
    val count: Int,
)

/**
 * Выпущенный код: `code` показывается один раз (в БД — SHA-256), TTL 24 ч. Консоль код не сохраняет и не логирует
 * (ПМ8); [toString] его не раскрывает.
 */
@Serializable
data class IssuedTokenDto(
    val id: String,
    val code: String,
    val restaurantId: String,
    val role: String,
    val name: String? = null,
    val expiresAt: Long,
) {
    override fun toString(): String = "IssuedTokenDto(id=$id, code=***, restaurantId=$restaurantId, role=$role)"
}
