package ru.restofleet.agent.data.provisioning

import okhttp3.OkHttpClient
import java.time.Duration

private const val CONNECT_TIMEOUT_SEC = 10L
private const val CALL_TIMEOUT_SEC = 30L

/**
 * HTTP-клиент провижининга (R-17): код одноразовый, поэтому без повторов при обрыве (повтор мог бы погасить код
 * вторым запросом) и без редиректов (код не уходит на чужой адрес). Доверие TLS добавляет [OkHttpProvisioningApi].
 */
fun provisioningHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
    .callTimeout(Duration.ofSeconds(CALL_TIMEOUT_SEC))
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
