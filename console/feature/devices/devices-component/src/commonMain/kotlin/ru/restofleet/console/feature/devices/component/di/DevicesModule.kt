package ru.restofleet.console.feature.devices.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.devices.component.DevicesComponent
import ru.restofleet.console.feature.devices.component.DevicesComponentFactory
import ru.restofleet.console.feature.devices.data.DevicesApi
import ru.restofleet.console.feature.devices.data.DevicesRepositoryImpl
import ru.restofleet.console.feature.devices.domain.DevicesRepository
import ru.restofleet.console.feature.devices.domain.LoadDevicesPage
import ru.restofleet.console.feature.devices.domain.LoadRestaurantOptions
import ru.restofleet.console.feature.devices.domain.ObserveDevicesEvents
import ru.restofleet.console.feature.devices.ui.viewmodel.DevicesViewModel

val devicesModule = module {
    single { DevicesApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<DevicesRepository> { DevicesRepositoryImpl(get(), get(), get()) }
    factory { LoadDevicesPage(get()) }
    factory { LoadRestaurantOptions(get()) }
    factory { ObserveDevicesEvents(get()) }
    single<DevicesComponentFactory> {
        DevicesComponentFactory { context, filter, title, output ->
            DevicesComponent(
                context,
                { router -> DevicesViewModel(get(), get(), get(), router, filter, title) },
                output,
            )
        }
    }
}
