package ru.restofleet.agent.presentation.status.viewmodel

import ru.restofleet.console.core.mvi.contract.MviEffect

/** Одноразовых событий у экрана статуса нет: он только показывает снимок, навигация — через роутер. */
sealed interface StatusEffect : MviEffect
