package ru.restofleet.agent.data.deviceadmin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Скачивание APK во `cacheDir/apk/` под случайным именем (имя из URL не используется). Только `https://`; редиректы
 * только https→https (политика навязывается здесь же, независимо от переданного клиента); размер ограничен
 * [maxBytes] и по заголовку, и при потоковом чтении. Запись идёт в `.part` и переименовывается по завершении; при
 * любом сбое и при отмене файлы удаляются. Чтение тела — на [ioDispatcher], отмена корутины отменяет HTTP-вызов.
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val maxBytes: Long = MAX_APK_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newName: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Удаляет всё из `cacheDir/apk/`: там лежат только временные файлы загрузчика, а загрузка одна (замок в
     * `DpmDeviceAdmin`). Нужна, потому что после убийства процесса или перезагрузки посреди загрузки `.part`/`.apk`
     * (до [maxBytes]) остаются навсегда. Вызывать только под замком установки, до начала загрузки.
     */
    fun clearStale() {
        File(cacheDir, DIR_NAME).listFiles()?.forEach { it.delete() }
    }

    /** Сбой — [DeviceAdminError.DownloadFailed] с причиной `https_required`, `invalid_url`, `http_<код>` и др. */
    suspend fun download(url: String): Outcome<File, DeviceAdminError> {
        val httpUrl = url.toHttpUrlOrNull()
        return when {
            httpUrl == null -> failed("invalid_url")
            !httpUrl.isHttps -> failed("https_required")
            else -> downloadTo(httpUrl)
        }
    }

    @Suppress("TooGenericExceptionCaught") // любой сбой загрузки — результат; Error и отмена не перехватываются
    private suspend fun downloadTo(url: HttpUrl): Outcome<File, DeviceAdminError> {
        val dir = File(cacheDir, DIR_NAME)
        val target = File(dir, "${newName()}.apk")
        val part = File(dir, "${target.name}.part")
        var saved = false
        return try {
            withContext(ioDispatcher) { fetch(url, dir, part, target) }.also { saved = it is Outcome.Success }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failed("network:${e::class.simpleName}")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            failed("network:${e::class.simpleName}")
        } finally {
            if (!saved) {
                part.delete()
                target.delete()
            }
        }
    }

    private suspend fun fetch(url: HttpUrl, dir: File, part: File, target: File): Outcome<File, DeviceAdminError> =
        coroutineScope {
            val strict = client.newBuilder()
                .followRedirects(true)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
            val call = strict.newCall(Request.Builder().url(url).build())
            // Отмена корутины отменяет вызов немедленно (даже если поток заблокирован в чтении сокета)
            val canceller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { save(it, dir, part, target) }
            } finally {
                canceller.cancel()
            }
        }

    private fun save(response: Response, dir: File, part: File, target: File): Outcome<File, DeviceAdminError> {
        val rejected = precheck(response, dir) ?: copyBody(response.body, part) ?: rename(part, target)
        return if (rejected == null) Outcome.Success(target) else failed(rejected)
    }

    /** Причина отказа до записи либо `null`. */
    private fun precheck(response: Response, dir: File): String? = when {
        !response.isSuccessful -> "http_${response.code}"
        response.body.contentLength() > maxBytes -> "too_large"
        !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory -> "io:storage"
        else -> null
    }

    /** Копирует тело в [part] с ограничением размера; причина отказа либо `null`. */
    private fun copyBody(body: ResponseBody, part: File): String? =
        openForWrite(part).let { opened ->
            if (opened.error != null) {
                opened.error
            } else {
                opened.stream!!.use { out -> body.byteStream().use { input -> copyLimited(input, out, maxBytes) } }
            }
        }

    private fun rename(part: File, target: File): String? = if (part.renameTo(target)) null else "io:storage"

    private fun failed(reason: String) = Outcome.Failure(DeviceAdminError.DownloadFailed(reason))

    companion object {
        /** Предельный размер APK: 200 МБ. */
        const val MAX_APK_BYTES: Long = 200L * 1024 * 1024
        private const val DIR_NAME = "apk"
    }
}

private const val BUFFER_SIZE = 16 * 1024

private class Opened(val stream: OutputStream?, val error: String?)

/** Ошибки диска (нет прав или места) — `io:<класс>`, а не `network:`; ошибки чтения из сети остаются сетевыми. */
@Suppress("SwallowedException") // класс исключения передаётся в причину
private fun openForWrite(part: File): Opened =
    try {
        Opened(part.outputStream(), null)
    } catch (e: IOException) {
        Opened(null, "io:${e::class.simpleName}")
    }

@Suppress("SwallowedException") // класс исключения передаётся в причину
private fun copyLimited(input: InputStream, out: OutputStream, maxBytes: Long): String? {
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    var rejected: String? = null
    while (rejected == null) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        rejected = if (total > maxBytes) "too_large" else write(out, buffer, read)
    }
    return rejected
}

@Suppress("SwallowedException") // класс исключения передаётся в причину
private fun write(out: OutputStream, buffer: ByteArray, count: Int): String? =
    try {
        out.write(buffer, 0, count)
        null
    } catch (e: IOException) {
        "io:${e::class.simpleName}"
    }
