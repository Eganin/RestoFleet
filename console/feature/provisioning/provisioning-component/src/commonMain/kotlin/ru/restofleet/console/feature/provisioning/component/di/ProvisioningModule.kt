package ru.restofleet.console.feature.provisioning.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.provisioning.component.ProvisioningComponent
import ru.restofleet.console.feature.provisioning.component.ProvisioningComponentFactory
import ru.restofleet.console.feature.provisioning.data.ProvisioningApi
import ru.restofleet.console.feature.provisioning.data.ProvisioningRepositoryImpl
import ru.restofleet.console.feature.provisioning.domain.IssueCode
import ru.restofleet.console.feature.provisioning.domain.LoadRestaurantChoices
import ru.restofleet.console.feature.provisioning.domain.ObserveCodeCountdown
import ru.restofleet.console.feature.provisioning.domain.ProvisioningRepository
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningViewModel

val provisioningModule = module {
    single { ProvisioningApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<ProvisioningRepository> { ProvisioningRepositoryImpl(get()) }
    factory { LoadRestaurantChoices(get()) }
    factory { IssueCode(get()) }
    factory { ObserveCodeCountdown(get()) }
    single<ProvisioningComponentFactory> {
        ProvisioningComponentFactory { context, output ->
            ProvisioningComponent(context, { router -> ProvisioningViewModel(get(), get(), get(), router) }, output)
        }
    }
}
