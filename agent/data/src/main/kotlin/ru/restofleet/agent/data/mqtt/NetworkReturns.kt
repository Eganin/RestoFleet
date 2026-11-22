package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Счётчик возвратов сети (V-4): растёт на каждом переходе «сети нет → сеть есть». Сессия читает [current] в начале
 * паузы и ждёт [awaitReturnFrom]; возврат, случившийся до паузы, её не прерывает. Неизвестное начальное состояние
 * считается «сети нет»: колбэк Android без сети молчит, и первый `available` после старта без сети не теряется.
 * [onNetwork] вызывает один сборщик.
 */
class NetworkReturns {
    private val version = MutableStateFlow(0L)
    private var available = false

    fun current(): Long = version.value

    fun onNetwork(isAvailable: Boolean) {
        if (isAvailable && !available) version.value += 1
        available = isAvailable
    }

    suspend fun awaitReturnFrom(seen: Long) {
        version.first { it != seen }
    }
}

/** Ожидания цикла сессии между попытками подключения: смена учётки ([changes]) и возврат сети ([returns], V-4). */
internal class ReconnectWaits(private val changes: CredentialChanges, private val returns: NetworkReturns) {
    /**
     * Пауза по [ReconnectPauses] (`null` — ждать смены учётки); любую паузу прерывает смена учётки, паузу
     * ретрайного отказа — ещё и возврат сети. Пауза `auth_failed` возврату сети не уступает.
     */
    suspend fun pauseAfter(error: MqttError, seen: Long, pauses: ReconnectPauses) {
        val pause = pauses.next(error)
        val network = returns.current()
        when {
            pause == null -> changes.awaitChangeFrom(seen)
            error == MqttError.AuthFailed -> changes.awaitChangeOrTimeout(seen, pause)
            else -> withTimeoutOrNull(pause) {
                channelFlow {
                    launch { changes.awaitChangeFrom(seen).also { send(Unit) } }
                    launch { returns.awaitReturnFrom(network).also { send(Unit) } }
                }.first()
            }
        }
    }

    /** Ждёт возврата сети, при котором [lost] (соединение потеряно, клиент ждёт автопереподключения). */
    suspend fun awaitNetworkBack(lost: () -> Boolean) {
        do {
            returns.awaitReturnFrom(returns.current())
        } while (!lost())
    }
}
