package ru.restofleet.agent.presentation.setup.viewmodel

import ru.restofleet.console.core.mvi.contract.MviEffect

/** Одноразовых событий у мастера нет: ошибка регистрации — часть состояния, навигация — через роутер. */
sealed interface SetupEffect : MviEffect
