package ru.restofleet.server.domain.fake

import ru.restofleet.server.domain.auth.AccessToken
import ru.restofleet.server.domain.auth.AccessTokenIssuer
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.BrokerAccounts
import ru.restofleet.server.domain.device.DeviceCredentialCache
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis(): Long = now
}

class SequentialIds : IdGenerator {
    private var n = 0

    override fun newId(): String = "id-${++n}"
}

/** «Хэш» = `bcrypt(<пароль>)`; считает вызовы hash и verify. */
class FakePasswordHasher : PasswordHasher {
    var hashes = 0
    var verifies = 0

    override fun hash(password: String): String = "bcrypt($password)".also { hashes++ }

    override fun verify(password: String, hash: String): Boolean = (hash == "bcrypt($password)").also { verifies++ }
}

class FakeSecrets : SecretGenerator {
    private var n = 0

    override fun provisioningCode(): String = "CODE${++n}"

    override fun devicePassword(): String = "pw-${++n}"

    override fun refreshToken(): String = "rt-${++n}"
}

val fakeDigest = SecretDigest { "sha($it)" }

class FakeCredentialCache : DeviceCredentialCache {
    val entries = mutableMapOf<DeviceId, String>()
    val invalidated = mutableListOf<DeviceId>()

    override fun get(deviceId: DeviceId): String? = entries[deviceId]

    override fun put(deviceId: DeviceId, passwordDigest: String) {
        entries[deviceId] = passwordDigest
    }

    override fun invalidate(deviceId: DeviceId) {
        entries.remove(deviceId)
        invalidated += deviceId
    }
}

/** Брокер: журнал вызовов; [failing] — операции, которые отвечают отказом. */
class FakeBrokerAccounts : BrokerAccounts {
    val created = mutableMapOf<DeviceId, String>()
    val disabled = mutableListOf<DeviceId>()
    val deleted = mutableListOf<DeviceId>()
    val failing = mutableSetOf<String>()

    override suspend fun createDevice(id: DeviceId, password: String): Outcome<Unit, ServerError.BrokerUnavailable> =
        attempt("create_client") { created[id] = password }

    override suspend fun disableDevice(id: DeviceId): Outcome<Unit, ServerError.BrokerUnavailable> =
        attempt("disable_client") { disabled += id }

    override suspend fun deleteDevice(id: DeviceId): Outcome<Unit, ServerError.BrokerUnavailable> =
        attempt("delete_client") {
            deleted += id
            created.remove(id)
        }

    private fun attempt(op: String, action: () -> Unit): Outcome<Unit, ServerError.BrokerUnavailable> =
        if (op in failing) Outcome.Failure(ServerError.BrokerUnavailable(op)) else Outcome.Success(action())
}

/** Публикации; [fail] — все публикации отказывают. [inTransaction] — проверка «публикация после commit». */
class FakePublisher(private val db: InMemoryDb? = null) : MessagePublisher {
    val desired = mutableListOf<Pair<DeviceId, DesiredState>>()
    val cleared = mutableListOf<DeviceId>()
    val commands = mutableListOf<Command>()
    var fail = false

    override suspend fun publishDesired(
        id: DeviceId,
        desired: DesiredState,
    ): Outcome<Unit, ServerError.BrokerUnavailable> =
        attempt("publish") { this.desired += id to desired }

    override suspend fun clearRetained(id: DeviceId): Outcome<Unit, ServerError.BrokerUnavailable> =
        attempt("publish") { cleared += id }

    override suspend fun publishCommand(command: Command): Outcome<Unit, ServerError.BrokerUnavailable> =
        attempt("publish") { commands += command }

    private fun attempt(op: String, action: () -> Unit): Outcome<Unit, ServerError.BrokerUnavailable> {
        check(db?.inTransaction != true) { "publish inside transaction" }
        return if (fail) Outcome.Failure(ServerError.BrokerUnavailable(op)) else Outcome.Success(action())
    }
}

class FakeAccessTokens : AccessTokenIssuer {
    var issued = 0

    override fun issue(user: User): AccessToken = AccessToken("jwt-${user.id}-${++issued}", 900_000L)
}
