package ru.restofleet.agent.presentation.lab.viewmodel.model

import ru.restofleet.agent.domain.capability.CheckId

/** Кнопка секции «Команды»: [checkId] — строка матрицы, в которую попадает результат. */
enum class LabCommand(val checkId: CheckId) {
    SHOW_MESSAGE(CheckId.C1),
    LAUNCH_APP(CheckId.C2),
    LAUNCH_APP_DELAYED(CheckId.C3),
    BEEP(CheckId.C4),
    ;

    companion object {
        fun of(id: CheckId): LabCommand? = entries.firstOrNull { it.checkId == id }
    }
}
