package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import ru.restofleet.shared.protocol.rest.ApiErrorDto
import ru.restofleet.shared.protocol.rest.console.AlertActionDto
import ru.restofleet.shared.protocol.rest.console.AlertActions
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.AlertStates
import ru.restofleet.shared.protocol.rest.console.CommandStatuses
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.DeviceRoles
import ru.restofleet.shared.protocol.rest.console.DeviceStatuses
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.IncidentActionDto
import ru.restofleet.shared.protocol.rest.console.IncidentKinds
import ru.restofleet.shared.protocol.rest.console.IssuedTokenDto
import ru.restofleet.shared.protocol.rest.console.LoginRequestDto
import ru.restofleet.shared.protocol.rest.console.LogoutRequestDto
import ru.restofleet.shared.protocol.rest.console.RefreshRequestDto
import ru.restofleet.shared.protocol.rest.console.RuleIds
import ru.restofleet.shared.protocol.rest.console.SendCommandRequestDto
import ru.restofleet.shared.protocol.rest.console.Severities
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

/** REST-контракт консоли (ПМ1, реш. 408): секреты не попадают в `toString`, умолчания, коды и пути. */
class ConsoleRestDtoTest {

    private fun <T> keysOf(serializer: KSerializer<T>, value: T): Set<String> =
        (RfJson.encodeToJsonElement(serializer, value) as JsonObject).keys

    @Test
    fun secretsAreHiddenInToString() {
        val login = LoginRequestDto(login = "support", password = "pw-123456")
        val refresh = RefreshRequestDto(refreshToken = "rt-123456")
        val logout = LogoutRequestDto(refreshToken = "rt-654321")
        val tokens = TokenResponseDto(
            accessToken = "at-111111", accessExpiresAt = 1, refreshToken = "rt-222222", refreshExpiresAt = 2,
        )
        val code = IssuedTokenDto(
            id = "t-1", code = "CODE-333333", restaurantId = "r", role = "kitchen", name = "Кухня", expiresAt = 3,
        )

        assertThat(login.toString()).doesNotContain("pw-123456")
        assertThat(login.toString()).contains("login=support")
        assertThat(refresh.toString()).doesNotContain("rt-123456")
        assertThat(logout.toString()).doesNotContain("rt-654321")
        assertThat(tokens.toString()).doesNotContain("at-111111")
        assertThat(tokens.toString()).doesNotContain("rt-222222")
        assertThat(tokens.toString()).contains("accessExpiresAt=1")
        assertThat(code.toString()).doesNotContain("CODE-333333")
        assertThat(code.toString()).contains("restaurantId=r")
    }

    @Test
    fun tokenTypeDefaultsToBearer() {
        val tokens = TokenResponseDto(accessToken = "a", accessExpiresAt = 1, refreshToken = "r", refreshExpiresAt = 2)
        assertThat(tokens.tokenType).isEqualTo("Bearer")
    }

    @Test
    fun sendCommandDefaultsMatchOpenApi() {
        val dto = RfJson.decodeFromString(SendCommandRequestDto.serializer(), """{ "type": "ping" }""")
        assertThat(dto.params).isEqualTo(JsonObject(emptyMap()))
        assertThat(dto.ttlSec).isEqualTo(SendCommandRequestDto.DEFAULT_TTL_SEC)
        assertThat(SendCommandRequestDto.DEFAULT_TTL_SEC).isEqualTo(300)
    }

    @Test
    fun courseworkDeviceShapeIsUnchangedWithoutConsoleFields() {
        // Поля консоли (П) необязательны: ответ К остаётся валидным, лишних ключей нет
        val dto = DeviceDto(id = "d", restaurantId = "r", role = "hall", status = "online", createdAt = 1)
        assertThat(keysOf(DeviceDto.serializer(), dto))
            .containsExactly("id", "restaurantId", "role", "status", "createdAt")
    }

    @Test
    fun alertDefaultsAreNotAutoAndWithoutDetails() {
        val alert = AlertDto(
            id = "a", ruleId = RuleIds.R01, severity = Severities.WARNING, state = AlertStates.OPEN, deviceId = "d",
            restaurantId = "r", restaurantCode = "MSK-01", restaurantName = "n", openedAt = 1,
        )
        assertThat(alert.auto).isFalse()
        assertThat(alert.details).isEmpty()
        assertThat(keysOf(AlertDto.serializer(), alert)).containsAtLeast("auto", "details")
    }

    @Test
    fun actionCommentIsOptional() {
        val ack = RfJson.decodeFromString(AlertActionDto.serializer(), """{ "action": "acknowledge" }""")
        assertThat(ack.action).isEqualTo(AlertActions.ACKNOWLEDGE)
        assertThat(ack.comment).isNull()
        val incidentAck = RfJson.decodeFromString(IncidentActionDto.serializer(), """{ "action": "acknowledge" }""")
        assertThat(incidentAck.comment).isNull()
    }

    @Test
    fun codesAreStable() {
        assertThat(RuleIds.ALL).containsExactly(
            "R-01", "R-02", "R-03", "R-04", "R-05", "R-06", "R-07", "R-08", "R-09",
        ).inOrder()
        assertThat(RuleIds.ALERT_RULES).containsExactly("R-01", "R-02", "R-03", "R-04", "R-05", "R-06")
        assertThat(IncidentKinds.RULE_BY_KIND).containsExactly(
            IncidentKinds.RESTAURANT_NETWORK, RuleIds.R07,
            IncidentKinds.BACKEND_GLOBAL, RuleIds.R08,
            IncidentKinds.BACKEND_LOCAL, RuleIds.R09,
        )
        assertThat(AlertStates.ALL).containsExactly("open", "acknowledged", "resolved")
        assertThat(Severities.ALL).containsExactly("critical", "warning")
        assertThat(AlertActions.ALL).containsExactly("acknowledge", "resolve")
        assertThat(DeviceStatuses.ALL).containsExactly("provisioned", "online", "offline", "decommissioned")
        assertThat(DeviceRoles.ALL).containsExactly("kitchen", "hall", "assembly")
        assertThat(CommandStatuses.FINAL).containsExactly("succeeded", "failed", "unsupported", "expired")
        assertThat(CommandStatuses.FINAL).containsNoneOf(CommandStatuses.QUEUED, CommandStatuses.DELIVERED)
    }

    @Test
    fun errorBodyOmitsAbsentFieldAndReason() {
        val error = ApiErrorDto(code = "not_found", message = "alert not found")
        assertThat(keysOf(ApiErrorDto.serializer(), error)).containsExactly("code", "message")
    }

    @Test
    fun consolePathsAreUnderApiV1() {
        assertThat(ConsolePaths.ALL).isNotEmpty()
        ConsolePaths.ALL.forEach { assertThat(it).startsWith("/api/v1/") }
        assertThat(ConsolePaths.WS).isEqualTo("/api/v1/ws")
        assertThat(ConsolePaths.alert("a-1")).isEqualTo("/api/v1/alerts/a-1")
        assertThat(ConsolePaths.incident("i-1")).isEqualTo("/api/v1/incidents/i-1")
        assertThat(ConsolePaths.device("d-1")).isEqualTo("/api/v1/devices/d-1")
        assertThat(ConsolePaths.deviceTelemetry("d-1")).isEqualTo("/api/v1/devices/d-1/telemetry")
        assertThat(ConsolePaths.deviceProbes("d-1")).isEqualTo("/api/v1/devices/d-1/probes")
        assertThat(ConsolePaths.deviceEvents("d-1")).isEqualTo("/api/v1/devices/d-1/events")
        assertThat(ConsolePaths.deviceShadow("d-1")).isEqualTo("/api/v1/devices/d-1/shadow")
        assertThat(ConsolePaths.deviceCommands("d-1")).isEqualTo("/api/v1/devices/d-1/commands")
        assertThat(ConsolePaths.command("c-1")).isEqualTo("/api/v1/commands/c-1")
        assertThat(ConsolePaths.restaurant("r-1")).isEqualTo("/api/v1/restaurants/r-1")
    }
}
