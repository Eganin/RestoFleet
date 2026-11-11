package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantRepository

/** `restaurants` (Exposed DSL). Вызывается внутри транзакции [ExposedTransactionRunner]. */
internal class ExposedRestaurantRepository : RestaurantRepository {
    override suspend fun insert(restaurant: Restaurant) {
        RestaurantsTable.insert {
            it[id] = restaurant.id.value.toUuid()
            it[code] = restaurant.code
            it[name] = restaurant.name
            it[city] = restaurant.city
            it[address] = restaurant.address
            it[timezone] = restaurant.timezone
            it[createdAt] = restaurant.createdAt.toTimestamp()
            it[updatedAt] = restaurant.updatedAt.toTimestamp()
        }
    }

    override suspend fun update(restaurant: Restaurant) {
        RestaurantsTable.update({ RestaurantsTable.id eq restaurant.id.value.toUuid() }) {
            it[code] = restaurant.code
            it[name] = restaurant.name
            it[city] = restaurant.city
            it[address] = restaurant.address
            it[timezone] = restaurant.timezone
            it[updatedAt] = restaurant.updatedAt.toTimestamp()
        }
    }

    override suspend fun findById(id: RestaurantId): Restaurant? {
        val uuid = id.value.toUuidOrNull() ?: return null
        return RestaurantsTable.selectAll().where { RestaurantsTable.id eq uuid }.singleOrNull()?.toRestaurant()
    }

    override suspend fun findByCode(code: String): Restaurant? =
        RestaurantsTable.selectAll().where { RestaurantsTable.code eq code }.singleOrNull()?.toRestaurant()

    override suspend fun list(): List<Restaurant> =
        RestaurantsTable.selectAll().orderBy(RestaurantsTable.code, SortOrder.ASC).map { it.toRestaurant() }

    private fun ResultRow.toRestaurant() = Restaurant(
        id = RestaurantId(this[RestaurantsTable.id].toString()),
        code = this[RestaurantsTable.code],
        name = this[RestaurantsTable.name],
        city = this[RestaurantsTable.city],
        address = this[RestaurantsTable.address],
        timezone = this[RestaurantsTable.timezone],
        createdAt = this[RestaurantsTable.createdAt].toMillis(),
        updatedAt = this[RestaurantsTable.updatedAt].toMillis(),
    )
}
