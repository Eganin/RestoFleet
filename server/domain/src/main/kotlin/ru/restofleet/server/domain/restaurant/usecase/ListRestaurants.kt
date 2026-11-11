package ru.restofleet.server.domain.restaurant.usecase

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantRepository

/** Все рестораны, упорядоченные по `code`. */
class ListRestaurants(
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(): List<Restaurant> = tx.inTransaction { restaurants.list() }
}
