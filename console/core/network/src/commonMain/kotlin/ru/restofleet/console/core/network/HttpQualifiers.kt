package ru.restofleet.console.core.network

/** Имена клиентов в DI: с токеном (по умолчанию для API), без (вход, refresh) и WebSocket (RV-2). */
object HttpQualifiers {
    const val AUTHORIZED = "http.authorized"
    const val BARE = "http.bare"
    const val REALTIME = "http.realtime"
}
