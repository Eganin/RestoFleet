package ru.restofleet.agent

import androidx.annotation.StringRes

/** Вкладки верхнего уровня агента. Навигация Decompose живёт только в консоли, агенту хватает двух вкладок. */
enum class AgentTab(@param:StringRes val label: Int) {
    LAB(R.string.tab_lab),
    DEVICE_OWNER(R.string.tab_device_owner),
}
