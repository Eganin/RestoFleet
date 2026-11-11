package ru.restofleet.server.domain.fake

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.shared.kernel.Outcome

/** Транзакция над [InMemoryDb]: Failure и исключение откатывают состояние; [failNextCommit] имитирует сбой commit. */
class FakeTransactionRunner(private val db: InMemoryDb) : TransactionRunner {
    var commits = 0
        private set
    var rollbacks = 0
        private set
    var failNextCommit = false

    override suspend fun <T, E> transactional(block: suspend () -> Outcome<T, E>): Outcome<T, E> {
        check(!db.inTransaction) { "nested transaction" }
        val before = db.state
        db.inTransaction = true
        try {
            val result = try {
                block()
            } catch (e: Exception) {
                rollback(before)
                throw e
            }
            when {
                result is Outcome.Failure -> rollback(before)
                failNextCommit -> {
                    failNextCommit = false
                    rollback(before)
                    throw IllegalStateException("commit failed")
                }
                else -> commits++
            }
            return result
        } finally {
            db.inTransaction = false
        }
    }

    private fun rollback(before: DbState) {
        db.state = before
        rollbacks++
    }
}
