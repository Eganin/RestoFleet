package ru.restofleet.server.domain.restaurant.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.server.domain.restaurant.RestaurantRules
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

/** Новый ресторан (FR-BE-01). Занятый `code` — [ServerError.Conflict]. */
class CreateRestaurant(
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: RestaurantDraft): Outcome<Restaurant, ServerError> {
        val clean = RestaurantRules.normalize(draft)
        RestaurantRules.validate(clean)?.let { return Outcome.Failure(it) }
        return tx.transactional {
            if (restaurants.findByCode(clean.code) != null) {
                Outcome.Failure(ServerError.Conflict("restaurant_code_taken"))
            } else {
                val now = clock.nowMillis()
                val restaurant = Restaurant(
                    id = RestaurantId(ids.newId()),
                    code = clean.code,
                    name = clean.name,
                    city = clean.city,
                    address = clean.address,
                    timezone = clean.timezone,
                    createdAt = now,
                    updatedAt = now,
                )
                restaurants.insert(restaurant)
                Outcome.Success(restaurant)
            }
        }
    }
}
