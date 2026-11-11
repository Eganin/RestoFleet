package ru.restofleet.server.domain.restaurant.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.RestaurantPatch
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.server.domain.restaurant.UpsertedRestaurant
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map

/**
 * Dev-seed стенда (`1-coursework.md` 4.5): ресторан с таким `code` обновляется, иначе создаётся — через **те же**
 * [CreateRestaurant] и [UpdateRestaurant], что и REST API.
 */
class UpsertRestaurantByCode(
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
    private val create: CreateRestaurant,
    private val update: UpdateRestaurant,
) {
    suspend operator fun invoke(draft: RestaurantDraft): Outcome<UpsertedRestaurant, ServerError> {
        val existing = tx.inTransaction { restaurants.findByCode(draft.code.trim()) }
        return if (existing == null) {
            create(draft).map { UpsertedRestaurant(it, created = true) }
        } else {
            val patch = RestaurantPatch(
                name = draft.name,
                city = draft.city,
                address = draft.address,
                timezone = draft.timezone,
            )
            update(existing.id, patch).map { UpsertedRestaurant(it, created = false) }
        }
    }
}
