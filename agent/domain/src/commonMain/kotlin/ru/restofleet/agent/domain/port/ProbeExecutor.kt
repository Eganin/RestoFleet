package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget

/** Выполняет одну пробу доступности. Не бросает: любой сбой — [ProbeResult] с `ok=false` и классом ошибки. */
fun interface ProbeExecutor {
    suspend fun probe(target: ProbeTarget): ProbeResult
}
