package ru.restofleet.console.feature.restaurants.domain

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Ресторан в списке (FR-CN-03, только просмотр). */
data class Restaurant(val id: String, val code: String, val name: String, val city: String, val address: String)

interface RestaurantsRepository {
    suspend fun list(): Outcome<List<Restaurant>, ConsoleError>
}

class LoadRestaurants(private val repository: RestaurantsRepository) {
    suspend operator fun invoke(): Outcome<List<Restaurant>, ConsoleError> = repository.list()
}

/** Поиск по названию, коду, городу и адресу без учёта регистра; пустой запрос возвращает всё. */
class FilterRestaurants {
    operator fun invoke(all: List<Restaurant>, query: String): List<Restaurant> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) {
            all
        } else {
            all.filter { listOf(it.name, it.code, it.city, it.address).any { f -> q in f.lowercase() } }
        }
    }
}
