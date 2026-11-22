package ru.restofleet.server.simulator.app

import java.nio.file.Path

/** Команда симулятора. */
enum class Command(val code: String) {
    /** Дорегистрировать устройства до N (FR-SM-08) и выйти. */
    REGISTER("register"),

    /** Запустить устройства сценария без измерителя (демонстрация, FR-SM-01). */
    RUN("run"),

    /** Прогон эксперимента: устройства + команды измерителя + замер пропажи + CSV (ТЗ 13.2). */
    EXPERIMENT("experiment"),

    /** Парк точности (реш. 428): рестораны `fleet` сценария и регистрация их устройств в отдельный кэш учёток. */
    PREPARE("prepare"),
}

/** Ошибка командной строки: текст и справка — в stderr, код выхода 2. */
class CliException(message: String) : IllegalArgumentException(message)

/**
 * Параметры командной строки. Пути по умолчанию — для контейнера `simulator` (compose, profile `experiment`):
 * сценарии `/scenarios`, кэш учёток `/var/lib/simulator/creds.jsonl` (том `deploy/out/sim`, 600), результаты
 * `/results` (`logs/experiments` вне git), CA стенда `/certs/ca.crt`, пароль `admin` — compose-секрет. Пароль читается
 * из файла в момент входа и никуда не печатается.
 */
data class CliOptions(
    val command: Command,
    val scenario: Path,
    val devices: Int? = null,
    val credentials: Path = Path.of("/var/lib/simulator/creds.jsonl"),
    val results: Path = Path.of("/results"),
    val caFile: Path = Path.of("/certs/ca.crt"),
    val adminLogin: String = "admin",
    val adminPasswordFile: Path = Path.of("/run/secrets/admin-password"),
    val parallelism: Int = DEFAULT_PARALLELISM,
    /** Куда писать эталон отказов `truth-<run>.csv` (том `deploy/out/sim`). */
    val truthDir: Path = Path.of("/var/lib/simulator"),
) {
    companion object {
        const val DEFAULT_PARALLELISM = 8

        const val USAGE = """Использование: restofleet-simulator <register|run|experiment|prepare> --scenario <файл>
  --devices N               сколько устройств (по умолчанию — из сценария; register дорегистрирует до N)
  --creds <файл>            кэш учёток, JSON Lines, права 600 (/var/lib/simulator/creds.jsonl)
  --results <каталог>       результаты CSV (/results)
  --ca <файл>               CA стенда (/certs/ca.crt)
  --admin-login <логин>     оператор REST API (admin)
  --admin-password-file <файл>  пароль оператора (/run/secrets/admin-password)
  --parallel N              параллельных /provision при регистрации (8)
  --truth-dir <каталог>     эталон отказов truth-<run>.csv (/var/lib/simulator)"""

        fun parse(args: List<String>): CliOptions {
            val command = Command.entries.firstOrNull { it.code == args.firstOrNull() }
                ?: fail("неизвестная команда: ${args.firstOrNull() ?: "(нет)"}")
            val map = keyValues(args.drop(1))
            val defaults = CliOptions(command, Path.of(map["scenario"] ?: fail("нужен --scenario")))
            return defaults.copy(
                devices = map["devices"]?.let { positive("devices", it) },
                credentials = map["creds"]?.let(Path::of) ?: defaults.credentials,
                results = map["results"]?.let(Path::of) ?: defaults.results,
                caFile = map["ca"]?.let(Path::of) ?: defaults.caFile,
                adminLogin = map["admin-login"] ?: defaults.adminLogin,
                adminPasswordFile = map["admin-password-file"]?.let(Path::of) ?: defaults.adminPasswordFile,
                parallelism = map["parallel"]?.let { positive("parallel", it) } ?: defaults.parallelism,
                truthDir = map["truth-dir"]?.let(Path::of) ?: defaults.truthDir,
            )
        }

        private val KEYS = setOf(
            "scenario", "devices", "creds", "results", "ca", "admin-login", "admin-password-file", "parallel",
            "truth-dir",
        )

        private fun keyValues(pairs: List<String>): Map<String, String> {
            if (pairs.size % 2 != 0) fail("параметры — пары --ключ значение")
            val map = pairs.chunked(2).associate { (key, value) ->
                if (!key.startsWith("--")) fail("ожидался --ключ, получено: $key")
                key.removePrefix("--") to value
            }
            val unknown = map.keys - KEYS
            if (unknown.isNotEmpty()) fail("неизвестные параметры: ${unknown.joinToString()}")
            return map
        }

        private fun positive(key: String, value: String): Int =
            value.toIntOrNull()?.takeIf { it > 0 } ?: fail("--$key: нужно целое > 0, получено: $value")

        private fun fail(message: String): Nothing = throw CliException(message)
    }
}
