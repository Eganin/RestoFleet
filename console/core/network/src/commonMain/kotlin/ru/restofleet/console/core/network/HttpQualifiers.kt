package ru.restofleet.console.core.network

/** Имена клиентов в DI: с токеном (по умолчанию для API) и без (вход, refresh). */
object HttpQualifiers {
    const val AUTHORIZED = "http.authorized"
    const val BARE = "http.bare"
}
