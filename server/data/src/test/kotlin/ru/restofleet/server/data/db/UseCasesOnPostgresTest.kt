package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.security.BcryptHasher
import ru.restofleet.server.data.security.SecureRandomSecrets
import ru.restofleet.server.data.security.Sha256Digest
import ru.restofleet.server.data.support.FakeBroker
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestClock
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.command.CommandRequest
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.command.usecase.ExpireCommands
import ru.restofleet.server.domain.command.usecase.SendCommand
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.polling.usecase.PollCommands
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import ru.restofleet.server.domain.provisioning.IssueTokensRequest
import ru.restofleet.server.domain.provisioning.IssuedToken
import ru.restofleet.server.domain.provisioning.ProvisionRequest
import ru.restofleet.server.domain.provisioning.ProvisionedDevice
import ru.restofleet.server.domain.provisioning.ProvisioningSettings
import ru.restofleet.server.domain.provisioning.usecase.IssueProvisioningTokens
import ru.restofleet.server.domain.provisioning.usecase.ProvisionDevice
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.UuidIdGenerator

/** Use cases на настоящих репозиториях: границы транзакций [ExposedTransactionRunner] и атомарность SQL. */
internal class UseCasesOnPostgresTest : PostgresTest() {
    private val broker = FakeBroker()
    private val clock = TestClock()
    private val hasher = BcryptHasher(cost = 4)
    private val tokens = ExposedTokenRepository()
    private val credentials = ExposedCredentialRepository()
    private val shadows = ExposedShadowRepository()
    private val commands = ExposedCommandRepository()
    private val settings = ProvisioningSettings(BrokerEndpoint("10.0.2.2", 8883))
    private val ids = UuidIdGenerator()
    private val secrets = SecureRandomSecrets()

    private val issue = IssueProvisioningTokens(
        tokens, restaurants, secrets, Sha256Digest, settings, TestDatabase.tx, ids, clock,
    )
    private val provision = ProvisionDevice(
        tokens, devices, credentials, shadows, broker, broker, hasher, secrets, Sha256Digest, settings,
        TestDatabase.tx, ids, clock,
    )
    private val info = DeviceInfo("Pixel 9a", "Google", "16", 36)

    private fun issueCode(): IssuedToken {
        val r = givenRestaurant()
        val issued = runBlocking { issue(IssueTokensRequest(r.id, DeviceRole.HALL, "Зал-1", count = 2)) }
        return (issued as Outcome.Success).value.first()
    }

    @Test
    fun `code is exchanged for a device, a bcrypt credential, a shadow and a DynSec client exactly once`() {
        val code = issueCode()
        // Код вводят руками: нижний регистр и дефисы не важны
        val typed = code.code.lowercase().chunked(4).joinToString("-")

        val result = runBlocking { provision(ProvisionRequest(typed, info, "0.2.0")) }
            as Outcome.Success<ProvisionedDevice>
        val again = runBlocking { provision(ProvisionRequest(code.code, info, "0.2.0")) }

        val device = result.value
        assertThat(device.role).isEqualTo(DeviceRole.HALL)
        assertThat(device.broker).isEqualTo(settings.broker)
        val stored = tx { devices.findById(device.deviceId) }!!
        assertThat(stored.status).isEqualTo(DeviceStatus.PROVISIONED)
        assertThat(stored.info).isEqualTo(info)
        assertThat(stored.name).isEqualTo("Зал-1")
        assertThat(hasher.verify(device.password, tx { credentials.findActiveHash(device.deviceId) }!!)).isTrue()
        assertThat(tx { shadows.find(device.deviceId) }!!.desiredVersion).isEqualTo(1)
        val token = tx { tokens.findByHash(Sha256Digest.sha256(code.code)) }!!
        assertThat(token.usedAt).isEqualTo(T0)
        assertThat(token.deviceId).isEqualTo(device.deviceId)
        val id = device.deviceId.value
        assertThat(broker.calls).containsExactly("create $id", "desired $id v1")
            .inOrder()
        assertThat(again).isEqualTo(Outcome.Failure(ServerError.TokenUsed))
    }

    @Test
    fun `broker refusal rolls the whole transaction back so the same code works later`() {
        val code = issueCode()
        broker.failAccounts = true

        val refused = runBlocking { provision(ProvisionRequest(code.code, info, "0.2.0")) }

        assertThat(refused).isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("create")))
        assertThat(tx { tokens.findByHash(Sha256Digest.sha256(code.code)) }!!.usedAt).isNull()
        listOf("devices", "device_credentials", "shadows").forEach {
            assertThat(TestDatabase.scalar("SELECT count(*) FROM $it")).isEqualTo("0")
        }

        broker.failAccounts = false
        val retried = runBlocking { provision(ProvisionRequest(code.code, info, "0.2.0")) }
        assertThat(retried).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun `expired code is rejected as expired`() {
        val code = issueCode()
        clock.now = T0 + ProvisioningSettings.DEFAULT_TOKEN_TTL_MS

        assertThat(runBlocking { provision(ProvisionRequest(code.code, info, "0.2.0")) })
            .isEqualTo(Outcome.Failure(ServerError.TokenExpired))
        assertThat(runBlocking { provision(ProvisionRequest("NO-SUCH-CODE", info, "0.2.0")) })
            .isEqualTo(Outcome.Failure(ServerError.TokenInvalid))
    }

    @Test
    fun `command lifecycle on postgres - queued, delivered to a poller once, the rest expires`() {
        val d = givenDevice()
        val send = SendCommand(devices, commands, broker, TestDatabase.tx, ids, clock)
        val poll = PollCommands(devices, commands, TestDatabase.tx, clock)
        val expire = ExpireCommands(commands, TestDatabase.tx, clock)

        val short = (runBlocking { send(CommandRequest(d.id, "ping", ttlSec = 10)) } as Outcome.Success).value
        clock.now = T0 + 11_000
        val fresh = (runBlocking { send(CommandRequest(d.id, "beep", ttlSec = 60)) } as Outcome.Success).value

        val delivered = runBlocking { poll(d.id) }
        val expired = runBlocking { expire() }

        assertThat(short.cmdId).startsWith("c-")
        assertThat(delivered.map { it.cmdId }).containsExactly(fresh.cmdId)
        assertThat(expired).isEqualTo(1)
        assertThat(tx { commands.find(short.cmdId) }!!.status).isEqualTo(CommandStatus.EXPIRED)
        assertThat(tx { commands.find(fresh.cmdId) }!!.status).isEqualTo(CommandStatus.DELIVERED)
        assertThat(runBlocking { poll(d.id) }).isEmpty()
        assertThat(tx { devices.findById(d.id) }!!.status).isEqualTo(DeviceStatus.ONLINE)
    }
}
