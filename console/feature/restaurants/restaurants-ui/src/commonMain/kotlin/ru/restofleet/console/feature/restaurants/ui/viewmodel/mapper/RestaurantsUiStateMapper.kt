package ru.restofleet.console.feature.restaurants.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.restaurants.domain.FilterRestaurants
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantRowUi
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsSession
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsUiState

class RestaurantsUiStateMapper(private val filter: FilterRestaurants = FilterRestaurants()) {
    fun map(s: RestaurantsSession): RestaurantsUiState {
        val rows = filter(s.all, s.query).map {
            RestaurantRowUi(it.id, "${it.name} · ${it.code}", listOf(it.city, it.address).filter { f -> f.isNotBlank() }.joinToString(", "))
        }
        return RestaurantsUiState(
            query = s.query,
            rows = rows,
            loading = s.loading,
            errorText = s.error?.let { if (it == ConsoleError.Network) "Нет связи с сервером" else "Не удалось загрузить рестораны" },
            emptyText = if (!s.loading && s.error == null && rows.isEmpty()) "Ничего не найдено" else null,
        )
    }
}
