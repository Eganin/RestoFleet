package ru.restofleet.shared.kernel

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Порт генерации идентификаторов (cmdId, connId, bootId…). */
fun interface IdGenerator {
    fun newId(): String
}

/** Случайный UUID v4 с необязательным префиксом, например `c-` для команд. */
class UuidIdGenerator(private val prefix: String = "") : IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newId(): String = prefix + Uuid.random().toString()
}
