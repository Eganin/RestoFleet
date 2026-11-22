package ru.restofleet.console.feature.restaurants.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.console.feature.restaurants.domain.Restaurant
import ru.restofleet.console.feature.restaurants.domain.RestaurantsRepository
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.RestaurantDto

class RestaurantsApi(private val client: HttpClient) {
    suspend fun list(): Outcome<ItemsDto<RestaurantDto>, ConsoleError> = apiCall { client.get(ConsolePaths.RESTAURANTS) }
}

class RestaurantsRepositoryImpl(private val api: RestaurantsApi) : RestaurantsRepository {
    override suspend fun list(): Outcome<List<Restaurant>, ConsoleError> = api.list().map { dto ->
        dto.items.map { Restaurant(it.id, it.code, it.name, it.city, it.address) }.sortedBy { it.name }
    }
}
