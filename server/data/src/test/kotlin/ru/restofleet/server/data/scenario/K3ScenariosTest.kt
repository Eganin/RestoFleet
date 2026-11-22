package ru.restofleet.server.data.scenario

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.server.data.db.ExposedRestaurantRepository
import ru.restofleet.server.data.support.KoinBackend
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.next
import ru.restofleet.server.data.support.TestBroker.publishQos1
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.command.CommandRequest
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.command.usecase.ExpireCommands
import ru.restofleet.server.domain.command.usecase.GetCommand
import ru.restofleet.server.domain.command.usecase.SendCommand
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.usecase.DecommissionDevice
import ru.restofleet.server.domain.device.usecase.GetDevice
import ru.restofleet.server.domain.provisioning.IssueTokensRequest
import ru.restofleet.server.domain.provisioning.ProvisionRequest
import ru.restofleet.server.domain.provisioning.ProvisionedDevice
import ru.restofleet.server.domain.provisioning.usecase.IssueProvisioningTokens
import ru.restofleet.server.domain.provisioning.usecase.ProvisionDevice
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import java.util.UUID

/**
 * Сценарии К3 (`1-coursework.md`, разд. 5, план 2.2) на настоящих адаптерах: PostgreSQL, Mosquitto + DynSec, HiveMQ,
 * граф Koin `dataModule` + `domainModule`. Агента заменяет MQTT-клиент с теми же свойствами сессии.
 */
class K3ScenariosTest {
    private val clientId = "backend-k3-${UUID.randomUUID()}"
    private lateinit var backend: KoinBackend
    private val agents = mutableListOf<Mqtt5BlockingClient>()

    @Before
    fun start() {
        TestDatabase.reset()
        backend = KoinBackend(TestBroker.backendConfig(clientId)).start()
    }

    @After
    fun stop() {
        agents.forEach { runCatching { if (it.state.isConnected) it.disconnect() } }
        backend.close()
        TestBroker.purgeSession(clientId)
    }

    private fun provision(): ProvisionedDevice {
        val restaurant = Samples.restaurant()
        TestDatabase.inTx { ExposedRestaurantRepository().insert(restaurant) }
        val code = runBlocking {
            backend.get<IssueProvisioningTokens>()(IssueTokensRequest(restaurant.id, DeviceRole.KITCHEN, "Кухня-1"))
        }.let { (it as Outcome.Success).value.single().code }
        val request = ProvisionRequest(code, DeviceInfo("sdk_gphone64_arm64", "Google", "16", 36), "0.2.0")
        return (runBlocking { backend.get<ProvisionDevice>()(request) } as Outcome.Success).value
    }

    private fun agent(device: ProvisionedDevice): Mqtt5BlockingClient =
        TestBroker.deviceClient(device.deviceId.value).also { agents += it }

    private fun Mqtt5BlockingClient.subscribe(topic: String) =
        subscribeWith().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE).send()

    private fun device(provisioned: ProvisionedDevice) =
        (runBlocking { backend.get<GetDevice>()(provisioned.deviceId) } as Outcome.Success).value

    @Test
    fun `provision then telemetry - the device is online and its readings are in the database`() {
        val provisioned = provision()
        val agent = agent(provisioned)
        val id = provisioned.deviceId

        assertThat(agent.connectAs(id.value, provisioned.password)).isNull()
        agent.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            agent.subscribe(Topics.shadowDesired(id))
            val desired = inbox.next(5_000)!!
            assertThat(desired.isRetain).isTrue()
            val dto = RfJson.decodeFromString(ShadowDesiredDto.serializer(), desired.payloadAsBytes.decodeToString())
            assertThat(dto.version).isEqualTo(1)
        }
        agent.publishQos1(Topics.status(id), """{"state":"online","ts":1,"connId":"k-1","agentVersion":"0.2.0"}""")
        agent.publishQos1(
            Topics.telemetry(id),
            """{"ts":${Samples.T0},"seq":1,"battery":{"level":77,"charging":true}}""",
        )

        assertThat(eventually { device(provisioned).lastTelemetry != null }).isTrue()
        val details = device(provisioned)
        assertThat(details.device.status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(details.lastTelemetry!!.batteryLevel).isEqualTo(77)
        assertThat(details.lastSession!!.connId).isEqualTo("k-1")
    }

    @Test
    fun `command to an offline device is delivered on reconnect and its result is stored`() {
        val provisioned = provision()
        val id = provisioned.deviceId
        val agent = agent(provisioned)
        // Агент однажды подключался и подписался: постоянная сессия на брокере (Session Expiry)
        assertThat(agent.connectAs(id.value, provisioned.password)).isNull()
        agent.subscribe(Topics.cmdReq(id))
        agent.disconnect()

        val command = (runBlocking { backend.get<SendCommand>()(CommandRequest(id, "ping", ttlSec = 60)) }
            as Outcome.Success).value
        assertThat(command.status).isEqualTo(CommandStatus.QUEUED)

        agent.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            assertThat(agent.connectAs(id.value, provisioned.password)).isNull()
            val req = generateSequence { inbox.next(5_000) }.first { it.topic.toString() == Topics.cmdReq(id) }
            val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), req.payloadAsBytes.decodeToString())
            assertThat(dto.cmdId).isEqualTo(command.cmdId)
            agent.publishQos1(Topics.cmdRes(id), """{"cmdId":"${dto.cmdId}","status":"received","ts":1}""")
            agent.publishQos1(
                Topics.cmdRes(id),
                """{"cmdId":"${dto.cmdId}","status":"succeeded","ts":2,"result":{"deviceTime":"2"}}""",
            )
        }

        val getCommand = backend.get<GetCommand>()
        fun stored() = (runBlocking { getCommand(command.cmdId) } as Outcome.Success).value
        assertThat(eventually { stored().status == CommandStatus.SUCCEEDED }).isTrue()
        val stored = stored()
        assertThat(stored.deliveredAt).isNotNull()
        assertThat(stored.result).containsExactly("deviceTime", "2")
    }

    @Test
    fun `command that outlives its ttl while the device is offline expires and is never delivered`() {
        val provisioned = provision()
        val id = provisioned.deviceId
        val agent = agent(provisioned)
        assertThat(agent.connectAs(id.value, provisioned.password)).isNull()
        agent.subscribe(Topics.cmdReq(id))
        agent.disconnect()

        val command = (runBlocking { backend.get<SendCommand>()(CommandRequest(id, "beep", ttlSec = 1)) }
            as Outcome.Success).value
        Thread.sleep(2_200)

        assertThat(runBlocking { backend.get<ExpireCommands>()() }).isAtLeast(1)
        assertThat((runBlocking { backend.get<GetCommand>()(command.cmdId) } as Outcome.Success).value.status)
            .isEqualTo(CommandStatus.EXPIRED)
        agent.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            assertThat(agent.connectAs(id.value, provisioned.password)).isNull()
            // Message Expiry брокера: истёкшая команда не доставляется вовсе (P2)
            val delivered = generateSequence { inbox.next(1_500) }
                .firstOrNull { it.topic.toString() == Topics.cmdReq(id) }
            assertThat(delivered).isNull()
        }
    }

    @Test
    fun `decommissioned device is disconnected and its account no longer connects`() {
        val provisioned = provision()
        val id = provisioned.deviceId
        val live = agent(provisioned)
        assertThat(live.connectAs(id.value, provisioned.password)).isNull()

        val result = (runBlocking { backend.get<DecommissionDevice>()(id) } as Outcome.Success).value

        assertThat(result.device.status).isEqualTo(DeviceStatus.DECOMMISSIONED)
        assertThat(result.retainedCleared).isTrue()
        assertThat(eventually(5_000) { !live.state.isConnected }).isTrue()
        assertThat(agent(provisioned).connectAs(id.value, provisioned.password))
            .isEqualTo(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED)
        assertThat(device(provisioned).device.status).isEqualTo(DeviceStatus.DECOMMISSIONED)
    }
}
