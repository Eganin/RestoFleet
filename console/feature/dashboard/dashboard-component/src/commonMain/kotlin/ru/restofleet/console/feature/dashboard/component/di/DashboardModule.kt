package ru.restofleet.console.feature.dashboard.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.dashboard.component.DashboardComponent
import ru.restofleet.console.feature.dashboard.component.DashboardComponentFactory
import ru.restofleet.console.feature.dashboard.data.DashboardApi
import ru.restofleet.console.feature.dashboard.data.DashboardRepositoryImpl
import ru.restofleet.console.feature.dashboard.domain.DashboardRepository
import ru.restofleet.console.feature.dashboard.domain.LoadDashboard
import ru.restofleet.console.feature.dashboard.domain.ObserveDashboardEvents
import ru.restofleet.console.feature.dashboard.ui.viewmodel.DashboardViewModel

val dashboardModule = module {
    single { DashboardApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<DashboardRepository> { DashboardRepositoryImpl(get(), get(), get()) }
    factory { LoadDashboard(get()) }
    factory { ObserveDashboardEvents(get()) }
    single<DashboardComponentFactory> {
        DashboardComponentFactory { context, output ->
            DashboardComponent(context, { router -> DashboardViewModel(get(), get(), router) }, output)
        }
    }
}
