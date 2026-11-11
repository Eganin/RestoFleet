package ru.restofleet.server.simulator.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.restofleet.server.simulator.domain.model.RegistrationReport
import ru.restofleet.server.simulator.domain.model.RegistrationRequest
import ru.restofleet.server.simulator.domain.plan.DevicePlan
import ru.restofleet.server.simulator.domain.port.ControlApi
import ru.restofleet.server.simulator.domain.port.CredentialStore
import ru.restofleet.server.simulator.domain.port.ProvisionOutcome
import ru.restofleet.server.simulator.domain.port.ProvisioningApi
import ru.restofleet.server.simulator.domain.port.ResultSink
import ru.restofleet.shared.kernel.Clock

/**
 * Регистрация виртуальных устройств тем же путём, что агент (FR-SM-08, арх. разд. 10): недостающие до
 * [RegistrationRequest.devices] устройства раскладываются по ресторанам ([DevicePlan]), коды выпускаются пакетами
 * (`tokens:batch`, ≤ 1000 за вызов, по ресторану и роли) и гасятся `/provision` параллельно. Учётки дописываются в
 * кэш по порядку слотов пачками — после обрыва дорегистрация продолжит с места остановки, а уже записанные учётки
 * переиспользуются обоими режимами. Временные отказы (429, 503, сеть) повторяются с паузой.
 */
class RegisterDevices(
    private val control: ControlApi,
    private val provisioning: ProvisioningApi,
    private val store: CredentialStore,
    private val sink: ResultSink,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: RegistrationRequest): RegistrationReport {
        val started = clock.nowMillis()
        val cached = store.load().size
        val failures = mutableMapOf<String, Int>()
        var registered = 0
        if (cached < request.devices) {
            val slots = DevicePlan.slots(control.restaurants(), request.mix, cached, request.devices)
            val codes = arrayOfNulls<String>(slots.size)
            slots.withIndex().groupBy({ it.value }, { it.index }).forEach { (slot, indices) ->
                indices.chunked(MAX_BATCH).forEach { chunk ->
                    control.issueCodes(slot.restaurantId, slot.role, chunk.size).zip(chunk)
                        .forEach { (code, index) -> codes[index] = code }
                }
            }
            val gate = Semaphore(request.parallelism)
            codes.toList().chunked(request.parallelism * CHUNK_FACTOR).forEach { chunk ->
                val outcomes = coroutineScope {
                    chunk.map { code -> async { gate.withPermit { provision(code, request) } } }.awaitAll()
                }
                outcomes.forEach { outcome ->
                    when (outcome) {
                        is ProvisionOutcome.Registered -> {
                            store.append(outcome.credentials)
                            registered++
                        }
                        is ProvisionOutcome.Retry -> failures.merge(outcome.reason, 1, Int::plus)
                        is ProvisionOutcome.Rejected -> failures.merge(outcome.reason, 1, Int::plus)
                    }
                }
            }
        }
        val report = RegistrationReport(
            requested = request.devices,
            cached = cached,
            registered = registered,
            failed = failures.values.sum(),
            durationMs = clock.nowMillis() - started,
            failures = failures.toSortedMap(),
        )
        sink.registration(report)
        return report
    }

    private suspend fun provision(code: String?, request: RegistrationRequest): ProvisionOutcome {
        if (code == null) return ProvisionOutcome.Rejected(NO_CODE)
        var attempt = 1
        while (true) {
            val outcome = provisioning.provision(code)
            if (outcome !is ProvisionOutcome.Retry || attempt >= request.maxAttempts) return outcome
            delay(request.retryDelayMs * attempt)
            attempt++
        }
    }

    private companion object {
        const val MAX_BATCH = 1_000
        const val CHUNK_FACTOR = 8
        const val NO_CODE = "no_code"
    }
}

