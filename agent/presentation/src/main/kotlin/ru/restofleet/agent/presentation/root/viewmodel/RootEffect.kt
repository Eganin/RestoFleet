package ru.restofleet.agent.presentation.root.viewmodel

import ru.restofleet.console.core.mvi.contract.MviEffect

/** Одноразовых событий у навигации нет: экран выбирается состоянием. */
sealed interface RootEffect : MviEffect
