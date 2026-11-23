package ru.restofleet.console.feature.shadow.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.shadow.component.ShadowComponent
import ru.restofleet.console.feature.shadow.component.ShadowComponentFactory
import ru.restofleet.console.feature.shadow.data.ShadowApi
import ru.restofleet.console.feature.shadow.data.ShadowJsonCodec
import ru.restofleet.console.feature.shadow.data.ShadowRepositoryImpl
import ru.restofleet.console.feature.shadow.domain.AwaitShadowApplied
import ru.restofleet.console.feature.shadow.domain.BuildDesiredPatch
import ru.restofleet.console.feature.shadow.domain.LoadShadow
import ru.restofleet.console.feature.shadow.domain.RenderEditableJson
import ru.restofleet.console.feature.shadow.domain.SaveShadowForm
import ru.restofleet.console.feature.shadow.domain.SaveShadowJson
import ru.restofleet.console.feature.shadow.domain.ShadowCodec
import ru.restofleet.console.feature.shadow.domain.ShadowRepository
import ru.restofleet.console.feature.shadow.domain.ValidateShadowForm
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowViewModel

val shadowModule = module {
    single { ShadowApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<ShadowRepository> { ShadowRepositoryImpl(get(), get()) }
    single<ShadowCodec> { ShadowJsonCodec() }
    factory { ValidateShadowForm() }
    factory { BuildDesiredPatch(get()) }
    factory { LoadShadow(get()) }
    factory { SaveShadowForm(get(), get()) }
    factory { SaveShadowJson(get(), get()) }
    factory { RenderEditableJson(get()) }
    factory { AwaitShadowApplied(get()) }
    single<ShadowComponentFactory> {
        ShadowComponentFactory { context, deviceId ->
            ShadowComponent(context) { ShadowViewModel(deviceId, get(), get(), get(), get(), get()) }
        }
    }
}
