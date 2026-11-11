package ru.restofleet.server.data.support

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import ru.restofleet.server.data.config.DatabaseConfig
import ru.restofleet.server.data.db.DataSources
import ru.restofleet.server.data.db.ExposedDatabase
import ru.restofleet.server.data.db.ExposedTransactionRunner
import ru.restofleet.server.data.db.Migrations
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction

/**
 * Один контейнер TimescaleDB (PostgreSQL 17, тот же образ, что на стенде) на JVM тестов: миграции применяются один раз,
 * между тестами таблицы очищаются ([reset]). Контейнер убирает Ryuk Testcontainers после завершения JVM.
 */
object TestDatabase {
    const val IMAGE = "timescale/timescaledb:2.31.0-pg17"

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("restofleet")
            .withUsername("restofleet")
            .withPassword("test-only")
            .also { it.start() }
    }

    val config: DatabaseConfig by lazy {
        DatabaseConfig(container.jdbcUrl, container.username, container.password, maxPoolSize = 8)
    }

    val dataSource: HikariDataSource by lazy {
        DataSources.hikari(config).also { migrated = Migrations.migrate(it) }
    }

    /** Сколько миграций применил первый запуск (проверка в `MigrationsTest`). */
    var migrated: Int = -1
        private set

    val database: Database by lazy { ExposedDatabase.connect(dataSource) }

    val tx: TransactionRunner by lazy { ExposedTransactionRunner(database) }

    private val TABLES = listOf(
        "refresh_tokens", "users", "commands", "events", "probe_results", "telemetry", "shadows", "device_sessions",
        "provisioning_tokens", "device_credentials", "devices", "restaurants",
    )

    /** Пустые таблицы перед каждым тестом. */
    fun reset() {
        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("TRUNCATE ${TABLES.joinToString()} RESTART IDENTITY CASCADE") }
            c.commit()
        }
    }

    /** Блок в транзакции [ExposedTransactionRunner] (репозитории требуют открытую транзакцию). */
    fun <T> inTx(block: suspend () -> T): T = runBlocking { tx.inTransaction(block) }

    /** Значение одного столбца одной строки — для проверок, которые порт не отдаёт наружу. */
    fun scalar(sql: String): String? = dataSource.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }.also { c.rollback() }
    }
}
