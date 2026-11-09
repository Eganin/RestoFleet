package ru.restofleet.agent.domain.model

/**
 * Итог досылки outbox: [sent] — отправлено за вызов, [remaining] — сколько записей осталось, [dropped] — сколько
 * записей брокер окончательно отверг и они удалены без повтора (решение 171).
 */
data class FlushResult(val sent: Int, val remaining: Int, val dropped: Int = 0)
