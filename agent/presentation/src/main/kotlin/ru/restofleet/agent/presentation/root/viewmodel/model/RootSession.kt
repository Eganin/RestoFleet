package ru.restofleet.agent.presentation.root.viewmodel.model

import ru.restofleet.console.core.mvi.contract.MviState

/** Экраны агента верхнего уровня; Lab (с вкладкой Device Owner) — только debug (реш. 140). */
enum class AgentScreen { LOADING, SETUP, STATUS, LAB }

/**
 * Источник истины навигации агента (план 3.6): без учётки — мастер, с учёткой — статус; Lab открывается из мастера
 * или статуса, если [labAvailable], и «назад» возвращает туда же ([labReturnTo]).
 */
data class RootSession(
    val screen: AgentScreen = AgentScreen.LOADING,
    val labAvailable: Boolean = false,
    val labReturnTo: AgentScreen? = null,
) : MviState
