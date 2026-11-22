package ru.restofleet.console.feature.restaurants.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class RestaurantsTest {
    private val all = listOf(
        Restaurant("1", "MSK-01", "Северная кухня", "Москва", "ул. Учебная, 10"),
        Restaurant("2", "SPB-02", "Пять специй", "Санкт-Петербург", "Невский, 1"),
    )

    @Test
    fun `search table`() {
        val filter = FilterRestaurants()
        assertThat(filter(all, "")).isEqualTo(all)
        assertThat(filter(all, "   ")).isEqualTo(all)
        assertThat(filter(all, "северная").map { it.id }).containsExactly("1")
        assertThat(filter(all, "SPB").map { it.id }).containsExactly("2")
        assertThat(filter(all, "москва").map { it.id }).containsExactly("1")
        assertThat(filter(all, "невск").map { it.id }).containsExactly("2")
        assertThat(filter(all, "zzz")).isEmpty()
    }

    @Test
    fun `load passes through the repository result`() = runTest {
        val ok = LoadRestaurants(object : RestaurantsRepository {
            override suspend fun list() = Outcome.Success(all)
        })
        assertThat(ok()).isEqualTo(Outcome.Success(all))
        val bad = LoadRestaurants(object : RestaurantsRepository {
            override suspend fun list() = Outcome.Failure(ConsoleError.Network)
        })
        assertThat(bad()).isEqualTo(Outcome.Failure(ConsoleError.Network))
    }
}
