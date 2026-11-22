package ru.restofleet.server.api.di

import org.koin.dsl.module
import ru.restofleet.server.api.ApiSettings
import ru.restofleet.server.api.auth.JwtAccessTokenIssuer
import ru.restofleet.server.domain.auth.AccessTokenIssuer

/** Порт выпуска access JWT для `Login`/`RefreshSession` (реш. 250). Требует [ApiSettings] из `server:app`. */
val apiModule = module {
    single<AccessTokenIssuer> { JwtAccessTokenIssuer(get<ApiSettings>().jwt, get(), get()) }
}
