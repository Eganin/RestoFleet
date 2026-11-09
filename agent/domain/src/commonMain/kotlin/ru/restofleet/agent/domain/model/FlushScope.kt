package ru.restofleet.agent.domain.model

/** Что досылает `FlushOutbox` (решение 172). */
enum class FlushScope {
    /** Весь outbox в порядке `peek`, пачками; между пачками замок свободен. */
    ALL,

    /**
     * Только итоги команд ([MessageKind.COMMAND_RESULT]) в порядке постановки: коллектор команд не ждёт досылки
     * телеметрии, проб и событий, а порядок `received` → итог внутри `cmd/res` и `seq` событий сохраняется.
     */
    COMMAND_RESULTS,
}
