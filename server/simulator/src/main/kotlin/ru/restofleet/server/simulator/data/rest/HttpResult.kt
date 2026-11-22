package ru.restofleet.server.simulator.data.rest

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ответ HTTP целиком: код и тело (тела небольшие — JSON API). */
data class HttpResult(val code: Int, val body: String, val retryAfterSec: Long? = null)

internal val JSON_MEDIA = "application/json".toMediaType()

internal fun String.jsonBody(): RequestBody = toRequestBody(JSON_MEDIA)

/** Асинхронный вызов OkHttp (диспетчер клиента) без блокировки потока корутины; отмена корутины отменяет вызов. */
internal suspend fun OkHttpClient.send(request: Request): HttpResult = newCall(request).await().use { response ->
    HttpResult(response.code, response.body.string(), response.header("Retry-After")?.toLongOrNull())
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation { cancel() }
}

/** Сеть недоступна или ответ не прочитан: `null` вместо исключения — для циклов устройств. */
internal suspend fun OkHttpClient.sendOrNull(request: Request): HttpResult? = try {
    send(request)
} catch (_: IOException) {
    null
}
