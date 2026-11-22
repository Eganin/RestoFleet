package ru.restofleet.console.feature.devicedetails.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.devicedetails.component.DeviceDetailsComponent
import ru.restofleet.console.feature.devicedetails.component.DeviceDetailsComponentFactory
import ru.restofleet.console.feature.devicedetails.data.DeviceDetailsApi
import ru.restofleet.console.feature.devicedetails.data.DeviceDetailsRepositoryImpl
import ru.restofleet.console.feature.devicedetails.domain.DeviceDetailsRepository
import ru.restofleet.console.feature.devicedetails.domain.LoadDeviceCard
import ru.restofleet.console.feature.devicedetails.domain.LoadEvents
import ru.restofleet.console.feature.devicedetails.domain.LoadProbes
import ru.restofleet.console.feature.devicedetails.domain.LoadTemperatureHistory
import ru.restofleet.console.feature.devicedetails.domain.ObserveDeviceLive
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.DetailsViewModel

val deviceDetailsModule = module {
    single { DeviceDetailsApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<DeviceDetailsRepository> { DeviceDetailsRepositoryImpl(get(), get(), get()) }
    factory { LoadDeviceCard(get()) }
    factory { LoadTemperatureHistory(get(), get()) }
    factory { LoadProbes(get()) }
    factory { LoadEvents(get()) }
    factory { ObserveDeviceLive(get()) }
    single<DeviceDetailsComponentFactory> {
        DeviceDetailsComponentFactory { context, deviceId, shadow, commands, output ->
            DeviceDetailsComponent(
                context,
                { router ->
                    DetailsViewModel(deviceId, get(), get(), get(), get(), get(), router)
                },
                shadow,
                commands,
                output,
            )
        }
    }
}
