package ru.restofleet.agent.data.deviceadmin

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

/**
 * Без реального времени: установка идёт на `StandardTestDispatcher` (виртуальные часы, в том числе для таймаута
 * 120 с), широковещательное сообщение доставляется детерминированно через `idle()` главного looper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPackageInstallerSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installer = context.packageManager.packageInstaller
    private val action = "ru.restofleet.test.INSTALL_RESULT"
    private val baselineReceivers = shadowOf(context as Application).registeredReceivers.size

    private fun apk(): File = tmp.newFile().also { it.writeBytes(ByteArray(1024) { i -> i.toByte() }) }

    private fun TestScope.session(timeoutMs: Long = AndroidPackageInstallerSession.INSTALL_TIMEOUT_MS) =
        AndroidPackageInstallerSession(context, testDispatcher(), timeoutMs) { action }

    private fun TestScope.testDispatcher(): CoroutineDispatcher = StandardTestDispatcher(testScheduler)

    private fun receivers() = shadowOf(context as Application).registeredReceivers.size - baselineReceivers

    /** Система присылает итог на наш PendingIntent; приёмник динамический, доставка — на главном looper. */
    private fun deliver(status: Int?, message: String? = null) {
        val intent = Intent(action).setPackage(context.packageName)
        if (status != null) intent.putExtra(PackageInstaller.EXTRA_STATUS, status)
        if (message != null) intent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun committedSessionReportingSuccessIsSuccess() = runTest {
        val job = async { session().install(apk()) }
        runCurrent()
        assertThat(installer.mySessions).hasSize(1)

        deliver(PackageInstaller.STATUS_SUCCESS)
        runCurrent()

        assertThat(job.await()).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun systemFailureStatusIsMappedAndSessionIsAbandoned() = runTest {
        val job = async { session().install(apk()) }
        runCurrent()

        deliver(PackageInstaller.STATUS_FAILURE_ABORTED, "INSTALL_FAILED_ABORTED")
        runCurrent()

        assertThat(job.await()).isEqualTo(Outcome.Failure(DeviceAdminError.InstallFailed(3, "INSTALL_FAILED_ABORTED")))
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun pendingUserActionIsAFailureForDeviceOwner() = runTest {
        val job = async { session().install(apk()) }
        runCurrent()

        deliver(PackageInstaller.STATUS_PENDING_USER_ACTION, "confirm")
        runCurrent()

        assertThat(job.await())
            .isEqualTo(Outcome.Failure(DeviceAdminError.InstallFailed(-1, "pending_user_action")))
        assertThat(installer.mySessions).isEmpty()
    }

    @Test
    fun messageWithoutStatusIsIgnoredAndTheNextRealStatusIsAccepted() = runTest {
        val job = async { session().install(apk()) }
        runCurrent()

        deliver(status = null, message = "progress")
        runCurrent()
        assertThat(job.isCompleted).isFalse()
        assertThat(receivers()).isEqualTo(1)

        deliver(PackageInstaller.STATUS_SUCCESS)
        runCurrent()
        assertThat(job.await()).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun noAnswerForTheDefaultTimeoutIsTimeoutFailureAndSessionIsAbandoned() = runTest {
        val job = async { session().install(apk()) }
        runCurrent()

        advanceTimeBy(AndroidPackageInstallerSession.INSTALL_TIMEOUT_MS - 1)
        runCurrent()
        assertThat(job.isCompleted).isFalse()

        advanceTimeBy(2)
        runCurrent()

        assertThat(AndroidPackageInstallerSession.INSTALL_TIMEOUT_MS).isEqualTo(120_000L)
        assertThat(job.await()).isEqualTo(
            Outcome.Failure(DeviceAdminError.InstallFailed(InstallStatusMapper.TIMEOUT, "timeout")),
        )
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun unreadableApkIsAnExceptionFailureAndSessionIsAbandoned() = runTest {
        val job = async { session().install(File(tmp.root, "missing.apk")) }
        runCurrent()

        assertThat(job.await()).isEqualTo(
            Outcome.Failure(DeviceAdminError.InstallFailed(InstallStatusMapper.EXCEPTION, "FileNotFoundException")),
        )
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun cancellationAbandonsTheSessionAndIsNotSwallowed() = runTest {
        var returned: Any? = null
        var thrown: Throwable? = null
        val job = async {
            try {
                returned = session().install(apk())
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        runCurrent()
        assertThat(installer.mySessions).hasSize(1)

        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(returned).isNull() // отмена не превращена в Failure
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(installer.mySessions).isEmpty()
        assertThat(receivers()).isEqualTo(0)
    }

    @Test
    fun resultIntentIsBoundToOwnPackageAndMutable() = runTest {
        val job = async { session().install(apk()) }
        runCurrent()
        val id = installer.mySessions.single().sessionId
        val probe = Intent(action).setPackage(context.packageName)

        val pending = PendingIntent.getBroadcast(
            context, id, probe, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
        )

        assertThat(pending).isNotNull()
        val shadow = shadowOf(pending)
        assertThat(shadow.isBroadcastIntent).isTrue()
        assertThat(shadow.flags and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
        assertThat(shadow.savedIntents.single().`package`).isEqualTo(context.packageName)
        assertThat(shadow.savedIntents.single().component).isNull()
        assertThat(shadow.savedIntents.single().action).isEqualTo(action)
        job.cancelAndJoin()
    }

    @Test
    fun defaultActionsAreUniquePerInstall() = runTest {
        val actions = mutableSetOf<String>()
        repeat(2) {
            val job = async {
                AndroidPackageInstallerSession(context, testDispatcher()).install(apk())
            }
            runCurrent()
            shadowOf(context as Application).registeredReceivers
                .mapNotNull { it.intentFilter.getAction(0) }
                .filter { it.startsWith("ru.restofleet.agent.INSTALL_RESULT.") }
                .forEach { actions += it }
            job.cancelAndJoin()
        }
        assertThat(actions).hasSize(2)
    }
}
