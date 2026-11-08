package ru.restofleet.agent.presentation.deviceowner.viewmodel

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.usecase.FetchApkChecksum
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.GrantSelfPermissions
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.presentation.common.guarded
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent.User
import ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper.DeviceOwnerUiStateMapper
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/**
 * Экран Device Owner. Состояние меняется только на главном потоке (scope — `Main.immediate`); use case идут через
 * [io]. Перезагрузка (D2) выполняется только после ответа на диалог: [rebootDecision] ждёт `User.ConfirmReboot`,
 * без ответа корутина просто стоит, `Reboot` не вызывается никогда.
 */
@Suppress("LongParameterList", "TooManyFunctions") // по одному use case на действие экрана
class DeviceOwnerViewModel(
    private val runChecks: RunCapabilityChecks,
    private val runCommand: RunCommandCheck,
    private val grantPermissions: GrantSelfPermissions,
    private val fetchChecksum: FetchApkChecksum,
    private val getDeviceInfo: GetDeviceInfo,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope = defaultScope(),
) : BaseMviViewModel<DeviceOwnerSession, DeviceOwnerUiState, DeviceOwnerIntent, DeviceOwnerAction, DeviceOwnerEffect>(
    store = MviStore(DeviceOwnerSession(), DeviceOwnerReducer(), DeviceOwnerUiStateMapper()::map),
    scope = scope,
) {
    private var rebootDecision: CompletableDeferred<Boolean>? = null

    init {
        refresh()
    }

    override fun onIntent(intent: DeviceOwnerIntent) {
        when (intent) {
            User.Refresh -> refresh()
            User.RunAll -> runAll()
            is User.RunOne -> runOne(intent.id)
            User.RunLaunchFromBackground -> if (!isBlocked(CheckId.D5)) {
                runRow(CheckId.D5) {
                    delay(BACKGROUND_DELAY_MS)
                    execute(CheckId.D5)
                }
            }
            is User.EditApkUrl -> reduce(DeviceOwnerAction.ApkUrlChanged(intent.text))
            is User.EditApkSha256 -> reduce(DeviceOwnerAction.ApkSha256Changed(intent.text))
            is User.EditTargetPackage -> reduce(DeviceOwnerAction.TargetPackageChanged(intent.text))
            is User.EditTargetUrl -> reduce(DeviceOwnerAction.TargetUrlChanged(intent.text))
            User.FetchChecksum -> fetchHash()
            is User.ConfirmReboot -> rebootDecision?.complete(intent.confirmed)
        }
    }

    private fun refresh() {
        if (state.isRunningAll || state.running.any { it in REFRESH_IDS }) return
        val ids = REFRESH_IDS.toSet()
        reduce(DeviceOwnerAction.RunStarted(ids))
        scope.launch {
            try {
                loadDevice()
                for (id in REFRESH_IDS) {
                    guarded(::emitError) { execute(id) }?.let { reduce(DeviceOwnerAction.ResultsArrived(listOf(it))) }
                }
            } finally {
                reduce(DeviceOwnerAction.RunFinished(ids))
            }
        }
    }

    private suspend fun loadDevice(): AgentMode? =
        guarded(::emitError) { withContext(io) { getDeviceInfo() } }?.also {
            reduce(DeviceOwnerAction.DeviceLoaded(it))
        }?.mode

    private fun runOne(id: CheckId) {
        if (isBlocked(id)) return
        runRow(id) { if (id == CheckId.D2) rebootWithConfirmation() else execute(id) }
    }

    /** Строка не из экрана, DO-строка без роли или D3 без URL/хэша: кнопка недоступна, интент игнорируется. */
    private fun isBlocked(id: CheckId): Boolean =
        id !in DeviceOwnerUiStateMapper.ROWS ||
            (id in DeviceOwnerUiStateMapper.DEVICE_OWNER_ONLY && state.device?.mode == AgentMode.NORMAL) ||
            (id == CheckId.D3 && skipReasonOfInstall() != null)

    private fun runRow(id: CheckId, block: suspend () -> CheckResult) {
        if (state.isRunningAll || id in state.running) return
        reduce(DeviceOwnerAction.RunStarted(setOf(id)))
        scope.launch {
            try {
                guarded(::emitError, block)?.let { reduce(DeviceOwnerAction.ResultsArrived(listOf(it))) }
            } finally {
                reduce(DeviceOwnerAction.RunFinished(setOf(id)))
            }
        }
    }

    /**
     * Порядок: D1, D6, R10, R13, D3, D5, D4, затем D2 — последней и только после подтверждения. Без роли Device Owner
     * строки [DeviceOwnerUiStateMapper.DEVICE_OWNER_ONLY] не исполняются (NA), диалога нет.
     */
    private fun runAll() {
        if (state.isRunningAll) return
        val ids = RUN_ALL_ORDER.toSet()
        reduce(DeviceOwnerAction.RunStarted(ids, isAll = true))
        scope.launch {
            try {
                val isOwner = loadDevice() == AgentMode.DEVICE_OWNER
                for (id in RUN_ALL_ORDER) {
                    val result = guarded(::emitError) { runAllStep(id, isOwner) }
                    reduce(
                        result?.let { DeviceOwnerAction.ResultsArrived(listOf(it)) }
                            ?: DeviceOwnerAction.RunFinished(setOf(id)),
                    )
                }
            } finally {
                reduce(DeviceOwnerAction.RunFinished(ids))
            }
        }
    }

    private suspend fun runAllStep(id: CheckId, isOwner: Boolean): CheckResult = when {
        id in DeviceOwnerUiStateMapper.DEVICE_OWNER_ONLY && !isOwner ->
            synthetic(id, CheckStatus.NOT_APPLICABLE, REQUIRES_DEVICE_OWNER)
        id == CheckId.D3 -> skipReasonOfInstall()?.let { synthetic(id, CheckStatus.NOT_CHECKED, it) } ?: execute(id)
        id == CheckId.D2 -> rebootWithConfirmation()
        else -> execute(id)
    }

    /** Причина, по которой D3 нельзя запустить, либо `null`. */
    private fun skipReasonOfInstall(): String? = when {
        state.apkUrl.isBlank() -> SKIPPED_NO_URL
        state.apkSha256.isBlank() -> SKIPPED_NO_HASH
        else -> null
    }

    private suspend fun rebootWithConfirmation(): CheckResult {
        val decision = CompletableDeferred<Boolean>()
        rebootDecision = decision
        emitEffect(DeviceOwnerEffect.ConfirmReboot)
        val confirmed = try {
            decision.await()
        } finally {
            rebootDecision = null
        }
        return if (confirmed) {
            withContext(io) { runCommand(CheckId.D2, Command.Reboot) }
        } else {
            synthetic(CheckId.D2, CheckStatus.NOT_CHECKED, NOT_RUN)
        }
    }

    private suspend fun execute(id: CheckId): CheckResult {
        val s = state
        val target = s.targetUrl.trim().ifEmpty { null }
        return withContext(io) {
            when (id) {
                CheckId.D2 -> runCommand(id, Command.Reboot)
                CheckId.D3 -> runCommand(id, Command.InstallApk(s.apkUrl.trim(), s.apkSha256.trim()))
                CheckId.D4 -> runCommand(id, Command.LockTask(s.targetPackage.trim(), target))
                CheckId.D5 -> runCommand(id, Command.LaunchApp(s.targetPackage.trim(), target))
                CheckId.D6 -> grantPermissions()
                else -> runChecks(setOf(id)).single()
            }
        }
    }

    private fun synthetic(id: CheckId, status: CheckStatus, note: String) =
        CheckResult(id, status, emptyMap(), note, clock.nowMillis())

    private fun fetchHash() {
        val url = state.apkUrl.trim()
        if (url.isEmpty() || state.isFetchingHash) return
        reduce(DeviceOwnerAction.FetchingHashChanged(true))
        scope.launch {
            try {
                guarded(::emitError) { withContext(io) { fetchChecksum(url) } }?.let { outcome ->
                    when (outcome) {
                        is Outcome.Success -> {
                            reduce(DeviceOwnerAction.ApkSha256Changed(outcome.value))
                            emitEffect(DeviceOwnerEffect.ShowMessage(DeviceOwnerMessage.HashFetched))
                        }
                        is Outcome.Failure ->
                            emitEffect(DeviceOwnerEffect.ShowMessage(DeviceOwnerMessage.HashFailed(outcome.error.code)))
                    }
                }
            } finally {
                reduce(DeviceOwnerAction.FetchingHashChanged(false))
            }
        }
    }

    private fun emitError(e: Exception) {
        emitEffect(
            DeviceOwnerEffect.ShowMessage(DeviceOwnerMessage.Error(e.message ?: e::class.simpleName.orEmpty())),
        )
    }

    private companion object {
        val REFRESH_IDS = listOf(CheckId.D1, CheckId.R10, CheckId.R13)
        val RUN_ALL_ORDER = listOf(
            CheckId.D1, CheckId.D6, CheckId.R10, CheckId.R13, CheckId.D3, CheckId.D5, CheckId.D4, CheckId.D2,
        )
        const val BACKGROUND_DELAY_MS = 10_000L
        const val REQUIRES_DEVICE_OWNER = "requires_device_owner"
        const val SKIPPED_NO_URL = "пропущено: нет URL"
        const val SKIPPED_NO_HASH = "пропущено: нет хэша"
        const val NOT_RUN = "не запускалась"
    }
}

/** Последняя линия обороны: необработанное исключение корутины VM — в лог, а не в крах процесса агента. */
internal fun deviceOwnerExceptionHandler(log: (Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e -> log(e) }

private fun defaultScope() = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate +
        deviceOwnerExceptionHandler { Log.e("DeviceOwnerViewModel", "unhandled", it) },
)
