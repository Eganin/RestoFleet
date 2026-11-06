package ru.restofleet.console.core.mvi.contract

/** Намерение пользователя или внешнего источника: публичный API экрана, единственный вход `onIntent`. */
interface MviIntent

/** Чистое изменение состояния: порождается только ViewModel, потребляется только `Reducer`. */
interface MviAction

/** Сессионное состояние экрана: источник истины внутри ViewModel. */
interface MviState

/** Одноразовое событие для UI (навигация, диалог, сообщение). */
interface MviEffect
