package ru.restofleet.agent.presentation.status.viewmodel

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.usecase.GetAgentStatus
import ru.restofleet.agent.presentation.common.guarded
import ru.restofleet.agent.presentation.status.api.IStatusRouter
import ru.restofleet.agent.presentation.status.viewmodel.StatusIntent.External
import ru.restofleet.agent.presentation.status.viewmodel.StatusIntent.User
import ru.restofleet.agent.presentation.status.viewmodel.mapper.StatusUiStateMapper
import ru.restofleet.agent.presentation.status.viewmodel.model.StatusSession
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore

/**
 * Экран статуса (FR-AG-05): снимок [GetAgentStatus] читается на [io] при создании, затем каждые [refreshMs], пока
 * экран на переднем плане (`Shown` … `Hidden` от контейнера), и по `Refresh`. Чтения не накладываются (замок):
 * медленный Keystore не порождает очередь. Сбой чтения оставляет прошлый снимок.
 */
class StatusViewModel(
    private val getStatus: GetAgentStatus,
    private val router: IStatusRouter,
    labAvailable: Boolean,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope = defaultScope(),
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
) : BaseMviViewModel<StatusSession, StatusUiState, StatusIntent, StatusAction, StatusEffect>(
    store = MviStore(StatusSession(labAvailable = labAvailable), StatusReducer(), StatusUiStateMapper()::map),
    scope = scope,
) {
    private val loading = Mutex()
    private var polling: Job? = null

    init {
        scope.launch { load() }
    }

    override fun onIntent(intent: StatusIntent) {
        when (intent) {
            User.Refresh -> scope.launch { load() }
            is User.OpenSettings -> router.openSettings(intent.target)
            User.OpenLab -> if (state.labAvailable) router.openLab()
            External.Shown -> if (polling?.isActive != true) {
                polling = scope.launch {
                    while (isActive) {
                        load()
                        delay(refreshMs)
                    }
                }
            }
            External.Hidden -> {
                polling?.cancel()
                polling = null
            }
        }
    }

    private suspend fun load() {
        if (!loading.tryLock()) return
        try {
            reduce(StatusAction.RefreshStarted)
            val status = guarded({}) { withContext(io) { getStatus() } }
            reduce(status?.let { StatusAction.Loaded(it) } ?: StatusAction.RefreshFinished)
        } finally {
            loading.unlock()
        }
    }

    private companion object {
        const val DEFAULT_REFRESH_MS = 3_000L

        fun defaultScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> Log.e("StatusViewModel", "unhandled", e) },
        )
    }
}
