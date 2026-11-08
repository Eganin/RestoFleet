package ru.restofleet.agent.data.deviceadmin

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.port.ApkChecksumSource
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import java.io.IOException
import kotlin.coroutines.resume

/**
 * `GET <url>.sha256` на клиенте APK (системный trust store): только `https://` (проверка [Capabilities.isHttpsUrl]),
 * редиректы только https→https, тело не больше 1 КиБ, первый токен — ровно 64 hex-символа. Сам файл APK не
 * качается. Отмена корутины отменяет HTTP-вызов. Причины отказа — коды [ChecksumError], без текста исключений.
 */
class HttpApkChecksumSource(client: OkHttpClient) : ApkChecksumSource {
    private val strict = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun fetch(checksumUrl: String): Outcome<String, DomainError> {
        val url = checksumUrl.takeIf(Capabilities::isHttpsUrl)?.toHttpUrlOrNull()
            ?: return failure("https_required")
        return suspendCancellableCoroutine { continuation ->
            val call = strict.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(failure("network:${e::class.simpleName}"))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response.use(::parse))
                    }
                },
            )
        }
    }

    private fun parse(response: Response): Outcome<String, DomainError> = when {
        !response.isSuccessful -> failure("http_${response.code}")
        response.body.contentLength() > MAX_BODY_BYTES -> failure("checksum_too_large")
        else -> readToken(response.body.source())
    }

    @Suppress("SwallowedException") // класс исключения передаётся в причину
    private fun readToken(source: BufferedSource): Outcome<String, DomainError> =
        try {
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) {
                failure("checksum_too_large")
            } else {
                val token = source.readUtf8().trimStart().takeWhile { !it.isWhitespace() }
                if (SHA256.matches(token)) Outcome.Success(token.lowercase()) else failure("checksum_invalid")
            }
        } catch (e: IOException) {
            failure("network:${e::class.simpleName}")
        }

    private fun failure(code: String) = Outcome.Failure(ChecksumError(code))

    private companion object {
        const val MAX_BODY_BYTES = 1024L
        val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}
