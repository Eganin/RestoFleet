package ru.restofleet.server.domain.restaurant

import ru.restofleet.server.domain.common.ServerError
import java.time.DateTimeException
import java.time.ZoneId

/** Правила полей ресторана: первая нарушенная — ошибка [ServerError.Validation]. */
internal object RestaurantRules {
    private val CODE = Regex("^[A-Za-z0-9_-]{1,32}$")
    private const val MAX_TEXT = 200

    fun validate(draft: RestaurantDraft): ServerError.Validation? = when {
        !CODE.matches(draft.code) -> ServerError.Validation("code", "invalid_format")
        draft.name.isBlank() -> ServerError.Validation("name", "required")
        draft.name.length > MAX_TEXT -> ServerError.Validation("name", "too_long")
        draft.city.isBlank() -> ServerError.Validation("city", "required")
        draft.city.length > MAX_TEXT -> ServerError.Validation("city", "too_long")
        draft.address.length > MAX_TEXT -> ServerError.Validation("address", "too_long")
        !isZone(draft.timezone) -> ServerError.Validation("timezone", "unknown_zone")
        else -> null
    }

    fun normalize(draft: RestaurantDraft): RestaurantDraft = draft.copy(
        code = draft.code.trim(),
        name = draft.name.trim(),
        city = draft.city.trim(),
        address = draft.address.trim(),
        timezone = draft.timezone.trim(),
    )

    private fun isZone(zone: String): Boolean = try {
        ZoneId.of(zone)
        true
    } catch (_: DateTimeException) {
        false
    }
}
