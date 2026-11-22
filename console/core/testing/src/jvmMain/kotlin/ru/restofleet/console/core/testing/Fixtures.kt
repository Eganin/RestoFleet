package ru.restofleet.console.core.testing

import java.io.File

/** Загрузчик фикстур контракта `docs/protocol/fixtures` (общие с сервером и `FixtureRoundTripTest`). */
object Fixtures {
    private val dir: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "docs/protocol/fixtures") }
            .firstOrNull { it.isDirectory }
            ?: error("docs/protocol/fixtures not found from ${System.getProperty("user.dir")}")
    }

    /** Текст фикстуры без расширения: `text("rest-console-alert")`. */
    fun text(name: String): String = File(dir, "$name.json").readText()

    fun names(prefix: String): List<String> =
        dir.listFiles().orEmpty().map { it.nameWithoutExtension }.filter { it.startsWith(prefix) }.sorted()
}
