package ru.restofleet.console.feature.restaurants.data

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.console.core.testing.MockHttp
import ru.restofleet.console.core.testing.MockHttp.json
import ru.restofleet.shared.kernel.Outcome

class RestaurantsRepositoryTest {
    @Test
    fun `maps restaurants and sorts them by name`() = runTest {
        val fixture = Fixtures.text("rest-console-restaurant")
        val second = fixture.replace("Северная кухня", "Арбат").replace("MSK-01", "MSK-09")
        val repo = RestaurantsRepositoryImpl(RestaurantsApi(MockHttp.client { json("""{"items":[$fixture,$second]}""") }))

        val list = (repo.list() as Outcome.Success).value

        assertThat(list.map { it.name }).containsExactly("Арбат", "Северная кухня").inOrder()
        assertThat(list.last().city).isEqualTo("Москва")
        assertThat(list.last().code).isEqualTo("MSK-01")
    }

    @Test
    fun `failures are mapped`() = runTest {
        val repo = RestaurantsRepositoryImpl(RestaurantsApi(MockHttp.client { respond("", HttpStatusCode.Unauthorized) }))
        assertThat(repo.list()).isEqualTo(Outcome.Failure(ConsoleError.Unauthorized))
    }
}
