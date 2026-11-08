package ru.restofleet.agent.data.mqtt.mapping

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import ru.restofleet.shared.protocol.RfJson
import java.io.File

/** Golden-фикстуры протокола (`docs/protocol/fixtures`), путь задаёт Gradle через `fixturesDir`. */
internal object Fixtures {
    private val dir = File(requireNotNull(System.getProperty("fixturesDir")) { "fixturesDir is not set" })

    fun text(name: String): String = File(dir, "$name.json").readText()

    fun json(name: String): JsonElement = RfJson.parseToJsonElement(text(name))

    inline fun <reified T> decode(name: String, serializer: KSerializer<T>): T =
        RfJson.decodeFromString(serializer, text(name))
}

/** Кодирует DTO как на проводе и сравнивает с фикстурой как JSON-деревом, а не строкой. */
internal fun <T> assertMatchesFixture(name: String, serializer: KSerializer<T>, dto: T) {
    assertThat(RfJson.encodeToJsonElement(serializer, dto)).isEqualTo(Fixtures.json(name))
}
