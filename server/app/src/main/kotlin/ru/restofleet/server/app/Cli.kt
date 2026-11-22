package ru.restofleet.server.app

import ru.restofleet.shared.kernel.Outcome

/** Команда CLI бэкенда (план 2.4). */
sealed interface Command {
    /** HTTP API + ingest + фоновые задачи (по умолчанию). Миграции не применяет — это отдельная команда `migrate`. */
    data object Serve : Command

    /** Миграции Flyway и выход (одноразовый сервис `migrate` в compose). */
    data object Migrate : Command

    /** Dev-seed стенда: рестораны (upsert по `code`) и пользователи через use cases. */
    data class Seed(val restaurants: String, val users: String, val secretsDir: String?) : Command
}

/**
 * Разбор аргументов: `serve` | `migrate` | `seed --restaurants <yaml> --users <yaml> [--secrets-dir <dir>]`. Пароли
 * аргументами не принимаются (реш. 252): у `seed` — только имя секрета в `users.yaml`.
 */
object Cli {
    const val USAGE = "usage: restofleet-backend [serve | migrate | " +
        "seed --restaurants <file> --users <file> [--secrets-dir <dir>]]"

    private val SEED_OPTIONS = setOf("--restaurants", "--users", "--secrets-dir")

    fun parse(args: List<String>): Outcome<Command, String> = when (args.firstOrNull()) {
        null, "serve" -> if (args.size <= 1) Outcome.Success(Command.Serve) else Outcome.Failure(USAGE)
        "migrate" -> if (args.size == 1) Outcome.Success(Command.Migrate) else Outcome.Failure(USAGE)
        "seed" -> seed(args.drop(1))
        else -> Outcome.Failure(USAGE)
    }

    private fun seed(args: List<String>): Outcome<Command, String> {
        val pairs = args.chunked(2).filter { it.size == 2 }.map { (key, value) -> key to value }
        val options = pairs.toMap()
        // Нечётное число, повтор или неизвестный ключ — ошибка, а не молчаливый пропуск
        val wellFormed =
            pairs.size * 2 == args.size && options.size == pairs.size && SEED_OPTIONS.containsAll(options.keys)
        val restaurants = options["--restaurants"].takeIf { wellFormed }
        val users = options["--users"].takeIf { wellFormed }
        return if (restaurants != null && users != null) {
            Outcome.Success(Command.Seed(restaurants, users, options["--secrets-dir"]))
        } else {
            Outcome.Failure(USAGE)
        }
    }
}
