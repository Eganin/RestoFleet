package ru.restofleet.agent.presentation.support

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.LaunchStrategy
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.UserNotifier
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Порты `ExecuteCommand` с журналом вызовов: тесты presentation проверяют, что и с какими аргументами исполнено. */
class CommandPorts(
    var mode: AgentMode = AgentMode.NORMAL,
    val calls: MutableList<String> = mutableListOf(),
) {
    var beepResult: Map<String, String> = mapOf("ringerMode" to "silent")
    var launchResult: Map<String, String> = mapOf("strategy" to "DIRECT_OVERLAY")
    var installFailure: String? = null
    var grants: Map<String, Boolean> = mapOf("ACCESS_FINE_LOCATION" to true)
    var failWith: Throwable? = null

    private fun hit(call: String) {
        calls += call
        failWith?.let { throw it }
    }

    val admin = object : DeviceAdmin {
        override suspend fun isDeviceOwner() = mode == AgentMode.DEVICE_OWNER

        override suspend fun reboot(): Outcome<Unit, DomainError> {
            hit("reboot")
            return Outcome.Success(Unit)
        }

        override suspend fun installApk(
            pkg: String,
            url: String,
            sha256: String,
        ): Outcome<Map<String, String>, DomainError> {
            hit("install:$pkg:$url:$sha256")
            return installFailure?.let { Outcome.Failure(Code(it)) } ?: Outcome.Success(mapOf("package" to pkg))
        }

        override suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError> {
            hit("lock:$pkg:$url")
            return Outcome.Success(Unit)
        }

        override suspend fun grantSelfPermissions(): Map<String, Boolean> {
            hit("grant")
            return grants
        }
    }

    private val notifier = UserNotifier { text, level: Level ->
        hit("message:$text:$level")
        Outcome.Success(emptyMap())
    }
    private val launcher = AppLauncher { pkg, url, strategy: LaunchStrategy ->
        hit("launch:$pkg:$url:$strategy")
        Outcome.Success(launchResult)
    }
    private val beeper = Beeper { ms ->
        hit("beep:$ms")
        Outcome.Success(beepResult)
    }

    val capabilities = CapabilitiesSource {
        Capabilities(mode, 36, setOf(Permission.SYSTEM_ALERT_WINDOW, Permission.POST_NOTIFICATIONS))
    }

    fun executeCommand(clock: Clock = Clock { 1L }) =
        ExecuteCommand(capabilities, admin, notifier, launcher, beeper, clock)
}

data class Code(override val code: String) : DomainError

class MemoryJournal : CheckResultJournal {
    val records = mutableListOf<CheckResult>()

    override fun record(result: CheckResult) {
        records += result
    }

    override fun recorded(): Map<CheckId, CheckResult> = records.associateBy { it.id }
}
