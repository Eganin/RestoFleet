package ru.restofleet.server.data.support

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.common.ServerError.BrokerUnavailable
import ru.restofleet.server.domain.device.BrokerAccounts
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import java.util.concurrent.CopyOnWriteArrayList

/** Брокер без сети для тестов адаптеров БД: журнал вызовов и управляемые отказы. */
class FakeBroker : BrokerAccounts, MessagePublisher {
    val calls = CopyOnWriteArrayList<String>()

    @Volatile var failAccounts = false

    private fun result(call: String, fail: Boolean): Outcome<Unit, BrokerUnavailable> {
        calls += call
        return if (fail) Outcome.Failure(BrokerUnavailable(call.substringBefore(' '))) else Outcome.Success(Unit)
    }

    override suspend fun createDevice(id: DeviceId, password: String) = result("create ${id.value}", failAccounts)

    override suspend fun disableDevice(id: DeviceId) = result("disable ${id.value}", failAccounts)

    override suspend fun deleteDevice(id: DeviceId) = result("delete ${id.value}", failAccounts)

    override suspend fun publishDesired(id: DeviceId, desired: DesiredState) =
        result("desired ${id.value} v${desired.version}", fail = false)

    override suspend fun clearRetained(id: DeviceId) = result("clear ${id.value}", fail = false)

    override suspend fun publishCommand(command: Command) = result("cmd ${command.cmdId}", fail = false)
}

/** Управляемые часы. */
class TestClock(@Volatile var now: Long = Samples.T0) : Clock {
    override fun nowMillis(): Long = now
}
