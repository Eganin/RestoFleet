package ru.restofleet.agent.data.lifecycle

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Построчный файл-журнал с ограничением по числу строк. Потокобезопасен в пределах одного экземпляра.
 *
 * Строка считается записанной, только если после неё есть перевод строки: оборванный хвост (питание пропало
 * посреди записи) при чтении отбрасывается, а перед следующей записью удаляется: иначе обрывок склеился бы
 * со следующей записью в «валидную» строку. Обрезка делается с запасом ([maxLines] / 10) атомарной заменой
 * файла (tmp + rename), чтобы не переписывать файл на каждом тике.
 */
internal class LineFile(private val file: File, private val maxLines: Int) {
    private var count: Int? = null

    @Synchronized
    fun append(line: String) {
        file.parentFile?.mkdirs()
        if (endsMidLine()) dropTornTail()
        file.appendText("$line\n")
        val total = (count ?: readComplete().size - 1) + 1
        count = total
        if (total > maxLines + slack()) trim()
    }

    @Synchronized
    fun lines(): List<String> = readComplete().takeLast(maxLines)

    private fun readComplete(): List<String> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        val complete = if (text.endsWith("\n")) text else text.substringBeforeLast('\n', missingDelimiterValue = "")
        return complete.lines().filter { it.isNotBlank() }
    }

    private fun dropTornTail() {
        val complete = readComplete()
        file.writeText(if (complete.isEmpty()) "" else complete.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun endsMidLine(): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        RandomAccessFile(file, "r").use {
            it.seek(file.length() - 1)
            return it.read() != '\n'.code
        }
    }

    private fun slack() = (maxLines / SLACK_DIVISOR).coerceAtLeast(1)

    private fun trim() {
        val kept = readComplete().takeLast(maxLines)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        count = kept.size
    }

    private companion object {
        const val SLACK_DIVISOR = 10
    }
}
