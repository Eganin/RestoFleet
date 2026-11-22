package ru.restofleet.console.feature.commands.data

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
import ru.restofleet.console.feature.commands.domain.CommandState
import ru.restofleet.console.feature.commands.domain.CommandType
import ru.restofleet.shared.kernel.Outcome

class CommandsRepositoryTest {
    private val log = mutableListOf<String>()
    private var bodies = mutableListOf<String>()

    private fun repo(status: HttpStatusCode = HttpStatusCode.OK) = CommandsRepositoryImpl(
        CommandsApi(
            MockHttp.client { req ->
                log += req.method.value + " " + req.url.encodedPath + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                bodies += String(req.body.toByteArray())
                if (status != HttpStatusCode.OK) return@client respond("", status)
                when {
                    req.url.encodedPath.endsWith("/commands") && req.method.value == "GET" ->
                        json("""{"items":[${Fixtures.text("rest-console-command")},${Fixtures.text("rest-console-command-failed")}]}""")
                    req.url.encodedPath.contains("/commands") -> json(Fixtures.text("rest-console-command"))
                    else -> json(Fixtures.text("rest-console-device-details"))
                }
            },
        ),
    )

    @Test
    fun `mode comes from the device card`() = runTest {
        assertThat(repo().mode("d1")).isEqualTo(Outcome.Success("normal"))
        assertThat(log.single()).isEqualTo("GET /api/v1/devices/d1")
    }

    @Test
    fun `send posts type and string params`() = runTest {
        val record = (repo().send("d1", CommandType.ShowMessage, mapOf("text" to "Привет")) as Outcome.Success).value

        assertThat(log.single()).isEqualTo("POST /api/v1/devices/d1/commands")
        assertThat(bodies.single()).contains("\"type\":\"show_message\"")
        assertThat(bodies.single()).contains("\"text\":\"Привет\"")
        assertThat(record.state).isEqualTo(CommandState.Succeeded)
        assertThat(record.deliveredAt).isEqualTo(1795334400400L)
        assertThat(record.result).containsExactly("shown", "true")
    }

    @Test
    fun `history and status are mapped`() = runTest {
        val repo = repo()
        val history = (repo.history("d1", 20) as Outcome.Success).value
        assertThat(history.map { it.state }).containsExactly(CommandState.Succeeded, CommandState.Expired).inOrder()
        assertThat(history[1].reason).isEqualTo("expired")
        assertThat(history[1].result).isEmpty()
        assertThat(log.first()).isEqualTo("GET /api/v1/devices/d1/commands?limit=20")

        assertThat((repo.status("c-0b1f6c2a") as Outcome.Success).value.type).isEqualTo("show_message")
        assertThat(log.last()).isEqualTo("GET /api/v1/commands/c-0b1f6c2a")
    }

    @Test
    fun `server errors are mapped`() = runTest {
        val conflict = repo(HttpStatusCode.Conflict).send("d1", CommandType.Ping, emptyMap())
        assertThat(conflict).isEqualTo(Outcome.Failure(ConsoleError.Conflict(null)))
        assertThat(repo(HttpStatusCode.NotFound).status("x")).isEqualTo(Outcome.Failure(ConsoleError.NotFound))
    }
}
