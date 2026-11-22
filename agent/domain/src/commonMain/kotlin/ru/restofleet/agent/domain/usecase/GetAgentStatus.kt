package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.model.AgentStatus
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.ConnectionMonitor
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.agent.domain.probe.ProbeStates
import kotlin.coroutines.cancellation.CancellationException

/**
 * Снимок экрана статуса (FR-AG-05). Каждый источник читается отдельно: сбой одного (Keystore, Room, системный
 * сервис) даёт `null` у своего поля, а не пустой экран (канон 78). Пробы — только цели текущей тени: убранные из
 * `desired` цели не показываются, хотя их автомат ещё в памяти.
 */
class GetAgentStatus(
    private val registration: GetRegistration,
    private val connection: ConnectionMonitor,
    private val caps: CapabilitiesSource,
    private val device: DeviceInfoSource,
    private val outbox: Outbox,
    private val probes: ProbeStates,
    private val shadow: ShadowStore,
) {
    suspend operator fun invoke(): AgentStatus {
        val info = attempt { device.deviceInfo() }
        val targets = attempt { shadow.load()?.probes?.map { it.id }?.toSet() }.orEmpty()
        return AgentStatus(
            registration = registration(),
            connection = connection.current(),
            mode = info?.mode,
            agentVersion = info?.agentVersion,
            granted = attempt { caps.current().granted },
            outboxSize = attempt { outbox.size() },
            probes = probes.statuses.value.filter { it.targetId in targets },
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция источника (канон 78)
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            null
        }
}
