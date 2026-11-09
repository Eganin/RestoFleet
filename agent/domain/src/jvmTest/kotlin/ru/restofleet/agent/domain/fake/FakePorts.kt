package ru.restofleet.agent.domain.fake

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.LaunchStrategy
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.error.MessageRejected
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.UserNotifier
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

typealias MapResult = Outcome<Map<String, String>, DomainError>

data class TestError(override val code: String) : DomainError

/** Окончательный отказ брокера в публикации (как PUBACK `0x87`): повтор не поможет. */
data class TestRejection(override val code: String = "not_authorized") : MessageRejected

class FakeDeviceAdmin : DeviceAdmin {
    val calls = mutableListOf<String>()
    var rebootResult: suspend () -> Outcome<Unit, DomainError> = { Outcome.Success(Unit) }
    var installResult: suspend () -> Outcome<Map<String, String>, DomainError> = { Outcome.Success(emptyMap()) }
    var lockResult: suspend () -> Outcome<Unit, DomainError> = { Outcome.Success(Unit) }

    override suspend fun isDeviceOwner(): Boolean = true

    override suspend fun reboot(): Outcome<Unit, DomainError> {
        calls += "reboot"
        return rebootResult()
    }

    override suspend fun installApk(
        pkg: String,
        url: String,
        sha256: String,
    ): Outcome<Map<String, String>, DomainError> {
        calls += "install:$pkg:$url:$sha256"
        return installResult()
    }

    override suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError> {
        calls += "lock:$pkg:$url"
        return lockResult()
    }

    var grantResult: Map<String, Boolean> = emptyMap()
    var grantCalls = 0
    var grantBefore: suspend () -> Unit = {}

    override suspend fun grantSelfPermissions(): Map<String, Boolean> {
        grantCalls++
        grantBefore()
        return grantResult
    }
}

class FakeUserNotifier : UserNotifier {
    val calls = mutableListOf<Pair<String, Level>>()
    var result: suspend () -> MapResult = { Outcome.Success(emptyMap()) }

    override suspend fun show(text: String, level: Level): MapResult {
        calls += text to level
        return result()
    }
}

class FakeAppLauncher : AppLauncher {
    data class Call(val pkg: String, val url: String?, val strategy: LaunchStrategy)

    val calls = mutableListOf<Call>()
    var result: suspend () -> MapResult = { Outcome.Success(emptyMap()) }

    override suspend fun launch(pkg: String, url: String?, strategy: LaunchStrategy): MapResult {
        calls += Call(pkg, url, strategy)
        return result()
    }
}

class FakeBeeper : Beeper {
    val calls = mutableListOf<Long>()
    var result: suspend () -> MapResult = { Outcome.Success(mapOf("ringerMode" to "normal")) }

    override suspend fun beep(durationMs: Long): MapResult {
        calls += durationMs
        return result()
    }
}

class FakeCheckResultJournal : CheckResultJournal {
    val records = mutableListOf<CheckResult>()

    override fun record(result: CheckResult) {
        records += result
    }

    override fun recorded(): Map<CheckId, CheckResult> = records.associateBy { it.id }
}
