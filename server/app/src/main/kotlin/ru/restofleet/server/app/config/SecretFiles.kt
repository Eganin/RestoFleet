package ru.restofleet.server.app.config

import java.io.File
import java.io.IOException

/**
 * Секреты — только из файлов (реш. 252): Docker secrets в контейнере (`/run/secrets/<имя>`), `deploy/out/…` (600) на
 * хосте. Значение не логируется; в сообщениях об ошибке — только путь. Хвостовой перевод строки отбрасывается
 * (`openssl rand … > file`).
 */
object SecretFiles {
    private val NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    fun read(path: String): String {
        val file = File(path)
        val value = try {
            file.readText().trimEnd('\n', '\r')
        } catch (e: IOException) {
            // В сообщении IOException — путь и причина ОС, содержимого файла там нет
            throw IllegalStateException("secret file is not readable: $path", e)
        }
        check(value.isNotBlank()) { "secret file is empty: $path" }
        return value
    }

    /** Секрет по имени в каталоге [dir] (`users.yaml` → `passwordSecret`): только плоское имя, без путей. */
    fun readNamed(dir: String, name: String): String {
        require(NAME.matches(name)) { "invalid secret name: must match ${NAME.pattern}" }
        return read(File(dir, name).path)
    }
}
