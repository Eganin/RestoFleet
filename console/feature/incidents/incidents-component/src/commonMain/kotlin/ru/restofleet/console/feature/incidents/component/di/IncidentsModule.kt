package ru.restofleet.console.feature.incidents.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.incidents.component.IncidentDetailsComponent
import ru.restofleet.console.feature.incidents.component.IncidentDetailsComponentFactory
import ru.restofleet.console.feature.incidents.component.IncidentsComponent
import ru.restofleet.console.feature.incidents.component.IncidentsComponentFactory
import ru.restofleet.console.feature.incidents.data.IncidentsApi
import ru.restofleet.console.feature.incidents.data.IncidentsRepositoryImpl
import ru.restofleet.console.feature.incidents.domain.AcknowledgeIncident
import ru.restofleet.console.feature.incidents.domain.IncidentsRepository
import ru.restofleet.console.feature.incidents.domain.LoadIncidentDetails
import ru.restofleet.console.feature.incidents.domain.LoadIncidents
import ru.restofleet.console.feature.incidents.domain.ObserveIncidentsEvents
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsViewModel
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsViewModel

val incidentsModule = module {
    single { IncidentsApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<IncidentsRepository> { IncidentsRepositoryImpl(get(), get(), get()) }
    factory { LoadIncidents(get()) }
    factory { LoadIncidentDetails(get()) }
    factory { AcknowledgeIncident(get()) }
    factory { ObserveIncidentsEvents(get()) }
    single<IncidentsComponentFactory> {
        IncidentsComponentFactory { context, output ->
            IncidentsComponent(context, { router -> IncidentsViewModel(get(), get(), router) }, output)
        }
    }
    single<IncidentDetailsComponentFactory> {
        IncidentDetailsComponentFactory { context, incidentId, output ->
            IncidentDetailsComponent(
                context,
                { router -> IncidentDetailsViewModel(incidentId, get(), get(), get(), router) },
                output,
            )
        }
    }
}
