package ru.restofleet.agent.presentation.status.viewmodel.model

import ru.restofleet.agent.domain.model.AgentStatus
import ru.restofleet.console.core.mvi.contract.MviState

/** Источник истины экрана статуса: последний снимок [status] (`null` — ещё не прочитан). */
data class StatusSession(
    val status: AgentStatus? = null,
    val isRefreshing: Boolean = false,
    val labAvailable: Boolean = false,
) : MviState
