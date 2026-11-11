package ru.restofleet.server.domain.restaurant.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantPatch
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.server.domain.restaurant.RestaurantRules
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/** Частичное изменение ресторана (FR-BE-01). Новый `code`, занятый другим рестораном, — [ServerError.Conflict]. */
class UpdateRestaurant(
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: RestaurantId, patch: RestaurantPatch): Outcome<Restaurant, ServerError> =
        tx.transactional {
            val current = restaurants.findById(id)
                ?: return@transactional Outcome.Failure(ServerError.NotFound("restaurant", id.value))
            val draft = RestaurantRules.normalize(
                RestaurantDraft(
                    code = patch.code ?: current.code,
                    name = patch.name ?: current.name,
                    city = patch.city ?: current.city,
                    address = patch.address ?: current.address,
                    timezone = patch.timezone ?: current.timezone,
                ),
            )
            val invalid = RestaurantRules.validate(draft)
            val owner = if (draft.code != current.code) restaurants.findByCode(draft.code) else null
            when {
                invalid != null -> Outcome.Failure(invalid)
                owner != null -> Outcome.Failure(ServerError.Conflict("restaurant_code_taken"))
                else -> {
                    val updated = current.copy(
                        code = draft.code,
                        name = draft.name,
                        city = draft.city,
                        address = draft.address,
                        timezone = draft.timezone,
                        updatedAt = clock.nowMillis(),
                    )
                    restaurants.update(updated)
                    Outcome.Success(updated)
                }
            }
        }
}
