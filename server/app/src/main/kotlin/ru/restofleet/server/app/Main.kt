package ru.restofleet.server.app

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import ru.restofleet.server.app.config.AppConfig
import ru.restofleet.server.app.config.AppConfigLoader
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.app.config.SecretFiles
import ru.restofleet.server.app.di.databaseModules
import ru.restofleet.server.app.seed.SeedFiles
import ru.restofleet.server.app.seed.Seeder
import ru.restofleet.server.app.serve.serve
import ru.restofleet.server.data.db.Migrations
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import javax.sql.DataSource
import kotlin.system.exitProcess

private val log = KotlinLogging.logger("ru.restofleet.server.app.Main")

fun main(args: Array<String>) {
    val code = run(args.toList(), System.getenv())
    if (code != 0) exitProcess(code)
}

/** Точка входа без `exitProcess` (тестируется): код возврата процесса. */
fun run(args: List<String>, env: Map<String, String>): Int {
    val command = when (val parsed = Cli.parse(args)) {
        is Outcome.Success -> parsed.value
        is Outcome.Failure -> {
            System.err.println(parsed.error)
            return EXIT_USAGE
        }
    }
    val config = AppConfigLoader.load(env)
    return when (command) {
        Command.Serve -> {
            serve(config)
            0
        }
        Command.Migrate -> migrate(config)
        is Command.Seed -> seed(config, command)
    }
}

/** `migrate`: Flyway `classpath:db/migration`, число применённых — в лог. */
fun migrate(config: AppConfig): Int = withDatabase(config) { koin ->
    val applied = Migrations.migrate(koin.get<DataSource>())
    log.info { "migrations applied: $applied" }
    0
}

/** `seed`: рестораны и пользователи из YAML через use cases (upsert по `code`), пароли — из каталога секретов. */
fun seed(config: AppConfig, command: Command.Seed): Int = withDatabase(config) { koin ->
    val restaurants = SeedFiles.restaurants(File(command.restaurants).readText())
    val users = SeedFiles.users(File(command.users).readText())
    val secretsDir = command.secretsDir ?: config.secretsDir
    val report = runBlocking {
        Seeder(koin.get(), koin.get()).run(restaurants, users) { SecretFiles.readNamed(secretsDir, it.passwordSecret) }
    }
    log.info { "seed: restaurants created ${report.created}, updated ${report.updated}; users ${report.users}" }
    report.errors.forEach { log.error { "seed: $it" } }
    if (report.ok) 0 else EXIT_SEED_FAILED
}

private fun withDatabase(config: AppConfig, block: (org.koin.core.Koin) -> Int): Int {
    val app = koinApplication { modules(databaseModules(RuntimeConfig.forDatabaseOnly(config))) }
    return try {
        block(app.koin)
    } finally {
        app.close()
    }
}

private const val EXIT_USAGE = 2
private const val EXIT_SEED_FAILED = 1
