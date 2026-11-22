package ru.restofleet.server.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import org.junit.Test
import ru.restofleet.server.domain.command.CommandRequest
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.rest.ApiErrorDto
import ru.restofleet.shared.protocol.rest.console.AlertActionDto
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.CommandDto
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.CreateRestaurantRequestDto
import ru.restofleet.shared.protocol.rest.console.DecommissionDto
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.EventRecordDto
import ru.restofleet.shared.protocol.rest.console.IncidentActionDto
import ru.restofleet.shared.protocol.rest.console.IncidentDetailsDto
import ru.restofleet.shared.protocol.rest.console.IncidentDto
import ru.restofleet.shared.protocol.rest.console.IncidentEvidenceDto
import ru.restofleet.shared.protocol.rest.console.IncidentThresholdsDto
import ru.restofleet.shared.protocol.rest.console.IssueTokenRequestDto
import ru.restofleet.shared.protocol.rest.console.IssueTokensBatchRequestDto
import ru.restofleet.shared.protocol.rest.console.IssuedTokenDto
import ru.restofleet.shared.protocol.rest.console.LoginRequestDto
import ru.restofleet.shared.protocol.rest.console.LogoutRequestDto
import ru.restofleet.shared.protocol.rest.console.ProbeRecordDto
import ru.restofleet.shared.protocol.rest.console.ProblemRestaurantDto
import ru.restofleet.shared.protocol.rest.console.RefreshRequestDto
import ru.restofleet.shared.protocol.rest.console.RestaurantDto
import ru.restofleet.shared.protocol.rest.console.RestaurantEvidenceDto
import ru.restofleet.shared.protocol.rest.console.RestaurantRefDto
import ru.restofleet.shared.protocol.rest.console.SendCommandRequestDto
import ru.restofleet.shared.protocol.rest.console.SessionDto
import ru.restofleet.shared.protocol.rest.console.ShadowDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto
import ru.restofleet.shared.protocol.rest.console.TelemetryPointDto
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto
import ru.restofleet.shared.protocol.rest.console.UpdateRestaurantRequestDto
import ru.restofleet.shared.protocol.rest.console.ws.WsDeviceRefDto
import ru.restofleet.shared.protocol.rest.console.ws.WsDeviceStatusDto
import ru.restofleet.shared.protocol.rest.console.ws.WsHelloDto
import ru.restofleet.shared.protocol.rest.console.ws.WsResyncDto
import ru.restofleet.shared.protocol.rest.console.ws.WsTelemetryDto
import ru.restofleet.shared.protocol.rest.console.ws.WsTypes

/**
 * Контракт консоли (ПМ1, реш. 408): схемы `documentation.yaml` совпадают с DTO `shared:protocol` по именам полей и
 * обязательности, пути консоли есть в спецификации, умолчания DTO равны доменным.
 */
class ConsoleContractTest {
    private val spec = Yaml.default.parseToYamlNode(
        checkNotNull(javaClass.classLoader.getResource(ApiPaths.OPENAPI_RESOURCE)).readText(),
    ).yamlMap

    private val schemas = spec.get<YamlMap>("components")!!.get<YamlMap>("schemas")!!

    @Test
    fun `every schema matches its dto by property names and required fields`() {
        SCHEMAS.forEach { (name, serializer) ->
            val schema = schemas.get<YamlMap>(name)
            assertWithMessage("schema $name").that(schema).isNotNull()
            val properties = schema!!.get<YamlMap>("properties")!!.entries.keys.map { it.content }.toSet()
            val required = schema.get<YamlList>("required")?.items?.map { it.text() }?.toSet().orEmpty()
            val descriptor = serializer.descriptor
            val mandatory = descriptor.elementNames.filterIndexed { i, _ -> !descriptor.isElementOptional(i) }

            assertWithMessage("properties of $name").that(properties).containsExactlyElementsIn(descriptor.elementNames)
            assertWithMessage("required of $name").that(required).containsAtLeastElementsIn(mandatory)
            assertWithMessage("required of $name are properties").that(properties).containsAtLeastElementsIn(required)
        }
    }

    @Test
    fun `websocket types are documented in the envelope schema`() {
        val type = schemas.get<YamlMap>("WsEnvelope")!!.get<YamlMap>("properties")!!.get<YamlMap>("type")!!
        val documented = type.get<YamlList>("enum")!!.items.map { it.text() }.toSet()

        assertThat(documented).containsExactlyElementsIn(WsTypes.SERVER + WsTypes.CLIENT)
    }

    @Test
    fun `every console path is in the specification`() {
        val paths = spec.get<YamlMap>("paths")!!.entries.keys.map { it.content }.toSet()

        assertThat(paths).containsAtLeastElementsIn(ConsolePaths.ALL)
    }

    @Test
    fun `command ttl default of the dto equals the domain default`() {
        assertThat(SendCommandRequestDto.DEFAULT_TTL_SEC).isEqualTo(CommandRequest.DEFAULT_TTL_SEC)
    }

    private fun YamlNode.text(): String = (this as YamlScalar).content

    private companion object {
        val SCHEMAS: Map<String, KSerializer<*>> = mapOf(
            "Error" to ApiErrorDto.serializer(),
            "LoginRequest" to LoginRequestDto.serializer(),
            "RefreshRequest" to RefreshRequestDto.serializer(),
            "LogoutRequest" to LogoutRequestDto.serializer(),
            "TokenResponse" to TokenResponseDto.serializer(),
            "Restaurant" to RestaurantDto.serializer(),
            "RestaurantRef" to RestaurantRefDto.serializer(),
            "CreateRestaurantRequest" to CreateRestaurantRequestDto.serializer(),
            "UpdateRestaurantRequest" to UpdateRestaurantRequestDto.serializer(),
            "DeviceInfo" to DeviceInfoDto.serializer(),
            "Device" to DeviceDto.serializer(),
            "TelemetryPoint" to TelemetryPointDto.serializer(),
            "Session" to SessionDto.serializer(),
            "DeviceDetails" to DeviceDetailsDto.serializer(),
            "Decommission" to DecommissionDto.serializer(),
            "ProbeRecord" to ProbeRecordDto.serializer(),
            "EventRecord" to EventRecordDto.serializer(),
            "Shadow" to ShadowDto.serializer(),
            "SendCommandRequest" to SendCommandRequestDto.serializer(),
            "Command" to CommandDto.serializer(),
            "IssueTokenRequest" to IssueTokenRequestDto.serializer(),
            "IssueTokensBatchRequest" to IssueTokensBatchRequestDto.serializer(),
            "IssuedToken" to IssuedTokenDto.serializer(),
            "Alert" to AlertDto.serializer(),
            "AlertAction" to AlertActionDto.serializer(),
            "Incident" to IncidentDto.serializer(),
            "IncidentAction" to IncidentActionDto.serializer(),
            "IncidentDetails" to IncidentDetailsDto.serializer(),
            "IncidentEvidence" to IncidentEvidenceDto.serializer(),
            "RestaurantEvidence" to RestaurantEvidenceDto.serializer(),
            "IncidentThresholds" to IncidentThresholdsDto.serializer(),
            "Summary" to SummaryDto.serializer(),
            "ProblemRestaurant" to ProblemRestaurantDto.serializer(),
            "WsHello" to WsHelloDto.serializer(),
            "WsDeviceStatus" to WsDeviceStatusDto.serializer(),
            "WsTelemetry" to WsTelemetryDto.serializer(),
            "WsResync" to WsResyncDto.serializer(),
            "WsDeviceRef" to WsDeviceRefDto.serializer(),
        )
    }
}
