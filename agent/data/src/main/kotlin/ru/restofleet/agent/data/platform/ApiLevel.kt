package ru.restofleet.agent.data.platform

import android.os.Build

/** Текущий уровень API; в тестах подменяется фиксированным значением. */
fun interface ApiLevel {
    fun current(): Int

    companion object {
        val Device = ApiLevel { Build.VERSION.SDK_INT }
    }
}
