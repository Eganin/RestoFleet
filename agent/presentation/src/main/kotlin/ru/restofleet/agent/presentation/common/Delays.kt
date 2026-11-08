package ru.restofleet.agent.presentation.common

/**
 * Пауза варианта «через 30 с» (C3 в Lab и D5): тестировщик успевает нажать Home, и запуск идёт из фона. Меньше нельзя:
 * около 10 с после ухода приложения действует окно гарантии фонового запуска Activity, внутри него `DIRECT_DO`
 * нельзя отличить от разрешённого запуска (решение 116).
 */
internal const val BACKGROUND_LAUNCH_DELAY_MS = 30_000L
