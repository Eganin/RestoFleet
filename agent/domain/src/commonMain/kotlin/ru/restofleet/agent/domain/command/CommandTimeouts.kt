package ru.restofleet.agent.domain.command

/** Потолок времени исполнения команды (решение 3.4): по его истечении ответ `failed`/`timeout`. */
object CommandTimeouts {
    const val DEFAULT_MS = 60_000L
    const val INSTALL_APK_MS = 600_000L

    fun forCommand(command: Command): Long = when (command) {
        is Command.InstallApk -> INSTALL_APK_MS
        else -> DEFAULT_MS
    }
}
