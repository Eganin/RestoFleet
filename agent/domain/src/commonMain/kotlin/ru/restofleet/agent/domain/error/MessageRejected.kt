package ru.restofleet.agent.domain.error

import ru.restofleet.shared.kernel.DomainError

/**
 * Брокер окончательно отверг именно это сообщение (например, PUBACK `0x87`: нет прав на топик), связь при этом
 * исправна. Повтор того же сообщения не поможет: досылка удаляет такую запись и идёт дальше, а `PublishOrBuffer` её
 * не буферизует (решение 171). Транспортные сбои и таймауты этим признаком не помечаются.
 */
interface MessageRejected : DomainError
