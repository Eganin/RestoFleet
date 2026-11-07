package ru.restofleet.agent.data.deviceadmin

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.time.Duration

private val CONNECT_TIMEOUT = Duration.ofSeconds(15)
private val READ_TIMEOUT = Duration.ofSeconds(60)
private val CALL_TIMEOUT = Duration.ofMinutes(10)

/**
 * Клиент загрузки APK: системное хранилище доверия (никакого trust-all, без пиннинга), только современный TLS
 * (cleartext невозможен на уровне клиента), редиректы только https→https (`followSslRedirects(false)` запрещает
 * переход между схемами), без повторов при сбое соединения. Доверие к CA стенда в debug-сборке — задача 2.5.
 */
fun apkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
    .connectTimeout(CONNECT_TIMEOUT)
    .readTimeout(READ_TIMEOUT)
    .callTimeout(CALL_TIMEOUT)
    .followRedirects(true)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()
