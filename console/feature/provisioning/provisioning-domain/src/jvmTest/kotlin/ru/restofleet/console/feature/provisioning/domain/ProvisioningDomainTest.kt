package ru.restofleet.console.feature.provisioning.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class ProvisioningDomainTest {
    private class Repo : ProvisioningRepository {
        var request: ProvisioningRequest? = null
        override suspend fun restaurants() = Outcome.Success(listOf(RestaurantChoice("r1", "Сокол · MSK-01")))
        override suspend fun issue(request: ProvisioningRequest): Outcome<IssuedCode, ConsoleError> {
            this.request = request
            return Outcome.Success(IssuedCode("t1", "SECRET-CODE", request.restaurantId, request.role, request.name, 1_000L))
        }
    }

    @Test
    fun `roles use contract wire names`() {
        assertThat(DeviceRole.entries.map { it.wire }).containsExactly("kitchen", "hall", "assembly").inOrder()
    }

    @Test
    fun `issued code never appears in toString`() {
        val code = IssuedCode("t1", "SECRET-CODE", "r1", DeviceRole.Hall, null, 1L)
        assertThat(code.toString()).doesNotContain("SECRET-CODE")
        assertThat(code.toString()).contains("t1")
    }

    @Test
    fun `restaurants are delegated`() = runTest {
        assertThat((LoadRestaurantChoices(Repo())() as Outcome.Success).value.single().label).isEqualTo("Сокол · MSK-01")
    }

    @Test
    fun `issue validates before the request, trims and nulls an empty name`() = runTest {
        val repo = Repo()
        val issue = IssueCode(repo)

        assertThat(issue(null, DeviceRole.Hall, "x")).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("restaurant", "empty")))
        assertThat(issue("r1", null, "x")).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("role", "empty")))
        assertThat(issue("r1", DeviceRole.Hall, "x".repeat(101))).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("name", "too_long")))
        assertThat(repo.request).isNull()

        issue("r1", DeviceRole.Kitchen, "  Кухня 3 ")
        assertThat(repo.request).isEqualTo(ProvisioningRequest("r1", DeviceRole.Kitchen, "Кухня 3"))
        issue("r1", DeviceRole.Hall, "   ")
        assertThat(repo.request!!.name).isNull()
        assertThat(issue("r1", DeviceRole.Hall, "x".repeat(100))).isInstanceOf(Outcome.Success::class.java)
    }

    private fun TestScope.clock() = Clock { currentTime }

    @Test
    fun `countdown ticks every second down to zero and stops`() = runTest {
        ObserveCodeCountdown(clock())(3_000L).test {
            assertThat(awaitItem()).isEqualTo(3_000L)
            assertThat(awaitItem()).isEqualTo(2_000L)
            assertThat(awaitItem()).isEqualTo(1_000L)
            assertThat(awaitItem()).isEqualTo(0L)
            awaitComplete()
        }
        assertThat(currentTime).isEqualTo(3_000L)
    }

    @Test
    fun `countdown of an already expired code is a single zero`() = runTest {
        ObserveCodeCountdown(Clock { 10_000L })(5_000L).test {
            assertThat(awaitItem()).isEqualTo(0L)
            awaitComplete()
        }
    }
}
