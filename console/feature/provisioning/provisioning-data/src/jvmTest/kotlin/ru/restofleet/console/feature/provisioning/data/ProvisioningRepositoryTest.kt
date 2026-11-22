package ru.restofleet.console.feature.provisioning.data

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.console.core.testing.MockHttp
import ru.restofleet.console.core.testing.MockHttp.json
import ru.restofleet.console.feature.provisioning.domain.DeviceRole
import ru.restofleet.console.feature.provisioning.domain.ProvisioningRequest
import ru.restofleet.shared.kernel.Outcome

class ProvisioningRepositoryTest {
    private val log = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private fun repo(status: HttpStatusCode = HttpStatusCode.OK) = ProvisioningRepositoryImpl(
        ProvisioningApi(
            MockHttp.client { req ->
                log += req.method.value + " " + req.url.encodedPath
                bodies += String(req.body.toByteArray())
                when {
                    status != HttpStatusCode.OK -> respond("", status)
                    req.method.value == "POST" -> json(Fixtures.text("rest-console-issued-token"), HttpStatusCode.Created)
                    else -> json("""{"items":[${Fixtures.text("rest-console-restaurant")}]}""")
                }
            },
        ),
    )

    @Test
    fun `restaurants become labelled choices`() = runTest {
        val choices = (repo().restaurants() as Outcome.Success).value
        assertThat(choices.single().label).isEqualTo("Северная кухня · MSK-01")
        assertThat(log.single()).isEqualTo("GET /api/v1/restaurants")
    }

    @Test
    fun `issue posts the request and maps the one-time code`() = runTest {
        val code = (repo().issue(ProvisioningRequest("r1", DeviceRole.Kitchen, "Кухня 3")) as Outcome.Success).value

        assertThat(log.single()).isEqualTo("POST /api/v1/provisioning/tokens")
        assertThat(bodies.single()).isEqualTo("""{"restaurantId":"r1","role":"kitchen","name":"Кухня 3"}""")
        assertThat(code.code).isEqualTo("0000EXAMPLE0000EXAMPLE0000")
        assertThat(code.role).isEqualTo(DeviceRole.Kitchen)
        assertThat(code.expiresAt).isEqualTo(1795420800000L)
        assertThat(code.toString()).doesNotContain("EXAMPLE")
    }

    @Test
    fun `unknown role in the answer falls back to the requested one, name may be absent`() = runTest {
        val body = Fixtures.text("rest-console-issued-token").replace("\"kitchen\"", "\"future\"")
        val repo = ProvisioningRepositoryImpl(ProvisioningApi(MockHttp.client { json(body) }))

        val code = (repo.issue(ProvisioningRequest("r1", DeviceRole.Hall, null)) as Outcome.Success).value

        assertThat(code.role).isEqualTo(DeviceRole.Hall)
        assertThat(bodies).isEmpty()
    }

    @Test
    fun `errors are mapped`() = runTest {
        assertThat(repo(HttpStatusCode.Forbidden).issue(ProvisioningRequest("r", DeviceRole.Hall, null))).isEqualTo(Outcome.Failure(ConsoleError.Forbidden))
        assertThat(repo(HttpStatusCode.TooManyRequests).restaurants()).isEqualTo(Outcome.Failure(ConsoleError.RateLimited(null)))
    }
}
