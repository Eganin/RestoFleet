package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.SharedSubscriptions

/** `DynSecBrokerAccounts` через `$CONTROL/dynamic-security/v1` на роли `backend-service` из `dynsec-roles.sh`. */
internal class DynSecBrokerAccountsTest : BrokerTest() {

    @Test
    fun `backend role allows the shared subscription to every device channel and DynSec control`() {
        val backend = backend()

        // SUBACK с ошибкой бросил бы IllegalStateException
        runBlocking { backend.subscribe(SharedSubscriptions.backend()) }

        val role = TestBroker.describeRole("backend-service")
        assertThat(role).contains("\$share/backend/rf/v1/dev/+/telemetry")
        assertThat(role).contains("\$CONTROL/dynamic-security/#")
    }

    @Test
    fun `created client connects only with its password and its own clientId`() {
        val accounts = accounts(backend())
        val (id, password) = provisionedDevice(accounts)

        assertThat(client(id.value).connectAs(id.value, password)).isNull()
        assertThat(client("${id.value}-other").connectAs(id.value, password)).isNotNull()
        assertThat(client(id.value).connectAs(id.value, "wrong")).isEqualTo(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED)
    }

    @Test
    fun `creating an existing client fails, so a broken provisioning never reuses someone else's account`() {
        val accounts = accounts(backend())
        val (id, _) = provisionedDevice(accounts)

        assertThat(runBlocking { accounts.createDevice(id, "another") })
            .isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("create_client")))
    }

    @Test
    fun `disable kicks the live session and refuses new connections`() {
        val accounts = accounts(backend())
        val (id, password) = provisionedDevice(accounts)
        val live = client(id.value)
        assertThat(live.connectAs(id.value, password)).isNull()

        assertThat(runBlocking { accounts.disableDevice(id) }).isEqualTo(Outcome.Success(Unit))

        waitUntil { !live.state.isConnected }
        assertThat(live.state.isConnected).isFalse()
        assertThat(client(id.value).connectAs(id.value, password)).isEqualTo(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED)
    }

    @Test
    fun `delete removes the account, and disable or delete of a missing client is a success`() {
        val accounts = accounts(backend())
        val (id, password) = provisionedDevice(accounts)

        assertThat(runBlocking { accounts.deleteDevice(id) }).isEqualTo(Outcome.Success(Unit))

        assertThat(client(id.value).connectAs(id.value, password)).isEqualTo(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED)
        val missing = Samples.deviceId()
        assertThat(runBlocking { accounts.deleteDevice(missing) }).isEqualTo(Outcome.Success(Unit))
        assertThat(runBlocking { accounts.disableDevice(missing) }).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun `no connection or no response is reported as broker unavailable`() {
        val disconnected = HiveMqClient(TestBroker.backendConfig(), trust = null)
        assertThat(runBlocking { accounts(disconnected).createDevice(Samples.deviceId(), "pw") })
            .isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("create_client")))

        // Подключён, но не подписан на ответы DynSec: ответа не дождаться
        val deaf = HiveMqClient(TestBroker.backendConfig(), trust = null)
        runBlocking { deaf.connect() }
        try {
            assertThat(runBlocking { accounts(deaf, timeoutMs = 500).disableDevice(Samples.deviceId()) })
                .isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("disable_client")))
        } finally {
            runBlocking { deaf.disconnect() }
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }
}
