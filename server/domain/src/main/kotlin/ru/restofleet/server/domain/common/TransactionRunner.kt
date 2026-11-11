package ru.restofleet.server.domain.common

import ru.restofleet.shared.kernel.Outcome

/**
 * Граница транзакции БД, которую задаёт use case (план 2.1), без знания об Exposed.
 *
 * Правило: **любой** вызов репозитория идёт внутри [transactional] — реализации репозиториев рассчитывают на открытую
 * транзакцию. [Outcome.Failure] блока откатывает транзакцию, [Outcome.Success] — фиксирует; исключение откатывает и
 * пробрасывается. Побочные эффекты вне БД (публикация в брокер) — только после возврата из [transactional], то есть
 * после commit (P10, реш. 233). Повторов при сбое реализация не делает: блок может звать брокер.
 */
interface TransactionRunner {
    suspend fun <T, E> transactional(block: suspend () -> Outcome<T, E>): Outcome<T, E>
}

/** Транзакция без бизнес-ошибок: чтение или запись, которая не может «не получиться» по правилам домена. */
suspend fun <T> TransactionRunner.inTransaction(block: suspend () -> T): T =
    when (val result = transactional<T, Nothing> { Outcome.Success(block()) }) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> error("unreachable: Nothing failure")
    }
