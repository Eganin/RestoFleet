package ru.restofleet.server.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig as ExposedConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import ru.restofleet.server.data.config.DatabaseConfig
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.shared.kernel.Outcome
import java.sql.Connection
import javax.sql.DataSource

/** Пул соединений: HikariCP, `READ COMMITTED`, без autocommit (транзакции задаёт [ExposedTransactionRunner]). */
object DataSources {
    fun hikari(config: DatabaseConfig): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            connectionTimeout = config.connectionTimeoutMs
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "restofleet"
        },
    )
}

/** Миграции Flyway из `classpath:db/migration` (`V1__core.sql`…). Вызывает CLI `migrate` и тесты. */
object Migrations {
    /** Возвращает число применённых миграций. */
    fun migrate(dataSource: DataSource): Int = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
        .migrationsExecuted
}

/** Подключение Exposed к пулу. Повторы транзакций выключены: блок use case может звать брокер (реш. 233). */
object ExposedDatabase {
    fun connect(dataSource: DataSource): Database = Database.connect(
        datasource = dataSource,
        databaseConfig = ExposedConfig {
            defaultMaxAttempts = 1
            defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
        },
    )
}

/**
 * [TransactionRunner] на Exposed: блок выполняется в `suspendTransaction` на [dispatcher] (JDBC блокирующий).
 * `Outcome.Failure` откатывает транзакцию через внутреннее исключение и возвращается как есть.
 */
class ExposedTransactionRunner(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TransactionRunner {
    override suspend fun <T, E> transactional(block: suspend () -> Outcome<T, E>): Outcome<T, E> = try {
        withContext(dispatcher) {
            suspendTransaction(database) {
                val result = block()
                if (result is Outcome.Failure) throw Rollback(result)
                result
            }
        }
    } catch (rollback: Rollback) {
        @Suppress("UNCHECKED_CAST")
        rollback.outcome as Outcome<T, E>
    }

    /** Сигнал отката с бизнес-ошибкой; без стека — это не сбой. */
    private class Rollback(val outcome: Outcome<*, *>) : RuntimeException(null, null, false, false)
}
