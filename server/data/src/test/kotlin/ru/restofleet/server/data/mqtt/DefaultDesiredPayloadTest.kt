package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.ShadowJson
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto

/** Тень нового устройства в том виде, как её публикует [HiveMqPublisher], разбирается DTO агента. */
class DefaultDesiredPayloadTest {

    @Test
    fun `default desired encodes into a document the agent dto accepts`() {
        val body = JsonObject(mapOf("version" to JsonPrimitive(1)) + ShadowJson.toJson(DefaultDesired.document))

        val dto = RfJson.decodeFromJsonElement(ShadowDesiredDto.serializer(), body)

        assertThat(dto.version).isEqualTo(1)
        assertThat(dto.telemetry.intervalSec).isGreaterThan(0)
    }
}
