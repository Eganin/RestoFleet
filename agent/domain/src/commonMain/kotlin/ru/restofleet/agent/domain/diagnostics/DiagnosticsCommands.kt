package ru.restofleet.agent.domain.diagnostics

import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.agent.domain.port.WatchedApps
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import ru.restofleet.agent.domain.usecase.PublishTelemetry
import ru.restofleet.agent.domain.usecase.RunProbes

/**
 * Внеочередные проверки (реш. 221): цели берутся из применённой тени, пробы идут через [runProbes] (тот же автомат
 * `up ↔ down` и те же `probe`/`probe_state`, что у плановых). `result` — только строковые значения (ТЗ 7.2).
 *
 * - `run_probe{target}` — одна цель; цели нет в тени — `failed` / `unknown_target`. Неудачная проба — это успешно
 *   исполненная команда: `succeeded` с `ok=false` и классом ошибки;
 * - `collect_diagnostics{}` — снимок телеметрии публикуется (как тик цикла) и прогоняются все цели; в `result` —
 *   `seq` снимка, число проб, успешных, `id` неуспешных и недоступные считывания (через запятую).
 */
class DiagnosticsCommands(
    private val shadow: ShadowStore,
    private val runProbes: RunProbes,
    private val collect: CollectTelemetry,
    private val publishTelemetry: PublishTelemetry,
    private val watched: WatchedApps,
) : OnDemandChecks {
    override suspend fun runProbe(targetId: String): CommandOutcome {
        val target = shadow.load()?.probes?.firstOrNull { it.id == targetId }
            ?: return CommandOutcome.Failed(UNKNOWN_TARGET)
        return CommandOutcome.Succeeded(runProbes(listOf(target)).single().toResult())
    }

    override suspend fun collectDiagnostics(): CommandOutcome {
        val snapshot = collect(watched.packages().toList())
        publishTelemetry(snapshot)
        val results = runProbes(shadow.load()?.probes.orEmpty())
        return CommandOutcome.Succeeded(
            mapOf(
                "seq" to snapshot.seq.toString(),
                "probes" to results.size.toString(),
                "probesOk" to results.count { it.ok }.toString(),
                "probesFailed" to results.filterNot { it.ok }.joinToString(",") { it.targetId },
                "unavailable" to snapshot.unavailable.keys.sorted().joinToString(","),
            ),
        )
    }

    private fun ProbeResult.toResult(): Map<String, String> = buildMap {
        put("target", targetId)
        put("ok", ok.toString())
        httpStatus?.let { put("status", it.toString()) }
        error?.let { put("error", it.code) }
        put("totalMs", totalMs.toString())
    }

    private companion object {
        const val UNKNOWN_TARGET = "unknown_target"
    }
}
