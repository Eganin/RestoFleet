package ru.restofleet.shared.protocol

/**
 * Значения `cmd/req.type` (ТЗ 6.1, FR-AG-60…69; параметры — реш. 221). Поле в DTO — строка: неизвестный тип от более
 * новой стороны агент отвечает `unsupported` / `unknown_type:<тип>`.
 */
object CommandTypes {
    /** Без параметров; `result.deviceTime` — время устройства, мс. */
    const val PING = "ping"

    /** `text` (1..1000), `level` (`info` | `warning` | `critical`, по умолчанию `info`). */
    const val SHOW_MESSAGE = "show_message"

    /** Без параметров: внеочередные телеметрия и прогон всех проб. */
    const val COLLECT_DIAGNOSTICS = "collect_diagnostics"

    /** `pkg`, необязательный `url` (`https://`, для кухни — кухонная станция в браузере). */
    const val LAUNCH_APP = "launch_app"

    /** `target` — `id` цели из `desired.probes`. */
    const val RUN_PROBE = "run_probe"

    /** `durationMs` 1..30 000 (Д). */
    const val BEEP = "beep"

    /** Device Owner, двухфазный (реш. 142). */
    const val REBOOT = "reboot"

    /** Device Owner: `package`, `url`, `sha256`. */
    const val INSTALL_UPDATE = "install_update"

    /** Device Owner: `pkg`, необязательный `url`. */
    const val LOCK_TASK = "lock_task"

    val ALL: Set<String> = setOf(
        PING, SHOW_MESSAGE, COLLECT_DIAGNOSTICS, LAUNCH_APP, RUN_PROBE, BEEP, REBOOT, INSTALL_UPDATE, LOCK_TASK,
    )
}
