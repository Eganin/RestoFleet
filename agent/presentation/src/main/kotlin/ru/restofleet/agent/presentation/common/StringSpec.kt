package ru.restofleet.agent.presentation.common

import androidx.annotation.StringRes

/** Строковый ресурс и его аргументы: чистая функция выбора, сам `getString` остаётся в UI. */
data class StringSpec(@param:StringRes val res: Int, val args: List<String> = emptyList())
