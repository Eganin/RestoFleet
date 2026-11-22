package ru.restofleet.console.feature.alerts.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.alerts.component.AlertsComponent
import ru.restofleet.console.feature.alerts.component.AlertsComponentFactory
import ru.restofleet.console.feature.alerts.data.AlertsApi
import ru.restofleet.console.feature.alerts.data.AlertsRepositoryImpl
import ru.restofleet.console.feature.alerts.domain.AcknowledgeAlert
import ru.restofleet.console.feature.alerts.domain.AlertsRepository
import ru.restofleet.console.feature.alerts.domain.LoadAlerts
import ru.restofleet.console.feature.alerts.domain.ObserveAlertsEvents
import ru.restofleet.console.feature.alerts.domain.ResolveAlert
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsViewModel

val alertsModule = module {
    single { AlertsApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<AlertsRepository> { AlertsRepositoryImpl(get(), get(), get()) }
    factory { LoadAlerts(get()) }
    factory { AcknowledgeAlert(get()) }
    factory { ResolveAlert(get()) }
    factory { ObserveAlertsEvents(get()) }
    single<AlertsComponentFactory> {
        AlertsComponentFactory { context, output ->
            AlertsComponent(context, { router -> AlertsViewModel(get(), get(), get(), get(), router) }, output)
        }
    }
}
