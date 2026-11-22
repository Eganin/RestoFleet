package ru.restofleet.console.feature.auth.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.auth.component.LoginComponent
import ru.restofleet.console.feature.auth.component.LoginComponentFactory
import ru.restofleet.console.feature.auth.data.AuthApi
import ru.restofleet.console.feature.auth.data.AuthRepositoryImpl
import ru.restofleet.console.feature.auth.domain.AuthRepository
import ru.restofleet.console.feature.auth.domain.Login
import ru.restofleet.console.feature.auth.domain.Logout
import ru.restofleet.console.feature.auth.domain.ObserveSession
import ru.restofleet.console.feature.auth.domain.RestoreSession
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginViewModel

val authModule = module {
    single { AuthApi(get(named(HttpQualifiers.BARE))) }
    single<AuthRepository> { AuthRepositoryImpl(get(), get(), get()) }
    factory { Login(get()) }
    factory { Logout(get()) }
    factory { RestoreSession(get()) }
    factory { ObserveSession(get()) }
    single<LoginComponentFactory> {
        LoginComponentFactory { context, onLoggedIn ->
            LoginComponent(context, { router -> LoginViewModel(get(), router) }, onLoggedIn)
        }
    }
}
