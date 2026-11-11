package ru.restofleet.agent.data.di

import android.os.Build
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.data.provisioning.OkHttpProvisioningApi
import ru.restofleet.agent.domain.port.ProvisioningApi
import ru.restofleet.agent.domain.usecase.GetAgentStatus
import ru.restofleet.agent.domain.usecase.GetPermissions
import ru.restofleet.agent.domain.usecase.GetRegistration
import ru.restofleet.agent.domain.usecase.Provision
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import java.time.Duration

private const val CONNECT_TIMEOUT_SEC = 10L
private const val CALL_TIMEOUT_SEC = 30L
private const val MAX_NAME = 100
private const val MAX_OS_VERSION = 32
private const val MAX_AGENT_VERSION = 64

/**
 * Провижининг (план 3.5) и use cases мастера и экрана статуса (3.6); логики здесь нет. Нужны извне: `ProvisioningTrust`
 * (debug — CA стенда, release — системное доверие; `agent:app`), `CredentialStore`, `CapabilitiesSource`,
 * `DeviceInfoSource`, `Outbox`, `ShadowStore`, `ConnectionMonitor` и `ProbeStates` (из [mqttModule]),
 * `AGENT_DISPATCHER`. Сведения об устройстве обрезаются до пределов контракта K2 (модель ≤ 100, ОС ≤ 32, версия ≤ 64).
 */
val provisioningModule: Module = module {
    single<ProvisioningApi> {
        val context = androidContext()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        OkHttpProvisioningApi(
            // Код одноразовый: без повторов при обрыве и без редиректов
            baseClient = OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .callTimeout(Duration.ofSeconds(CALL_TIMEOUT_SEC))
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
            trust = get(),
            device = DeviceInfoDto(
                model = Build.MODEL.orEmpty().take(MAX_NAME),
                manufacturer = Build.MANUFACTURER.orEmpty().take(MAX_NAME),
                osVersion = Build.VERSION.RELEASE.orEmpty().take(MAX_OS_VERSION),
                apiLevel = Build.VERSION.SDK_INT,
            ),
            agentVersion = version.take(MAX_AGENT_VERSION),
            dispatcher = get(AGENT_DISPATCHER),
        )
    }
    single { Provision(get(), get()) }
    single { GetRegistration(get()) }
    single { GetPermissions(get()) }
    single { GetAgentStatus(get(), get(), get(), get(), get(), get(), get()) }
}
