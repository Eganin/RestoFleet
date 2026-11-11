package ru.restofleet.server.data.mapper

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue
import java.io.File
import kotlin.test.assertFailsWith

class ShadowJsonTest {

    @Test
    fun `integer numbers are written without fraction and fractional ones as is`() {
        val doc = ShadowDocument(
            mapOf(
                "int" to ShadowValue.Num(45),
                "neg" to ShadowValue.Num(-3),
                "frac" to ShadowValue.Num(45.5),
                "ms" to ShadowValue.Num(1_762_000_000_123L),
            ),
        )

        assertThat(ShadowJson.encode(doc)).isEqualTo("""{"int":45,"neg":-3,"frac":45.5,"ms":1762000000123}""")
    }

    @Test
    fun `numbers beyond 2^53 keep the double form instead of a lossy long`() {
        val big = 2.0e16

        val text = ShadowJson.encode(ShadowDocument(mapOf("big" to ShadowValue.Num(big))))

        assertThat(ShadowJson.decode(text)["big"]).isEqualTo(ShadowValue.Num(big))
        assertThat(text).contains("E16")
    }

    @Test
    fun `round trip keeps every value type and nesting`() {
        val doc = ShadowDocument(
            mapOf(
                "s" to ShadowValue.Str("x"),
                "b" to ShadowValue.Bool(false),
                "l" to ShadowValue.List(listOf(ShadowValue.Num(1), ShadowValue.Str("a"))),
                "o" to ShadowValue.Obj(mapOf("inner" to ShadowValue.Obj(mapOf("n" to ShadowValue.Num(0.25))))),
            ),
        )

        assertThat(ShadowJson.decode(ShadowJson.encode(doc))).isEqualTo(doc)
    }

    @Test
    fun `json null is an absent key and is skipped inside arrays`() {
        val doc = ShadowJson.decode("""{"a":null,"b":[1,null,2],"c":{"d":null}}""")

        assertThat(doc.fields.keys).containsExactly("b", "c")
        assertThat(doc["b"]).isEqualTo(ShadowValue.List(listOf(ShadowValue.Num(1), ShadowValue.Num(2))))
        assertThat(doc["c"]).isEqualTo(ShadowValue.Obj(emptyMap()))
    }

    @Test
    fun `numeric strings stay strings and booleans stay booleans`() {
        val doc = ShadowJson.decode("""{"s":"45","t":"true","b":true}""")

        assertThat(doc["s"]).isEqualTo(ShadowValue.Str("45"))
        assertThat(doc["t"]).isEqualTo(ShadowValue.Str("true"))
        assertThat(doc["b"]).isEqualTo(ShadowValue.Bool(true))
    }

    @Test
    fun `non finite numbers are dropped so that jsonb never receives invalid json`() {
        val doc = ShadowJson.decode("""{"huge":1e400,"ok":1,"list":[1e400,2]}""")

        assertThat(doc.fields.keys).containsExactly("ok", "list")
        assertThat(doc["list"]).isEqualTo(ShadowValue.List(listOf(ShadowValue.Num(2))))
        assertThat(ShadowJson.encode(doc)).isEqualTo("""{"ok":1,"list":[2]}""")
    }

    @Test
    fun `decode rejects anything but an object`() {
        assertFailsWith<IllegalArgumentException> { ShadowJson.decode("[1,2]") }
        assertFailsWith<IllegalArgumentException> { ShadowJson.decode("\"x\"") }
    }

    @Test
    fun `default desired encodes into a document the agent dto accepts`() {
        val body = JsonObject(mapOf("version" to JsonPrimitive(1)) + ShadowJson.toJson(DefaultDesired.document))

        val dto = RfJson.decodeFromJsonElement(ShadowDesiredDto.serializer(), body)

        assertThat(dto.version).isEqualTo(1)
        assertThat(dto.telemetry.intervalSec).isGreaterThan(0)
    }

    @Test
    fun `golden desired fixture survives decode and encode without changing meaning`() {
        val fixture = File(System.getProperty("fixturesDir"), "shadow-desired.json").readText()

        val doc = ShadowJson.decode(fixture)
        val again = ShadowJson.decode(ShadowJson.encode(doc))

        assertThat(again).isEqualTo(doc)
        // 45.0 пишется как 45 — для DTO агента это то же значение
        assertThat(RfJson.decodeFromString(ShadowDesiredDto.serializer(), ShadowJson.encode(doc)))
            .isEqualTo(RfJson.decodeFromString(ShadowDesiredDto.serializer(), fixture))
    }
}
