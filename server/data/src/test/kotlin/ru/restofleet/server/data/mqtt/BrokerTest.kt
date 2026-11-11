package ru.restofleet.server.data.mqtt

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestClock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import java.util.concurrent.CopyOnWriteArrayList

/** Тесты на живом Mosquitto + DynSec ([TestBroker]): бэкенд и «устройства» — настоящие MQTT-клиенты. */
internal abstract class BrokerTest {
    private val opened = CopyOnWriteArrayList<Mqtt5BlockingClient>()
    private val backends = CopyOnWriteArrayList<HiveMqClient>()
    private val backendIds = CopyOnWriteArrayList<String>()
    protected val clock = TestClock(System.currentTimeMillis())

    /** Подключённый бэкенд, подписанный на ответы DynSec. */
    protected fun backend(config: MqttConfig = TestBroker.backendConfig()): HiveMqClient =
        HiveMqClient(config, trust = null).also { client ->
            track(client, config)
            runBlocking {
                client.connect()
                client.subscribe(listOf(DynSecBrokerAccounts.RESPONSE_TOPIC))
            }
        }

    /** Клиент, который тест подключит сам: после теста он отключается, а его сессия удаляется. */
    protected fun track(client: HiveMqClient, config: MqttConfig) {
        backends += client
        backendIds += config.clientId
    }

    protected fun accounts(client: HiveMqClient, timeoutMs: Long = 5_000) = DynSecBrokerAccounts(client, timeoutMs)

    /** Учётка устройства, созданная через DynSec так же, как при провижининге. */
    protected fun provisionedDevice(accounts: DynSecBrokerAccounts): Pair<DeviceId, String> {
        val id = Samples.deviceId()
        val password = "pw-${Samples.uuid()}"
        check(runBlocking { accounts.createDevice(id, password) } is Outcome.Success) { "createClient failed" }
        return id to password
    }

    protected fun client(clientId: String): Mqtt5BlockingClient =
        TestBroker.deviceClient(clientId).also { opened += it }

    @After
    fun closeClients() {
        opened.forEach { c -> runCatching { if (c.state.isConnected) c.disconnect() } }
        runBlocking { backends.forEach { it.disconnect() } }
        backendIds.distinct().forEach { TestBroker.purgeSession(it) }
    }
}
