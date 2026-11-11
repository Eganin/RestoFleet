package ru.restofleet.agent.domain.command

/** Уровень важности сообщения персоналу (`params.level` у `show_message`, ТЗ раздел 7). */
enum class Level { INFO, WARNING, CRITICAL }

/**
 * Команда агенту. Соответствие типам протокола (маппинг в M3): [Ping] — `ping`, [ShowMessage] — `show_message`,
 * [LaunchApp] — `launch_app`, [Beep] — `beep`, [Reboot] — `reboot`, [InstallApk] — `install_update`,
 * [LockTask] — `lock_task`, [RunProbe] — `run_probe`, [CollectDiagnostics] — `collect_diagnostics`.
 */
sealed interface Command {
    data object Ping : Command
    data class ShowMessage(val text: String, val level: Level) : Command
    data class LaunchApp(val pkg: String, val url: String?) : Command
    data class Beep(val durationMs: Long) : Command
    data object Reboot : Command
    data class InstallApk(val pkg: String, val url: String, val sha256: String) : Command
    data class LockTask(val pkg: String, val url: String?) : Command

    /** Внеочередная проба цели [target] — `id` из `desired.probes` (FR-AG-64, реш. 221). */
    data class RunProbe(val target: String) : Command

    /** Внеочередные телеметрия и прогон всех проб (FR-AG-62, реш. 221). */
    data object CollectDiagnostics : Command
}
