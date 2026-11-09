package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.UserNotifier
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Исполняет команду: проверка возможностей (`Unsupported` без обращения к портам), отказ из-за киоска
 * (`Failed("kiosk_active")`, решение 141), затем вызов порта.
 * Сбой порта изолируется (решение 78): `Exception` -> `Failed("exception:<Класс>")`, `Error` и настоящая отмена
 * вызывающего пробрасываются. Таймаут исполнения и срок `expiresAt` здесь не проверяются (задача 3.4, M3).
 */
class ExecuteCommand(
    private val caps: CapabilitiesSource,
    private val admin: DeviceAdmin,
    private val notifier: UserNotifier,
    private val launcher: AppLauncher,
    private val beeper: Beeper,
    private val clock: Clock,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(command: Command): CommandOutcome =
        try {
            val capabilities = caps.current()
            val reason = capabilities.check(command)
            when {
                reason != null -> CommandOutcome.Unsupported(reason)
                else -> capabilities.kioskRefusal(command)?.let { CommandOutcome.Failed(it) }
                    ?: execute(command, capabilities)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failed(e)
        } catch (e: Exception) {
            failed(e)
        }

    private suspend fun execute(command: Command, capabilities: Capabilities): CommandOutcome = when (command) {
        Command.Ping -> CommandOutcome.Succeeded(mapOf("deviceTime" to clock.nowMillis().toString()))
        is Command.ShowMessage -> notifier.show(command.text, command.level).toOutcome()
        is Command.LaunchApp ->
            launcher.launch(command.pkg, command.url, capabilities.launchStrategy()).toOutcome()
        is Command.Beep -> beeper.beep(command.durationMs).toOutcome()
        Command.Reboot -> admin.reboot().toOutcome()
        is Command.InstallApk -> admin.installApk(command.pkg, command.url, command.sha256).toOutcome()
        is Command.LockTask -> admin.startLockTask(command.pkg, command.url).toOutcome()
    }

    private fun Outcome<Unit, DomainError>.toOutcome(): CommandOutcome = when (this) {
        is Outcome.Success -> CommandOutcome.Succeeded()
        is Outcome.Failure -> CommandOutcome.Failed(error.code)
    }

    @JvmName("mapResultToOutcome")
    private fun Outcome<Map<String, String>, DomainError>.toOutcome(): CommandOutcome = when (this) {
        is Outcome.Success -> CommandOutcome.Succeeded(value)
        is Outcome.Failure -> CommandOutcome.Failed(error.code)
    }

    private fun failed(e: Exception): CommandOutcome =
        CommandOutcome.Failed("exception:${e::class.simpleName ?: "Unknown"}")
}
