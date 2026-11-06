package ru.restofleet.agent.data.probes

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * Базовый клиент проб для продакшна: системное хранилище доверия, проверка имени хоста включена,
 * только современный TLS (cleartext-соединения запрещены на уровне клиента, не только политикой Android).
 */
fun productionProbeClient(): OkHttpClient =
    OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()
