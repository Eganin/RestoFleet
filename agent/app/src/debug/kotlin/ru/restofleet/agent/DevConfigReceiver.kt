package ru.restofleet.agent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.restofleet.agent.data.di.AGENT_DISPATCHER
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.domain.usecase.ConfigureBrokerCredentials
import ru.restofleet.agent.domain.usecase.Provision
import ru.restofleet.shared.kernel.Outcome

/**
 * Только debug, два действия по adb (компонент экспортирован и закрыт разрешением `DUMP`, его держит оболочка adb):
 * - `DEV_CONFIG` — готовая учётка брокера:
 *   `adb shell am broadcast -a ru.restofleet.agent.DEV_CONFIG -n ru.restofleet.agent/.DevConfigReceiver
 *    --es host H --ei port P --es deviceId ID --es password PW`;
 * - `DEV_PROVISION` — регистрация по одноразовому коду, как в мастере (план 3.5, автоматизация S1):
 *   `adb shell am broadcast -a ru.restofleet.agent.DEV_PROVISION -n ru.restofleet.agent/.DevConfigReceiver
 *    --es code CODE [--es baseUrl https://10.0.2.2:8443]`; ответ — `ok deviceId=<id> role=<роль>` или код ошибки.
 * Значения extras (пароль, код) нигде не логируются: ответ `am broadcast` — `RESULT_OK` или `RESULT_CANCELED` с кодом
 * ошибки без значений. После сохранения учётки сервис агента запускается существующим путём; MQTT-сессия
 * перезапускается по смене учётки.
 */
class DevConfigReceiver : BroadcastReceiver(), KoinComponent {
    private val configure: ConfigureBrokerCredentials by inject()
    private val provision: Provision by inject()
    private val starter: ServiceStarter by inject()
    private val dispatcher: CoroutineDispatcher by inject(AGENT_DISPATCHER)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION && intent.action != ACTION_PROVISION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                val (code, data) = apply(intent)
                pending.resultCode = code
                pending.resultData = data
            } finally {
                pending.finish()
            }
        }
    }

    /** Результат для `am broadcast`; запись на диск завершена (`commit`) до возврата и до запуска сервиса. */
    internal suspend fun apply(intent: Intent): Pair<Int, String> =
        if (intent.action == ACTION_PROVISION) applyProvision(intent) else applyConfig(intent)

    private suspend fun applyConfig(intent: Intent): Pair<Int, String> {
        val result = configure(
            host = intent.getStringExtra(EXTRA_HOST),
            port = intent.getIntExtra(EXTRA_PORT, 0),
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID),
            password = intent.getStringExtra(EXTRA_PASSWORD),
        )
        return when (result) {
            is Outcome.Success -> started(RESULT_OK_DATA)
            is Outcome.Failure -> Activity.RESULT_CANCELED to result.error.code
        }
    }

    private suspend fun applyProvision(intent: Intent): Pair<Int, String> {
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: DEFAULT_BASE_URL
        return when (val result = provision(baseUrl, intent.getStringExtra(EXTRA_CODE).orEmpty())) {
            is Outcome.Success -> {
                val registration = result.value
                started("$RESULT_OK_DATA deviceId=${registration.deviceId.value} role=${registration.role?.code}")
            }
            is Outcome.Failure -> Activity.RESULT_CANCELED to result.error.code
        }
    }

    private fun started(data: String): Pair<Int, String> {
        starter.ensureRunning(REASON)
        return Activity.RESULT_OK to data
    }

    companion object {
        const val ACTION = "ru.restofleet.agent.DEV_CONFIG"
        const val ACTION_PROVISION = "ru.restofleet.agent.DEV_PROVISION"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_CODE = "code"
        const val EXTRA_BASE_URL = "baseUrl"

        /** Стенд с точки зрения эмулятора: nginx `apk-host` проксирует `/api/` на бэкенд (реш. 211). */
        const val DEFAULT_BASE_URL = "https://10.0.2.2:8443"
        const val REASON = "dev_config"
        const val RESULT_OK_DATA = "ok"
    }
}
