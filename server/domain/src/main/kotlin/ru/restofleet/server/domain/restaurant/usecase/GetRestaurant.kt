package ru.restofleet.server.domain.restaurant.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.shared.kernel.Outcome

/** Карточка ресторана. */
class GetRestaurant(
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: RestaurantId): Outcome<Restaurant, ServerError> =
        tx.inTransaction { restaurants.findById(id) }
            ?.let { Outcome.Success(it) }
            ?: Outcome.Failure(ServerError.NotFound("restaurant", id.value))
}
