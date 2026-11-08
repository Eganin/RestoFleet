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
import ru.restofleet.shared.kernel.Outcome

/**
 * Только debug: приём учётки брокера командой
 * `adb shell am broadcast -a ru.restofleet.agent.DEV_CONFIG -n ru.restofleet.agent/.DevConfigReceiver
 *  --es host H --ei port P --es deviceId ID --es password PW`.
 * Компонент экспортирован и закрыт разрешением `DUMP` (его держит оболочка adb). Значения extras нигде не
 * логируются: ответ `am broadcast` — `RESULT_OK` или `RESULT_CANCELED` с кодом ошибки без значений. После сохранения
 * сервис агента запускается существующим путём; MQTT-сессия (3.6) читает учётку при старте цикла.
 */
class DevConfigReceiver : BroadcastReceiver(), KoinComponent {
    private val configure: ConfigureBrokerCredentials by inject()
    private val starter: ServiceStarter by inject()
    private val dispatcher: CoroutineDispatcher by inject(AGENT_DISPATCHER)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
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
    internal suspend fun apply(intent: Intent): Pair<Int, String> {
        val result = configure(
            host = intent.getStringExtra(EXTRA_HOST),
            port = intent.getIntExtra(EXTRA_PORT, 0),
            deviceId = intent.getStringExtra(EXTRA_DEVICE_ID),
            password = intent.getStringExtra(EXTRA_PASSWORD),
        )
        return when (result) {
            is Outcome.Success -> {
                starter.ensureRunning(REASON)
                Activity.RESULT_OK to RESULT_OK_DATA
            }
            is Outcome.Failure -> Activity.RESULT_CANCELED to result.error.code
        }
    }

    companion object {
        const val ACTION = "ru.restofleet.agent.DEV_CONFIG"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_PASSWORD = "password"
        const val REASON = "dev_config"
        const val RESULT_OK_DATA = "ok"
    }
}
