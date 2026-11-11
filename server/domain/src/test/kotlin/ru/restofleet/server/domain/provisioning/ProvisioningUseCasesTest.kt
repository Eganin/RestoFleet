package ru.restofleet.server.domain.provisioning

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.provisioning.usecase.IssueProvisioningTokens
import ru.restofleet.server.domain.provisioning.usecase.ProvisionDevice
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.shared.kernel.Outcome

class ProvisioningUseCasesTest {
    private val w = World()
    private val issue = IssueProvisioningTokens(
        w.tokens, w.restaurants, w.secrets, w.digest, w.provisioningSettings, w.tx, w.ids, w.clock,
    )
    private val provision = ProvisionDevice(
        w.tokens, w.devices, w.credentials, w.shadows, w.broker, w.publisher, w.hasher, w.secrets, w.digest,
        w.provisioningSettings, w.tx, w.ids, w.clock,
    )

    private fun request(code: String) = ProvisionRequest(code, World.INFO, "1.2.0")

    private suspend fun issueOne(name: String? = "Кухня-1"): IssuedToken {
        w.restaurant()
        return (issue(IssueTokensRequest(RestaurantId("r-1"), DeviceRole.HALL, name)) as Outcome.Success).value.single()
    }

    @Test
    fun issueStoresOnlyHashWithTtl() = runTest {
        val token = issueOne()
        assertThat(token.code).isEqualTo("CODE1")
        assertThat(token.expiresAt).isEqualTo(w.clock.now + ProvisioningSettings.DEFAULT_TOKEN_TTL_MS)
        val stored = w.db.state.tokens.values.single()
        assertThat(stored.tokenHash).isEqualTo("sha(CODE1)")
        assertThat(stored.role).isEqualTo(DeviceRole.HALL)
        assertThat(stored.name).isEqualTo("Кухня-1")
        assertThat(stored.usedAt).isNull()
        assertThat(token.toString()).doesNotContain("CODE1")
    }

    @Test
    fun issueBatchAndLimits() = runTest {
        w.restaurant()
        val request = IssueTokensRequest(RestaurantId("r-1"), DeviceRole.KITCHEN, count = 1000)
        val batch = (issue(request) as Outcome.Success).value
        assertThat(batch.map { it.code }.toSet()).hasSize(1000)
        listOf(0, 1001).forEach {
            assertThat(issue(IssueTokensRequest(RestaurantId("r-1"), DeviceRole.KITCHEN, count = it)))
                .isEqualTo(Outcome.Failure(ServerError.Validation("count", "out_of_range")))
        }
        assertThat(issue(IssueTokensRequest(RestaurantId("r-1"), DeviceRole.KITCHEN, name = "n".repeat(101))))
            .isEqualTo(Outcome.Failure(ServerError.Validation("name", "too_long")))
        assertThat(issue(IssueTokensRequest(RestaurantId("nope"), DeviceRole.KITCHEN)))
            .isEqualTo(Outcome.Failure(ServerError.NotFound("restaurant", "nope")))
    }

    @Test
    fun issueBlankNameIsNull() = runTest {
        assertThat(issueOne(name = "  ").name).isNull()
    }

    @Test
    fun provisionCreatesDeviceCredentialShadowAndBrokerClient() = runTest {
        val token = issueOne()
        val result = (provision(request(" code1 ")) as Outcome.Success).value
        val id = result.deviceId
        assertThat(id.value).startsWith("d-")
        assertThat(result.role).isEqualTo(DeviceRole.HALL)
        assertThat(result.restaurantId).isEqualTo(RestaurantId("r-1"))
        assertThat(result.broker).isEqualTo(w.provisioningSettings.broker)
        assertThat(result.toString()).doesNotContain(result.password)

        val device = w.db.state.devices.getValue(id)
        assertThat(device.status).isEqualTo(DeviceStatus.PROVISIONED)
        assertThat(device.name).isEqualTo("Кухня-1")
        assertThat(device.info).isEqualTo(World.INFO)
        assertThat(device.agentVersion).isEqualTo("1.2.0")
        assertThat(w.db.state.credentials.getValue(id).hash).isEqualTo("bcrypt(${result.password})")
        assertThat(w.broker.created).containsExactly(id, result.password)
        val stored = w.db.state.tokens.getValue(token.id)
        assertThat(stored.usedAt).isEqualTo(w.clock.now)
        assertThat(stored.deviceId).isEqualTo(id)
        // Тень версии 1 с desired по умолчанию, retained публикуется после commit
        assertThat(w.db.state.shadows.getValue(id).desired).isEqualTo(DefaultDesired.document)
        assertThat(w.publisher.desired.single().second.version).isEqualTo(1)
    }

    @Test
    fun codeIsSingleUse() = runTest {
        issueOne()
        assertThat(provision(request("CODE1"))).isInstanceOf(Outcome.Success::class.java)
        assertThat(provision(request("CODE1"))).isEqualTo(Outcome.Failure(ServerError.TokenUsed))
        assertThat(w.db.state.devices).hasSize(1)
    }

    @Test
    fun unknownExpiredAndMalformedCodes() = runTest {
        issueOne()
        assertThat(provision(request("NOPE"))).isEqualTo(Outcome.Failure(ServerError.TokenInvalid))
        assertThat(provision(request(" - "))).isEqualTo(Outcome.Failure(ServerError.TokenInvalid))
        assertThat(provision(request("X".repeat(65)))).isEqualTo(Outcome.Failure(ServerError.TokenInvalid))
        w.clock.now += ProvisioningSettings.DEFAULT_TOKEN_TTL_MS
        assertThat(provision(request("CODE1"))).isEqualTo(Outcome.Failure(ServerError.TokenExpired))
        assertThat(w.db.state.devices).isEmpty()
        assertThat(w.broker.created).isEmpty()
    }

    @Test
    fun brokerFailureRollsBackAndKeepsCodeUsable() = runTest {
        val token = issueOne()
        w.broker.failing += "create_client"
        assertThat(provision(request("CODE1")))
            .isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("create_client")))
        assertThat(w.db.state.devices).isEmpty()
        assertThat(w.db.state.credentials).isEmpty()
        assertThat(w.db.state.shadows).isEmpty()
        assertThat(w.db.state.tokens.getValue(token.id).usedAt).isNull()
        assertThat(w.publisher.desired).isEmpty()

        w.broker.failing.clear()
        assertThat(provision(request("CODE1"))).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun commitFailureDeletesBrokerClient() = runTest {
        val token = issueOne()
        w.tx.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { provision(request("CODE1")) }
        }
        assertThat(w.broker.deleted).hasSize(1)
        assertThat(w.broker.created).isEmpty()
        assertThat(w.db.state.devices).isEmpty()
        assertThat(w.db.state.tokens.getValue(token.id).usedAt).isNull()
    }

    @Test
    fun publishFailureAfterCommitStillProvisions() = runTest {
        issueOne()
        w.publisher.fail = true
        assertThat(provision(request("CODE1"))).isInstanceOf(Outcome.Success::class.java)
        assertThat(w.db.state.devices).hasSize(1)
    }

    @Test
    fun codeNormalization() {
        assertThat(ProvisioningCode.normalize(" ab-cd ef\n")).isEqualTo("ABCDEF")
    }
}
