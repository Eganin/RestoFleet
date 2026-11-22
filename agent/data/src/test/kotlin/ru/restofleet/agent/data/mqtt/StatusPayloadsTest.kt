package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.agent.data.mqtt.mapping.Fixtures
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.shared.protocol.RfJson

class StatusPayloadsTest {
    private val facts = DeviceFacts("sdk_gphone64_arm64", "Google", "16", 36)

    private fun payloads(deviceOwner: Boolean = false, bootId: String? = "b7f2c1d0") =
        StatusPayloads(facts, "0.1.0-mvp", { deviceOwner }, { bootId })

    private fun json(bytes: ByteArray) = RfJson.parseToJsonElement(bytes.decodeToString()) as JsonObject

    @Test
    fun onlineMatchesFixtureExceptUnknownRole() {
        val bytes = payloads().online("k-7f3a9c", 1_793_865_600_000)

        val expected = JsonObject((Fixtures.json("status-online") as JsonObject).filterKeys { it != "role" })
        assertThat(json(bytes)).isEqualTo(expected)
    }

    @Test
    fun onlineWithRoleFromProvisioningMatchesTheFixture() {
        // Реш. 210: роль из ответа /provision хранится с учёткой и уходит в status.role
        val bytes = payloads().online("k-7f3a9c", 1_793_865_600_000, DeviceRole.KITCHEN)

        assertThat(json(bytes)).isEqualTo(Fixtures.json("status-online"))
    }

    @Test
    fun deviceOwnerModeIsReported() {
        assertThat(json(payloads(deviceOwner = true).online("k-1", 1))["mode"])
            .isEqualTo(JsonPrimitive("device_owner"))
    }

    @Test
    fun unknownBootIdIsOmitted() {
        assertThat(json(payloads(bootId = null).online("k-1", 1)).containsKey("bootId")).isFalse()
    }

    @Test
    fun offlineOnStopKeepsConnIdAndReason() {
        val bytes = payloads().offline("k-9", 42, "stop")

        assertThat(json(bytes)).isEqualTo(
            JsonObject(
                mapOf(
                    "state" to JsonPrimitive("offline"),
                    "ts" to JsonPrimitive(42),
                    "connId" to JsonPrimitive("k-9"),
                    "reason" to JsonPrimitive("stop"),
                ),
            ),
        )
    }
}
