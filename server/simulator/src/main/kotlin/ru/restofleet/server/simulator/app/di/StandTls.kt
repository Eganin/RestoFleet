package ru.restofleet.server.simulator.app.di

import javax.net.ssl.TrustManagerFactory

/** Доверие CA стенда; `null` — CA не смонтирован (только без TLS, тесты). */
class StandTls(val trust: TrustManagerFactory?)
