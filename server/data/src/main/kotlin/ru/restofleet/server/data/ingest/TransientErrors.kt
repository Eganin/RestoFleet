package ru.restofleet.server.data.ingest

import java.net.ConnectException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * Временный ли сбой (стоит повторить ту же пачку): нет соединения с БД, исчерпан пул, БД перезапускается,
 * сериализация/дедлок. Детерминированные ошибки (нарушение ограничения, баг) — нет: такую пачку разбирают по одному.
 */
object TransientErrors {
    private val TRANSIENT_SQL_STATES = listOf("08", "40", "53", "57")
    private const val MAX_DEPTH = 10

    fun isTransient(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(MAX_DEPTH).any { cause ->
        cause is SQLTransientException ||
            cause is SQLRecoverableException ||
            cause is ConnectException ||
            (cause is SQLException && TRANSIENT_SQL_STATES.any { cause.sqlState?.startsWith(it) == true })
    }
}
