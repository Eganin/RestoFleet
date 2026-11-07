package ru.restofleet.agent.data.deviceadmin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Тихая установка через `PackageInstaller` (`MODE_FULL_INSTALL`). Итог приходит широковещательным сообщением на
 * `PendingIntent`, привязанный к пакету приложения (`setPackage`, действие уникально для каждой установки);
 * приёмник регистрируется динамически с `RECEIVER_NOT_EXPORTED` и снимается при выходе. `FLAG_MUTABLE` на API 31+
 * обязателен: установщик дописывает в intent extras. Ожидание итога ограничено [timeoutMs]; при сбое, таймауте
 * и отмене сессия `abandon`-ится.
 */
class AndroidPackageInstallerSession(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = INSTALL_TIMEOUT_MS,
    private val newAction: () -> String = { "$ACTION_PREFIX${UUID.randomUUID()}" },
) : PackageInstallerSession {

    private class Result(val status: Int, val message: String?)

    @Suppress("TooGenericExceptionCaught") // любой сбой установки — результат; Error и отмена не перехватываются
    override suspend fun install(apk: File): Outcome<Unit, DeviceAdminError> {
        val packageInstaller = context.packageManager.packageInstaller
        val action = newAction()
        val result = CompletableDeferred<Result>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Система всегда присылает EXTRA_STATUS; сообщение без него итогом не считается (дождёмся таймаута)
                if (!intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                result.complete(Result(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        var sessionId = NO_SESSION
        var pending: PendingIntent? = null
        return try {
            withContext(ioDispatcher) {
                sessionId = packageInstaller.createSession(
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
                )
                pending = resultIntent(sessionId, action)
                packageInstaller.openSession(sessionId).use { session ->
                    session.openWrite(APK_NAME, 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    session.commit(pending!!.intentSender)
                }
            }
            val outcome = withTimeoutOrNull(timeoutMs) { result.await() }
                ?.let { InstallStatusMapper.map(it.status, it.message) }
                ?: Outcome.Failure(DeviceAdminError.InstallFailed(InstallStatusMapper.TIMEOUT, "timeout"))
            if (outcome is Outcome.Failure) abandon(packageInstaller, sessionId)
            outcome
        } catch (e: CancellationException) {
            abandon(packageInstaller, sessionId)
            currentCoroutineContext().ensureActive()
            failed(e)
        } catch (e: Exception) {
            abandon(packageInstaller, sessionId)
            currentCoroutineContext().ensureActive()
            failed(e)
        } finally {
            context.unregisterReceiver(receiver)
            pending?.cancel()
        }
    }

    private fun resultIntent(sessionId: Int, action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    /** Сессия могла уже завершиться или не создаться — `abandon` лучшего усилия. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun abandon(packageInstaller: PackageInstaller, sessionId: Int) {
        if (sessionId == NO_SESSION) return
        try {
            packageInstaller.abandonSession(sessionId)
        } catch (e: Exception) {
            // сессия уже закрыта системой: действий не требуется
        }
    }

    private fun failed(e: Exception) =
        Outcome.Failure(DeviceAdminError.InstallFailed(InstallStatusMapper.EXCEPTION, e::class.simpleName))

    companion object {
        const val INSTALL_TIMEOUT_MS = 120_000L
        private const val ACTION_PREFIX = "ru.restofleet.agent.INSTALL_RESULT."
        private const val APK_NAME = "base.apk"
        private const val NO_SESSION = -1
    }
}
