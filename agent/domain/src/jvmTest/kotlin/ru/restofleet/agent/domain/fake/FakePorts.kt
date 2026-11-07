package ru.restofleet.agent.domain.fake

import ru.restofleet.agent.domain.capability.LaunchStrategy
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.UserNotifier
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

typealias MapResult = Outcome<Map<String, String>, DomainError>

data class TestError(override val code: String) : DomainError

class FakeDeviceAdmin : DeviceAdmin {
    val calls = mutableListOf<String>()
    var rebootResult: suspend () -> Outcome<Unit, DomainError> = { Outcome.Success(Unit) }
    var installResult: suspend () -> Outcome<Unit, DomainError> = { Outcome.Success(Unit) }
    var lockResult: suspend () -> Outcome<Unit, DomainError> = { Outcome.Success(Unit) }

    override suspend fun isDeviceOwner(): Boolean = true

    override suspend fun reboot(): Outcome<Unit, DomainError> {
        calls += "reboot"
        return rebootResult()
    }

    override suspend fun installApk(url: String, sha256: String): Outcome<Unit, DomainError> {
        calls += "install:$url:$sha256"
        return installResult()
    }

    override suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError> {
        calls += "lock:$pkg:$url"
        return lockResult()
    }

    override suspend fun grantSelfPermissions(): Map<String, Boolean> = emptyMap()
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
