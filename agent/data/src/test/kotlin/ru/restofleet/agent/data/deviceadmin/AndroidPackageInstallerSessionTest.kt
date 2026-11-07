package ru.restofleet.agent.data.deviceadmin

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPackageInstallerSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installer = context.packageManager.packageInstaller
    private val action = "ru.restofleet.test.INSTALL_RESULT"
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun apk(): File = tmp.newFile().also { it.writeBytes(ByteArray(1024) { i -> i.toByte() }) }

    private fun session(timeoutMs: Long = 10_000) =
        AndroidPackageInstallerSession(context, Dispatchers.IO, timeoutMs) { action }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val baselineReceivers = shadowOf(context as Application).registeredReceivers.size

    private fun receivers() = shadowOf(context as Application).registeredReceivers.size - baselineReceivers

    /** Крутит главный looper (доставка broadcast), пока [block] не вернёт значение. */
    private fun <T : Any> pumpUntil(timeoutMs: Long = 10_000, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            block()?.let { return it }
            Thread.sleep(10)
        }
        error("не дождались условия")
    }

    private fun <T> await(job: Deferred<T>): T {
        pumpUntil { if (job.isCompleted) true else null }
        return runBlocking { job.await() }
    }

    private fun sessionId(): Int = pumpUntil { installer.mySessions.firstOrNull()?.sessionId }

    /** Имитирует итог системы: broadcast на наш PendingIntent (после commit; до него приёмник уже зарегистрирован). */
    private fun deliver(status: Int, message: String?) {
        context.sendBroadcast(
            Intent(action).setPackage(context.packageName)
                .putExtra(PackageInstaller.EXTRA_STATUS, status)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message),
        )
        idle()
    }

    @Test
    fun committedSessionReportingSuccessIsSuccess() {
        val job = scope.async { session().install(apk()) }
        sessionId()
        pumpUntil {
            deliver(PackageInstaller.STATUS_SUCCESS, null)
            if (job.isCompleted) true else null
        }
        assertThat(await(job)).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun systemFailureStatusIsMappedAndSessionIsAbandoned() {
        val job = scope.async { session().install(apk()) }
        sessionId()
        pumpUntil {
            deliver(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED")
            if (job.isCompleted) true else null
        }
        assertThat(await(job)).isEqualTo(Outcome.Failure(DeviceAdminError.InstallFailed(3, "INSTALL_FAILED_ABORTED")))
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun pendingUserActionIsAFailureForDeviceOwner() {
        val job = scope.async { session().install(apk()) }
        sessionId()
        pumpUntil {
            deliver(PackageInstaller.STATUS_PENDING_USER_ACTION, "confirm")
            if (job.isCompleted) true else null
        }
        assertThat(await(job))
            .isEqualTo(Outcome.Failure(DeviceAdminError.InstallFailed(-1, "pending_user_action")))
        assertThat(installer.mySessions).isEmpty()
    }

    @Test
    fun noAnswerWithinTimeoutIsTimeoutFailureAndSessionIsAbandoned() {
        val job = scope.async { session(timeoutMs = 300).install(apk()) }
        assertThat(await(job)).isEqualTo(
            Outcome.Failure(DeviceAdminError.InstallFailed(InstallStatusMapper.TIMEOUT, "timeout")),
        )
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun unreadableApkIsAnExceptionFailureAndSessionIsAbandoned() {
        val job = scope.async { session().install(File(tmp.root, "missing.apk")) }
        assertThat(await(job)).isEqualTo(
            Outcome.Failure(DeviceAdminError.InstallFailed(InstallStatusMapper.EXCEPTION, "FileNotFoundException")),
        )
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun cancellationAbandonsTheSessionAndIsNotSwallowed() {
        var returned: Any? = null
        var thrown: Throwable? = null
        val job = scope.async {
            try {
                returned = session().install(apk())
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        sessionId()
        runBlocking { job.cancelAndJoin() }
        assertThat(job.isCancelled).isTrue()
        assertThat(returned).isNull() // отмена не превращена в Failure
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun resultIntentIsBoundToOwnPackageAndMutable() {
        val job = scope.async { session(timeoutMs = 3_000).install(apk()) }
        val id = sessionId()
        val probe = Intent(action).setPackage(context.packageName)
        val pending = pumpUntil {
            PendingIntent.getBroadcast(context, id, probe, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)
        }
        val shadow = shadowOf(pending)
        assertThat(shadow.isBroadcastIntent).isTrue()
        assertThat(shadow.flags and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
        assertThat(shadow.savedIntents.single().`package`).isEqualTo(context.packageName)
        assertThat(shadow.savedIntents.single().component).isNull()
        assertThat(shadow.savedIntents.single().action).isEqualTo(action)
        await(job)
    }

    @Test
    fun defaultActionsAreUniquePerInstall() {
        val actions = mutableSetOf<String>()
        repeat(2) {
            val job = scope.async { AndroidPackageInstallerSession(context, Dispatchers.IO, 300L).install(apk()) }
            pumpUntil {
                shadowOf(context as Application).registeredReceivers
                    .mapNotNull { it.intentFilter.getAction(0) }
                    .firstOrNull { it.startsWith("ru.restofleet.agent.INSTALL_RESULT.") }
                    ?.also { actions += it }
            }
            await(job)
        }
        assertThat(actions).hasSize(2)
        assertThat(actions.all { it.startsWith("ru.restofleet.agent.INSTALL_RESULT.") }).isTrue()
    }
}
