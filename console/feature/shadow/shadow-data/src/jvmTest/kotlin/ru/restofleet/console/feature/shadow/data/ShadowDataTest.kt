package ru.restofleet.console.feature.shadow.data

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
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

class ShadowDataTest {
    private val log = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private fun repo(fixture: String = "rest-console-shadow", status: HttpStatusCode = HttpStatusCode.OK) = ShadowRepositoryImpl(
        ShadowApi(
            MockHttp.client { req ->
                log += req.method.value + " " + req.url.encodedPath
                bodies += String(req.body.toByteArray())
                if (status == HttpStatusCode.OK) json(Fixtures.text(fixture)) else respond("", status)
            },
        ),
        Clock { 99L },
    )

    @Test
    fun `load maps documents, versions and delta`() = runTest {
        val shadow = (repo().load("d1") as Outcome.Success).value

        assertThat(log.single()).isEqualTo("GET /api/v1/devices/d1/shadow")
        assertThat(shadow.desiredVersion).isEqualTo(13L)
        assertThat(shadow.reportedVersion).isEqualTo(12L)
        assertThat(shadow.pending).isTrue()
        assertThat(shadow.loadedAt).isEqualTo(99L)
        assertThat(shadow.published).isNull()
        assertThat(shadow.delta["telemetry"]).isInstanceOf(ShadowValue.Obj::class.java)
        assertThat(shadow.desired["watchedApps"]).isEqualTo(ShadowValue.List(listOf(ShadowValue.Str("com.android.chrome"))))
        assertThat(shadow.reported).isNotNull()
    }

    @Test
    fun `put sends the patch as a json object and returns the new state`() = runTest {
        val patch = ShadowDocument(mapOf("telemetry" to ShadowValue.Obj(mapOf("intervalSec" to ShadowValue.Num(20)))))

        val shadow = (repo("rest-console-shadow-published").put("d1", patch) as Outcome.Success).value

        assertThat(log.single()).isEqualTo("PUT /api/v1/devices/d1/shadow")
        assertThat(bodies.single()).isEqualTo("""{"telemetry":{"intervalSec":20}}""")
        assertThat(shadow.published).isNotNull()
    }

    @Test
    fun `errors are mapped`() = runTest {
        assertThat(repo(status = HttpStatusCode.NotFound).load("x")).isEqualTo(Outcome.Failure(ConsoleError.NotFound))
        val bad = repo(status = HttpStatusCode.BadRequest).put("x", ShadowDocument.EMPTY)
        assertThat((bad as Outcome.Failure).error).isInstanceOf(ConsoleError.Validation::class.java)
    }

    @Test
    fun `codec renders and parses, broken json is an input error`() {
        val codec = ShadowJsonCodec()
        val doc = (codec.parse("""{"a":{"b":1},"l":["x"]}""") as Outcome.Success).value
        assertThat(codec.render(doc)).isEqualTo("""{"a":{"b":1},"l":["x"]}""")
        assertThat((codec.parse("{") as Outcome.Failure).error).isInstanceOf(ConsoleError.InvalidInput::class.java)
        val notObject = (codec.parse("[1]") as Outcome.Failure).error as ConsoleError.InvalidInput
        assertThat(notObject.field).isEqualTo("json")
    }
}
